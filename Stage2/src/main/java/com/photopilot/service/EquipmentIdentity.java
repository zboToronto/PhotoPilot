package com.photopilot.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.photopilot.model.CameraModel;
import com.photopilot.model.GearNames;

/**
 * Deterministic identity of camera and lens model names that come from different sources (inventory records, plan
 * snapshots, EXIF Make/Model), shared by the F11 comparison, the F11 AI claim check and the F04 duplicate review
 * (Stage2OptRe1 PP-03).
 *
 * <ul>
 * <li>{@link Relation#SAME_MODEL}: the names are equal over normalized tokens once a leading manufacturer is ignored,
 * or the bundled camera catalog lists both as names of one model ("Sony Alpha 7 IV", "Sony α7 IV", "SONY ILCE-7M4").</li>
 * <li>{@link Relation#DIFFERENT_MODEL}: the catalog resolves the names to two different models, the two named camera
 * manufacturers differ, or (lenses) the focal lengths or maximum apertures written in both names differ.</li>
 * <li>{@link Relation#UNKNOWN}: anything else, including a missing name. There is no fuzzy matching: "A7 Mark IV" is
 * not merged with "α7 IV" because the catalog does not list that spelling.</li>
 * </ul>
 * A model name never identifies an individual camera body or lens: two photos with the same model may come from two
 * bodies, and EXIF serial numbers are not read by PhotoPilot.
 */
public final class EquipmentIdentity {
    private EquipmentIdentity() { }

    public enum Kind { CAMERA, LENS }
    public enum Relation { SAME_MODEL, DIFFERENT_MODEL, UNKNOWN }
    /** Why the relation was decided; MISSING means at least one name was blank. */
    public enum Basis { NAME, CATALOG, MANUFACTURER, SPECIFICATION, NONE, MISSING }

    /** The relation of two names with its basis and, for cameras, the catalog entries the names resolved to. */
    public record Comparison(Relation relation, Basis basis, Optional<CameraModel> first, Optional<CameraModel> second) {
        public Comparison { Objects.requireNonNull(relation); Objects.requireNonNull(basis); Objects.requireNonNull(first); Objects.requireNonNull(second); }
        public boolean same() { return relation == Relation.SAME_MODEL; }
        public boolean different() { return relation == Relation.DIFFERENT_MODEL; }
        /** True when the names are the same model only through the catalog (spelled differently). */
        public boolean alias() { return relation == Relation.SAME_MODEL && basis == Basis.CATALOG; }
    }

    public static Comparison compare(Kind kind, String first, String second) {
        return kind == Kind.CAMERA ? cameras(first, second) : lenses(first, second);
    }

    /** Two camera names, e.g. a plan snapshot "Sony Alpha 7 IV" and an EXIF "SONY ILCE-7M4" (same model). */
    public static Comparison cameras(String first, String second) {
        if (blank(first) || blank(second)) return new Comparison(Relation.UNKNOWN, Basis.MISSING, Optional.empty(), Optional.empty());
        var a = camera(first); var b = camera(second);
        var makerA = manufacturer(first); var makerB = manufacturer(second);
        if (makerA.isPresent() && makerB.isPresent() && !makerA.equals(makerB)) return new Comparison(Relation.DIFFERENT_MODEL, Basis.MANUFACTURER, a, b);
        if (sameTokens(first, second)) return new Comparison(Relation.SAME_MODEL, Basis.NAME, a, b);
        if (a.isPresent() && b.isPresent())
            return new Comparison(a.get().id().equals(b.get().id()) && a.get().brand() == b.get().brand() ? Relation.SAME_MODEL : Relation.DIFFERENT_MODEL, Basis.CATALOG, a, b);
        return new Comparison(Relation.UNKNOWN, Basis.NONE, a, b);
    }

    /**
     * Two lens names. Lens makers are not compared (co-branded lenses such as Sony/ZEISS are named by either maker), so
     * only equal names or differing written focal lengths/apertures decide; there is no lens catalog.
     */
    public static Comparison lenses(String first, String second) {
        if (blank(first) || blank(second)) return new Comparison(Relation.UNKNOWN, Basis.MISSING, Optional.empty(), Optional.empty());
        if (sameTokens(first, second)) return new Comparison(Relation.SAME_MODEL, Basis.NAME, Optional.empty(), Optional.empty());
        var a = optics(first); var b = optics(second);
        if (a.focal().isPresent() && b.focal().isPresent()) {
            if (!a.focal().equals(b.focal())) return new Comparison(Relation.DIFFERENT_MODEL, Basis.SPECIFICATION, Optional.empty(), Optional.empty());
            if (a.aperture().isPresent() && b.aperture().isPresent() && !a.aperture().equals(b.aperture()))
                return new Comparison(Relation.DIFFERENT_MODEL, Basis.SPECIFICATION, Optional.empty(), Optional.empty());
        }
        return new Comparison(Relation.UNKNOWN, Basis.NONE, Optional.empty(), Optional.empty());
    }

