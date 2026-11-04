package com.photopilot.integration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;
import com.photopilot.model.EquipmentData;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearNames;
import com.photopilot.model.SpecificationEvidence;
import org.w3c.dom.Element;

/**
 * Stage 1 UML element 98 (zero-based): {Adapter; sources, conflicts and unknowns retained}
 * F04 external specification retrieval from two independent, free, key-less sources:
 * <ul>
 * <li>Wikipedia (MediaWiki Action API: search, then the lead-section wikitext of the best pages). Only articles with a
 * camera or photographic-lens infobox whose name or bold model code exactly matches are used; infobox values are
 * parsed field by field and unparseable values are omitted. Evidence links to the exact revision (CC BY-SA).</li>
 * <li>Lensfun's open lens/camera database (XML data files, CC BY-SA 3.0): product identity and mount label.</li>
 * </ul>
 * Each source returns at most one matching product. Nothing is inferred: a value is present only when the source
 * states it. No hard-coded product list is used; endpoints are configurable (tests use loopback fakes).
 */
public class EquipmentCatalogAdapter implements EquipmentSpecificationProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://en.wikipedia.org/w/api.php");
    public static final URI DEFAULT_LENSFUN = URI.create("https://raw.githubusercontent.com/lensfun/lensfun/master/data/db/");
    public static final String WIKIPEDIA = "Wikipedia", LENSFUN = "Lensfun";
    /** Wikimedia's API etiquette asks for an identifying agent with a contact URL. */
    static final String USER_AGENT = "PhotoPilot/0.1 (https://github.com/zboToronto/PhotoPilot; EECS3311 course project)";
    private static final int MAX_BODY = 8 * 1024 * 1024, MAX_PAGES = 4;
    private static final Duration CACHE = Duration.ofHours(24);
    private static final String NUMBER = "([0-9]+(?:\\.[0-9]+)?)";

    private URI endpoint;
    private HttpClient http;
    private final URI lensfun;
    private final Duration timeout;
    private final Clock clock;
    private final Map<String, Cached> lensfunFiles = new ConcurrentHashMap<>();
    private record Cached(Instant fetched, Optional<String> body) { }
    private record Page(String title, long revision, String wikitext) { }

    public EquipmentCatalogAdapter(URI endpoint, HttpClient http) {
        this(endpoint, DEFAULT_LENSFUN, http, Duration.ofSeconds(15), Clock.systemUTC());
    }

    public EquipmentCatalogAdapter(URI endpoint, URI lensfun, HttpClient http, Duration timeout, Clock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        String base = Objects.requireNonNull(lensfun, "lensfun").toString();
        this.lensfun = URI.create(base.endsWith("/") ? base : base + "/");
        this.http = Objects.requireNonNull(http, "http");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stage 1 lookup by model text: catalogued products whose stated values form complete F05 equipment. */
    @Override
    public Result<List<Equipment>> lookup(String model) {
        if (model == null || model.isBlank()) return Result.failureKey("import.error.identityRequired");
        var items = new ArrayList<Equipment>();
        Result<List<SpecificationEvidence>> last = Result.success(List.of());
        for (EquipmentType type : List.of(EquipmentType.CAMERA, EquipmentType.LENS)) {
            last = search(type, "", model, "");
            if (!last.isSuccess()) continue;
            for (var record : last.value().orElseThrow()) {
                var specs = new LinkedHashMap<>(record.values()); String weight = specs.remove("weightGrams");
                var sources = new LinkedHashMap<String, String>(); specs.keySet().forEach(key -> sources.put(key, record.url().toString()));
                new EquipmentData(type.name(), UUID.randomUUID().toString(), record.brand(), record.model(), 1,
                    weight == null ? null : Double.valueOf(weight), specs.keySet(), sources, record.retrievedAt().toString(), specs)
                    .toEquipment().value().ifPresent(items::add);
            }
        }
        return items.isEmpty() && !last.isSuccess() ? Result.failure(last) : Result.success(items);
    }

    @Override
    public Result<List<SpecificationEvidence>> search(EquipmentType type, String brand, String model, String modelCode) {
        if (type == null || model == null || model.isBlank()) return Result.failureKey("import.error.identityRequired");
        if (type != EquipmentType.CAMERA && type != EquipmentType.LENS) return Result.success(List.of());
        String maker = Objects.requireNonNullElse(brand, "").strip(), name = model.strip(), code = Objects.requireNonNullElse(modelCode, "").strip();
        var records = new ArrayList<SpecificationEvidence>();
        var unavailable = new ArrayList<String>();
        Result<?> firstFailure = null;
        var wiki = wikipedia(type, maker, name, code);
        if (wiki.isSuccess()) wiki.value().orElseThrow().ifPresent(records::add);
        else { unavailable.add(WIKIPEDIA); firstFailure = wiki; }
        int attempted = 1;
        if (!maker.isEmpty()) {
            attempted++;
            var catalogue = lensfun(type, maker, name, code);
            if (catalogue.isSuccess()) catalogue.value().orElseThrow().ifPresent(records::add);
            else { unavailable.add(LENSFUN); if (firstFailure == null) firstFailure = catalogue; }
        }
        if (unavailable.size() == attempted) return Result.failure(firstFailure);
        return new Result<>(Optional.of(List.copyOf(records)), List.of(), unavailable);
    }

    // ---------------------------------------------------------------- Wikipedia

    private Result<Optional<SpecificationEvidence>> wikipedia(EquipmentType type, String brand, String model, String code) {
        var titles = new LinkedHashSet<String>();
        var queries = new LinkedHashSet<String>();
        queries.add((brand + " " + model).strip());
        if (!code.isEmpty() && !GearNames.alnum(model).contains(GearNames.alnum(code))) queries.add((brand + " " + code).strip());
        for (String query : queries) {
            var found = fetch(api("action", "query", "list", "search", "srsearch", query, "srlimit", "5", "srnamespace", "0",
                "format", "json", "formatversion", "2"), WIKIPEDIA);
            if (!found.isSuccess()) return Result.failure(found);
            var body = found.value().orElseThrow();
            if (body.isEmpty()) continue;
            try {
                var hits = JsonParser.parseString(body.get()).getAsJsonObject().getAsJsonObject("query").getAsJsonArray("search");
                for (JsonElement hit : hits) titles.add(hit.getAsJsonObject().get("title").getAsString());
            } catch (RuntimeException malformed) { return Result.failureKey("import.error.catalogResponse", WIKIPEDIA); }
        }
        int checked = 0;
        for (String title : titles) {
            if (checked++ >= MAX_PAGES) break;
            var fetched = fetch(api("action", "parse", "page", title, "prop", "wikitext|revid", "section", "0", "redirects", "1",
                "format", "json", "formatversion", "2"), WIKIPEDIA);
            if (!fetched.isSuccess()) return Result.failure(fetched);
            if (fetched.value().orElseThrow().isEmpty()) continue;
            Page page = page(fetched.value().orElseThrow().get());
            if (page == null) continue;
            var infobox = infobox(page.wikitext(), type);
            if (infobox == null || !matches(brand, model, code, page, infobox)) continue;
            String maker = clean(infobox.getOrDefault("maker", infobox.getOrDefault("manufacturer", "")));
            var values = type == EquipmentType.CAMERA ? cameraValues(infobox) : lensValues(infobox);
            URI url = page.revision() > 0 ? endpoint.resolve("index.php?oldid=" + page.revision())
                : endpoint.resolve("index.php?title=" + URLEncoder.encode(page.title(), StandardCharsets.UTF_8));
            return Result.success(Optional.of(new SpecificationEvidence(WIKIPEDIA, url, type, maker.isEmpty() ? brand : maker,
                page.title(), values, clock.instant())));
        }
        return Result.success(Optional.empty());
    }

    private static Page page(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("parse")) return null;
            JsonObject parse = root.getAsJsonObject("parse");
            JsonElement text = parse.get("wikitext");
            String wikitext = text == null ? "" : text.isJsonObject() ? text.getAsJsonObject().get("*").getAsString() : text.getAsString();
            long revision = parse.has("revid") ? parse.get("revid").getAsLong() : 0;
            return new Page(parse.get("title").getAsString(), revision, wikitext);
        } catch (RuntimeException malformed) { return null; }
    }

    /** Exact identity only: infobox name or page title equals the model (normalized), or a bold/infobox model code matches. */
    private static boolean matches(String brand, String model, String code, Page page, Map<String, String> infobox) {
        String maker = clean(infobox.getOrDefault("maker", infobox.getOrDefault("manufacturer", "")));
        if (!brand.isEmpty() && !maker.isEmpty() && !GearNames.sameBrand(maker, brand)) return false;
        String name = clean(infobox.getOrDefault("camera_name", infobox.getOrDefault("name", "")));
        if (GearNames.sameModel(brand, page.title(), model) || (!name.isEmpty() && GearNames.sameModel(brand, name, model))) return true;
        var codes = new ArrayList<String>();
        Matcher bold = Pattern.compile("'''([^']{2,60})'''").matcher(page.wikitext());
        while (bold.find()) codes.add(GearNames.alnum(bold.group(1)));
        for (String wanted : List.of(code, model)) {
            String key = GearNames.alnum(wanted);
            if (key.length() >= 4 && codes.contains(key)) return true;
        }
        return false;
    }

    /** Parameters of the first camera or lens infobox in the lead section; null when there is none. */
    static Map<String, String> infobox(String wikitext, EquipmentType type) {
        var names = type == EquipmentType.CAMERA ? List.of("infobox camera", "infobox digital camera")
            : List.of("infobox photographic lens", "infobox lens", "infobox camera lens");
        String lower = wikitext.toLowerCase(Locale.ROOT);
        int start = -1;
        for (String name : names) {
            Matcher found = Pattern.compile("\\{\\{\\s*" + Pattern.quote(name) + "\\s*[|}\\n]").matcher(lower);
            if (found.find() && (start < 0 || found.start() < start)) start = found.start();
        }
        if (start < 0) return null;
        int depth = 0, end = -1;
        for (int i = start; i < wikitext.length() - 1; i++) {
            if (wikitext.startsWith("{{", i)) { depth++; i++; }
            else if (wikitext.startsWith("}}", i)) { depth--; i++; if (depth == 0) { end = i - 1; break; } }
        }
        if (end < 0) return null;
        String body = wikitext.substring(start + 2, end);
        var parameters = new LinkedHashMap<String, String>();
        int templates = 0, links = 0, from = 0;
        var parts = new ArrayList<String>();
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("{{", i)) { templates++; i++; }
            else if (body.startsWith("}}", i)) { templates--; i++; }
            else if (body.startsWith("[[", i)) { links++; i++; }
            else if (body.startsWith("]]", i)) { links--; i++; }
            else if (body.charAt(i) == '|' && templates == 0 && links == 0) { parts.add(body.substring(from, i)); from = i + 1; }
        }
        parts.add(body.substring(from));
        for (String part : parts.subList(1, parts.size())) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            parameters.put(part.substring(0, equals).strip().toLowerCase(Locale.ROOT), part.substring(equals + 1).strip());
        }
        return parameters;
    }

    /** Wiki markup to plain text: comments, references and unknown templates removed; links and conversions kept as text. */
    static String clean(String value) {
        if (value == null) return "";
        String text = value.replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?is)<ref[^>]*/>", " ").replaceAll("(?is)<ref[^>]*>.*?</ref>", " ");
        text = text.replaceAll("(?i)\\{\\{\\s*convert\\s*\\|\\s*([0-9.,]+)\\s*\\|\\s*([^|}]+)[^{}]*}}", "$1 $2")
            .replaceAll("(?i)\\{\\{\\s*f/\\s*}}", "f/").replaceAll("\\{\\{\\s*!\\s*}}", "|").replaceAll("(?i)\\{\\{\\s*nbsp\\s*}}", " ");
        for (int i = 0; i < 5 && text.contains("{{"); i++) text = text.replaceAll("\\{\\{[^{}]*}}", " ");
        text = text.replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*)]]", "$1").replaceAll("\\[https?://\\S+\\s+([^\\]]*)]", "$1")
            .replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&times;", "×").replace("&ndash;", "–").replace("&amp;", "&")
            .replace("'''", "").replace("''", "");
        return text.replaceAll("\\s+", " ").strip();
    }

    static Map<String, String> cameraValues(Map<String, String> infobox) {
        var values = new LinkedHashMap<String, String>();
        mount(values, clean(infobox.getOrDefault("lens_mount", infobox.getOrDefault("mount", ""))));
        Matcher sensor = Pattern.compile(NUMBER + "\\s*(?:mm)?\\s*[×xX*]\\s*" + NUMBER + "\\s*mm").matcher(clean(infobox.getOrDefault("sensor_size", "")));
        if (sensor.find()) {
            double a = Double.parseDouble(sensor.group(1)), b = Double.parseDouble(sensor.group(2));
            if (a >= 1 && a <= 100 && b >= 1 && b <= 100) { values.put("sensorWidthMm", format(Math.max(a, b))); values.put("sensorHeightMm", format(Math.min(a, b))); }
        }
        String resolution = clean(infobox.getOrDefault("res", "") + " " + infobox.getOrDefault("maxres", "") + " " + infobox.getOrDefault("sensor", ""));
        Matcher megapixels = Pattern.compile("(?i)" + NUMBER + "\\s*(?:effective\\s+)?(?:mega\\s*pixels?|MP\\b)").matcher(resolution);
        if (megapixels.find() && within(megapixels.group(1), 0.1, 500)) values.put("resolutionMP", format(Double.parseDouble(megapixels.group(1))));
        Matcher fps = Pattern.compile("(?i)" + NUMBER + "\\s*(?:fps|frames?\\s*(?:per|/)\\s*s(?:ec(?:ond)?)?)").matcher(clean(infobox.getOrDefault("cont", "")));
        double burst = 0;
        while (fps.find()) if (within(fps.group(1), 0.1, 200)) burst = Math.max(burst, Double.parseDouble(fps.group(1)));
        if (burst > 0) values.put("burstFps", format(burst));
        Matcher battery = Pattern.compile("\\b([A-Z]{1,4}-[A-Z]{0,4}[0-9]{1,4}[A-Za-z]{0,3})\\b").matcher(clean(infobox.getOrDefault("battery", "")));
        var batteries = new TreeSet<String>();
        while (battery.find()) batteries.add(battery.group(1));
        if (!batteries.isEmpty()) values.put("batteryTypes", String.join(",", batteries));
        var formats = storage(clean(infobox.getOrDefault("recording_medium", "") + " " + infobox.getOrDefault("storage", "")));
        if (!formats.isEmpty()) values.put("storageFormats", String.join(",", formats));
        weight(values, clean(infobox.getOrDefault("weight", "")));
        return values;
    }

    static Map<String, String> lensValues(Map<String, String> infobox) {
        var values = new LinkedHashMap<String, String>();
        mount(values, clean(infobox.getOrDefault("mount", "")));
        String focal = clean(infobox.getOrDefault("flength", infobox.getOrDefault("focal_length", "")));
        Matcher zoom = Pattern.compile(NUMBER + "\\s*(?:mm)?\\s*(?:[-–—]|to)\\s*" + NUMBER + "\\s*mm").matcher(focal);
        Matcher prime = Pattern.compile(NUMBER + "\\s*mm").matcher(focal);
        boolean isZoom = false;
        if (zoom.find() && within(zoom.group(1), 1, 3000) && within(zoom.group(2), 1, 3000)
                && Double.parseDouble(zoom.group(1)) < Double.parseDouble(zoom.group(2))) {
            values.put("minFocalMm", format(Double.parseDouble(zoom.group(1)))); values.put("maxFocalMm", format(Double.parseDouble(zoom.group(2)))); isZoom = true;
        } else if (prime.find() && within(prime.group(1), 1, 3000)) {
            values.put("minFocalMm", format(Double.parseDouble(prime.group(1)))); values.put("maxFocalMm", format(Double.parseDouble(prime.group(1))));
        }
        Matcher apertures = Pattern.compile(NUMBER).matcher(clean(infobox.getOrDefault("aperture", "")));
        var numbers = new ArrayList<Double>();
        while (apertures.find() && numbers.size() < 3) numbers.add(Double.parseDouble(apertures.group(1)));
        if (!numbers.isEmpty() && numbers.getFirst() >= 0.7 && numbers.getFirst() <= 32) {
            values.put("maxAperture", format(numbers.getFirst()));
            // "f/3.5-5.6": a second wide-open value below f/11 is the long-end maximum aperture of a variable zoom.
            if (isZoom && numbers.size() > 1 && numbers.get(1) > numbers.getFirst() && numbers.get(1) < 11)
                values.put("maxApertureAtMaxFocal", format(numbers.get(1)));
        }
        String filter = clean(infobox.getOrDefault("filter", ""));
        Matcher thread = Pattern.compile(NUMBER + "\\s*mm").matcher(filter);
        if (!filter.toLowerCase(Locale.ROOT).matches(".*(none|rear|gel|drop-in|n/a).*") && thread.find() && within(thread.group(1), 10, 200))
            values.put("filterDiameterMm", format(Double.parseDouble(thread.group(1))));
        String stabilization = clean(infobox.getOrDefault("feat-is", "")).toLowerCase(Locale.ROOT);
        if (Set.of("y", "yes", "true").contains(stabilization)) values.put("stabilization", "true");
        if (Set.of("n", "no", "false").contains(stabilization)) values.put("stabilization", "false");
        String drive = clean(infobox.getOrDefault("focusdrive", "")).toLowerCase(Locale.ROOT);
        if (!drive.isEmpty()) values.put("autofocus", drive.contains("manual") || drive.equals("none") ? "false" : "true");
        weight(values, clean(infobox.getOrDefault("weight", "")));
        return values;
    }

    private static void mount(Map<String, String> values, String text) {
        String label = text.replaceAll("\\(.*?\\)", " ").split("[,;/]| and | or ")[0].replaceAll("\\s+", " ").strip();
        if (!label.isEmpty() && label.length() <= 60 && !label.equalsIgnoreCase("interchangeable") && !label.equalsIgnoreCase("fixed"))
            values.put("mount", label);
    }
    private static void weight(Map<String, String> values, String text) {
        Matcher grams = Pattern.compile("(?i)" + NUMBER + "\\s*(kg|g)\\b").matcher(text.replace(",", ""));
        if (!grams.find()) return;
        double value = Double.parseDouble(grams.group(1)) * (grams.group(2).equalsIgnoreCase("kg") ? 1000 : 1);
        if (value >= 1 && value <= 20_000) values.put("weightGrams", format(value));
    }
    /** Card formats named in the text, in a fixed vocabulary of format names (not products). */
    private static List<String> storage(String text) {
        var found = new TreeSet<String>();
        Matcher cfexpress = Pattern.compile("(?i)CF\\s*express(?:\\s*\\(?Type\\s*([AB])\\)?)?").matcher(text);
        while (cfexpress.find()) found.add(cfexpress.group(1) == null ? "CFexpress" : "CFexpress Type " + cfexpress.group(1).toUpperCase(Locale.ROOT));
        String rest = cfexpress.replaceAll(" ");
        Matcher sd = Pattern.compile("(?i)\\b(micro\\s*)?SD(HC|XC|UC)?\\b").matcher(rest);
        while (sd.find()) found.add((sd.group(1) == null ? "SD" : "microSD") + (sd.group(2) == null ? "" : sd.group(2).toUpperCase(Locale.ROOT)));
        if (Pattern.compile("(?i)\\bXQD\\b").matcher(rest).find()) found.add("XQD");
        if (Pattern.compile("(?i)Compact\\s*Flash|\\bCF\\b").matcher(rest).find()) found.add("CompactFlash");
        if (Pattern.compile("(?i)Memory\\s*Stick").matcher(rest).find()) found.add("Memory Stick");
        return List.copyOf(found);
    }

    // ---------------------------------------------------------------- Lensfun

    private Result<Optional<SpecificationEvidence>> lensfun(EquipmentType type, String brand, String model, String code) {
        String slug = String.join("-", GearNames.tokens(brand));
        if (slug.isEmpty()) return Result.success(Optional.empty());
        var files = new ArrayList<>(List.of("mil-" + slug, "slr-" + slug));
        if (type == EquipmentType.CAMERA) files.add("compact-" + slug);
        files.add(slug);
        files.add("rf-" + slug);
        for (String file : files) {
            var fetched = lensfunFile(file + ".xml");
            if (!fetched.isSuccess()) return Result.failure(fetched);
            var body = fetched.value().orElseThrow();
            if (body.isEmpty()) continue;
            var document = xml(body.get());
            if (document == null) return Result.failureKey("import.error.catalogResponse", LENSFUN);
            var nodes = document.getElementsByTagName(type == EquipmentType.CAMERA ? "camera" : "lens");
            for (int i = 0; i < nodes.getLength(); i++) {
                var element = (Element) nodes.item(i);
                String maker = first(element, "maker");
                if (!GearNames.sameBrand(maker, brand)) continue;
                var names = all(element, "model");
                boolean matched = names.stream().anyMatch(name -> GearNames.sameModel(brand, name, model)
                    || (!code.isEmpty() && GearNames.alnum(name).equals(GearNames.alnum(code))) || GearNames.alnum(name).equals(GearNames.alnum(model)));
                if (!matched) continue;
                var mounts = all(element, "mount");
                var values = new LinkedHashMap<String, String>();
                // A record listing several mounts does not state which one this unit has.
                if (mounts.size() == 1) values.put("mount", mounts.getFirst());
                String english = englishModel(element);
                return Result.success(Optional.of(new SpecificationEvidence(LENSFUN, lensfun.resolve(file + ".xml"), type, maker,
                    english.isEmpty() ? names.getFirst() : english, values, clock.instant())));
            }
        }
        return Result.success(Optional.empty());
    }

    private Result<Optional<String>> lensfunFile(String file) {
        var cached = lensfunFiles.get(file);
        if (cached != null && cached.fetched().plus(CACHE).isAfter(clock.instant())) return Result.success(cached.body());
        var fetched = fetch(lensfun.resolve(file), LENSFUN);
        if (fetched.isSuccess()) lensfunFiles.put(file, new Cached(clock.instant(), fetched.value().orElseThrow()));
        return fetched;
    }

    private static org.w3c.dom.Document xml(String body) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false); factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception malformed) { return null; }
    }
    private static String first(Element element, String tag) { var values = all(element, tag); return values.isEmpty() ? "" : values.getFirst(); }
    private static List<String> all(Element element, String tag) {
        var result = new ArrayList<String>();
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof Element child && child.getTagName().equals(tag) && !child.getTextContent().isBlank())
                result.add(child.getTextContent().strip());
        return result;
    }
    private static String englishModel(Element element) {
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
            if (children.item(i) instanceof Element child && child.getTagName().equals("model") && "en".equals(child.getAttribute("lang")))
                return child.getTextContent().strip();
        return "";
    }

    // ---------------------------------------------------------------- transport

    private URI api(String... pairs) {
        var query = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (!query.isEmpty()) query.append('&');
            query.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        String base = endpoint.toString();
        return URI.create(base + (base.contains("?") ? "&" : "?") + query);
    }

    /** GET with timeout and identifying agent: 200 = body, 404 = empty, anything else an explicit failure. */
    private Result<Optional<String>> fetch(URI uri, String source) {
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, application/xml, text/xml;q=0.9, */*;q=0.5").GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) return Result.success(Optional.empty());
            if (response.statusCode() != 200) return Result.failureKey("import.error.catalogHttp", source, response.statusCode());
            byte[] body = response.body();
            if (body == null || body.length > MAX_BODY) return Result.failureKey("import.error.catalogResponse", source);
            return Result.success(Optional.of(new String(body, StandardCharsets.UTF_8)));
        } catch (HttpTimeoutException timedOut) {
            return Result.failureKey("import.error.catalogTimeout", source);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failureKey("import.error.catalogNetwork", source);
        } catch (IOException | SecurityException | IllegalArgumentException unreachable) {
            return Result.failureKey("import.error.catalogNetwork", source);
        }
    }

    private static boolean within(String number, double min, double max) {
        double value = Double.parseDouble(number);
        return value >= min && value <= max;
    }
    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0);
    }
}