    /**
     * The catalog model a camera name denotes: brand from a leading manufacturer word, otherwise the one catalog brand
     * under which the name resolves (a name that resolves under both is ambiguous and not resolved).
     */
    public static Optional<CameraModel> camera(String name) {
        if (blank(name)) return Optional.empty();
        var catalog = CameraModelCatalog.bundled();
        var maker = manufacturer(name);
        if (maker.isPresent()) {
            return Arrays.stream(CameraModel.Brand.values()).filter(brand -> brand.label().toLowerCase(Locale.ROOT).equals(maker.get()))
                .findFirst().flatMap(brand -> catalog.resolve(brand, stripped(name)));
        }
        var found = Arrays.stream(CameraModel.Brand.values()).map(brand -> catalog.resolve(brand, name)).flatMap(Optional::stream).toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /** Catalog label of the model a camera name denotes ("α7 IV (ILCE-7M4)"), for comparisons and evidence. */
    public static Optional<String> catalogLabel(String name) {
        return camera(name).map(model -> model.brand().label() + " " + model.label());
    }

    /** Normalized manufacturer named by the leading word of an equipment name or EXIF Make, when it is a known one. */
    public static Optional<String> manufacturer(String name) {
        var tokens = GearNames.tokens(name);
        if (tokens.isEmpty()) return Optional.empty();
        return Optional.ofNullable(MAKERS.get(tokens.getFirst()));
    }

    /**
     * Token patterns that denote a name in free text: the name without its leading manufacturer and, for a catalogued
     * camera, every catalog spelling ("ILCE-7M4", "α7 IV", "A7M4", "A74").
     */
    public static List<List<String>> spellings(Kind kind, String name) {
        var result = new LinkedHashSet<List<String>>();
        var own = strippedTokens(name);
        if (!own.isEmpty()) result.add(own);
        if (kind == Kind.CAMERA) camera(name).ifPresent(model -> model.aliases().forEach(alias -> {
            var tokens = GearNames.tokens(alias);
            if (tokens.size() > 1 || tokens.stream().anyMatch(token -> token.length() > 2)) result.add(tokens);
        }));
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Leading words of EXIF Make values and product names; groups of one company map to one key. */
    private static final Map<String, String> MAKERS = Map.ofEntries(
        Map.entry("sony", "sony"), Map.entry("nikon", "nikon"), Map.entry("canon", "canon"), Map.entry("fujifilm", "fujifilm"),
        Map.entry("fuji", "fujifilm"), Map.entry("panasonic", "panasonic"), Map.entry("lumix", "panasonic"), Map.entry("olympus", "om"),
        Map.entry("om", "om"), Map.entry("leica", "leica"), Map.entry("pentax", "pentax"), Map.entry("ricoh", "pentax"),
        Map.entry("hasselblad", "hasselblad"), Map.entry("sigma", "sigma"), Map.entry("dji", "dji"), Map.entry("gopro", "gopro"),
        Map.entry("apple", "apple"), Map.entry("samsung", "samsung"), Map.entry("google", "google"));
    /** Company-name words that may follow the manufacturer in an EXIF Make ("NIKON CORPORATION", "OM Digital Solutions"). */
    private static final Set<String> COMPANY = Set.of("corporation", "corp", "imaging", "camera", "ag", "co", "ltd", "company", "inc", "gmbh",
        "solutions", "digital", "system");
    /** Regex for a leading manufacturer phrase in free text ("Sony ", "NIKON CORPORATION "), matched case-insensitively. */
    static final String MAKER_PREFIX = "(?:" + String.join("|", new TreeSet<>(MAKERS.keySet())) + ")(?:[\\s,]+(?:"
        + String.join("|", new TreeSet<>(COMPANY)) + ")\\.?)*[\\s\\-]+";

    private static boolean blank(String text) { return text == null || GearNames.tokens(text).isEmpty(); }
    private static boolean sameTokens(String first, String second) {
        var a = strippedTokens(first); var b = strippedTokens(second);
        if (a.isEmpty() || b.isEmpty()) return false;
        var x = new ArrayList<>(a); var y = new ArrayList<>(b); x.sort(null); y.sort(null);
        return x.equals(y);
    }
    /** Tokens without a leading manufacturer, its company words and a repeated manufacturer word. */
    static List<String> strippedTokens(String name) {
        var tokens = new ArrayList<>(GearNames.tokens(name));
        if (tokens.size() > 1 && MAKERS.containsKey(tokens.getFirst())) {
            String maker = MAKERS.get(tokens.removeFirst());
            while (tokens.size() > 1 && COMPANY.contains(tokens.getFirst())) tokens.removeFirst();
            if (tokens.size() > 1 && maker.equals(MAKERS.get(tokens.getFirst()))) tokens.removeFirst();
        }
        return tokens;
    }
    private static String stripped(String name) { return String.join(" ", strippedTokens(name)); }

    private record Optics(Optional<String> focal, Optional<String> aperture) { }
    private static final Pattern FOCAL = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)(?:\\s*-\\s*(\\d+(?:\\.\\d+)?))?\\s*mm\\b");
    private static final Pattern APERTURE = Pattern.compile("(?i)\\bf\\s*/?\\s*(\\d+(?:\\.\\d+)?)|\\b1\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    /** Focal length (range) and maximum aperture written in a lens name ("FE 24-70mm F2.8 GM II" -> 24-70, 2.8). */
    private static Optics optics(String name) {
        Matcher focal = FOCAL.matcher(name);
        Optional<String> range = focal.find() ? Optional.of(number(focal.group(1)) + (focal.group(2) == null ? "" : "-" + number(focal.group(2)))) : Optional.empty();
        Matcher aperture = APERTURE.matcher(name);
        Optional<String> f = aperture.find() ? Optional.of(number(aperture.group(1) != null ? aperture.group(1) : aperture.group(2))) : Optional.empty();
        return new Optics(range, f);
    }
    private static String number(String text) { return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString(); }
}
