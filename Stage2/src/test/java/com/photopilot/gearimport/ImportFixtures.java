package com.photopilot.gearimport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Synthetic F04 fixtures: generated images (no real personal data), model replies and an offline catalogue server. */
public final class ImportFixtures {
    private ImportFixtures() { }
    /** Area standing in for personal information (pure red) in every generated image. */
    public static final java.awt.Rectangle PERSONAL = new java.awt.Rectangle(10, 10, 60, 20);
    public static final int RED = 0xFFFF0000;

    public static BufferedImage image(int w, int h) {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            g.setColor(Color.DARK_GRAY); g.fillRect(w / 4, h / 3, w / 2, h / 3);
            g.setColor(new Color(RED, true)); g.fillRect(PERSONAL.x, PERSONAL.y, PERSONAL.width, PERSONAL.height);
        } finally { g.dispose(); }
        return image;
    }
    public static Path png(Path directory, String name, int w, int h) throws Exception {
        Path file = directory.resolve(name);
        ImageIO.write(image(w, h), "png", file.toFile());
        return file;
    }
    public static Path jpeg(Path directory, String name, int w, int h) throws Exception {
        Path file = directory.resolve(name);
        ImageIO.write(image(w, h), "jpeg", file.toFile());
        return file;
    }
    /** Adds a PNG tEXt metadata chunk (e.g. an owner name) right after IHDR. */
    public static byte[] withTextChunk(byte[] png, String keyword, String text) {
        byte[] data = (keyword + "\0" + text).getBytes(StandardCharsets.ISO_8859_1);
        var chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length); chunk.put("tEXt".getBytes(StandardCharsets.US_ASCII)); chunk.put(data);
        var crc = new CRC32(); crc.update("tEXt".getBytes(StandardCharsets.US_ASCII)); crc.update(data);
        chunk.putInt((int) crc.getValue());
        int insert = 8 + 25; // signature + IHDR (length, type, 13 data bytes, CRC)
        var out = new ByteArrayOutputStream();
        out.write(png, 0, insert); out.writeBytes(chunk.array()); out.write(png, insert, png.length - insert);
        return out.toByteArray();
    }

    // ------------------------------------------------------------- model replies (structured output text)

    public static String candidate(String category, String brand, String model, String code, String visible) {
        return """
            {"category":"%s","brand":"%s","model":"%s","modelCode":%s,"visualClues":["model name printed on the body"],
             "distinguishingDetails":null,"visibleSpecifications":%s}""".formatted(category, brand, model,
            code == null ? "null" : "\"" + code + "\"", visible);
    }
    public static String visible(String mount, Double min, Double max, Double aperture, Double filter) {
        return "{\"mount\":%s,\"minFocalMm\":%s,\"maxFocalMm\":%s,\"maxAperture\":%s,\"maxApertureAtMaxFocal\":null,\"filterDiameterMm\":%s,\"autofocus\":null,\"stabilization\":null,\"resolutionMP\":null}"
            .formatted(mount == null ? "null" : "\"" + mount + "\"", min, max, aperture, filter);
    }
    public static String recognition(boolean ambiguous, String... candidates) {
        return "{\"readable\":true,\"imageKind\":\"equipment_photo\",\"visibleText\":[\"SONY\"],\"candidates\":[" + String.join(",", candidates)
            + "],\"ambiguous\":" + ambiguous + ",\"uncertainty\":" + (ambiguous ? "\"The version mark is not visible.\"" : "null") + "}";
    }
    public static final String CAMERA = recognition(false, candidate("CAMERA", "Sony", "α7 IV", "ILCE-7M4", visible(null, null, null, null, null)));
    public static final String LENS = recognition(false, candidate("LENS", "Sony", "FE 24-70mm F2.8 GM", null, visible(null, 24.0, 70.0, 2.8, null)));
    public static final String AMBIGUOUS_LENS = recognition(true,
        candidate("LENS", "Sony", "FE 24-70mm F2.8 GM", null, visible(null, 24.0, 70.0, 2.8, null)),
        candidate("LENS", "Sony", "FE 24-70mm F2.8 GM II", null, visible(null, 24.0, 70.0, 2.8, null)));
    public static final String UNREADABLE = "{\"readable\":false,\"imageKind\":\"other\",\"visibleText\":[],\"candidates\":[],\"ambiguous\":false,\"uncertainty\":\"too blurry\"}";
    public static final String OTHER_ONLY = recognition(false, candidate("OTHER", "SanDisk", "Extreme PRO 128GB", null, visible(null, null, null, null, null)));

    // ------------------------------------------------------------- catalogue fixtures (abbreviated infobox facts)

    public static final String A7IV_WIKITEXT = """
        {{Short description|Mirrorless camera}}
        {{Infobox camera
        | camera_name = Sony α7 IV
        | maker = Sony
        | sensor_type = [[BSI-CMOS]]
        | sensor_size = 35.9&nbsp;× 23.9&nbsp;mm (Full frame type)
        | recording_medium = SD, SDHC, SDXC, CF Express Type A memory card
        | res = 7008&nbsp;× 4672 (33 megapixels)
        | lens_mount = [[Sony E-mount]]
        | cont = 10 frames per second
        | battery = NP-FZ100 Lithium-Ion rechargeable battery
        | weight = 658g<ref>{{cite web |title=Specifications}}</ref>
        }}
        The '''Sony α7 IV''' (model '''ILCE-7M4''') is a full-frame mirrorless camera.
        """;
    public static final String GM_WIKITEXT = """
        {{Infobox photographic lens
        |name         = Sony FE 24-70mm F2.8 GM
        |maker        = Sony
        |mount        = [[Sony E-mount]]
        |focusdrive   = Piezoelectric
        |feat-is      = n
        |flength      = 24-70mm
        |aperture     = {{f/}}2.8 -22.0
        |weight       = {{convert|886|g|lb}}
        |filter       = 82mm
        }}
        The '''Sony FE 24-70mm F2.8 GM''' is a zoom lens.
        """;
    public static final String GM2_WIKITEXT = """
        {{Infobox photographic lens
        |name         = Sony FE 24-70mm F2.8 GM II
        |maker        = Sony
        |mount        = [[Sony E-mount]]
        |flength      = 24-70mm
        |aperture     = {{f/}}2.8 -22.0
        |filter       = 82mm
        }}
        The '''Sony FE 24-70mm F2.8 GM II''' is a zoom lens.
        """;
    public static final String SONY_LENSFUN = """
        <lensdatabase version="2">
            <camera>
                <maker>Sony</maker>
                <model>ILCE-7M4</model>
                <model lang="en">Alpha 7 IV</model>
                <mount>Sony E</mount>
                <cropfactor>1</cropfactor>
            </camera>
            <lens>
                <maker>Sony</maker>
                <model>FE 24-70mm f/2.8 GM</model>
                <mount>Sony E</mount>
                <cropfactor>1</cropfactor>
            </lens>
            <lens>
                <maker>Sony</maker>
                <model>FE 24-70mm f/2.8 GM II</model>
                <mount>Sony E</mount>
                <cropfactor>1</cropfactor>
            </lens>
        </lensdatabase>
        """;

    /** Loopback stand-in for the Wikipedia Action API and Lensfun data files; records request paths and agents. */
    public static final class FakeCatalog implements AutoCloseable {
        private final HttpServer server;
        public final Map<String, List<String>> searches = new ConcurrentHashMap<>();
        public final Map<String, String> pages = new ConcurrentHashMap<>();
        public final Map<String, String> files = new ConcurrentHashMap<>();
        public final List<String> requests = new CopyOnWriteArrayList<>();
        public final List<String> agents = new CopyOnWriteArrayList<>();
        public volatile int wikiStatus = 200, lensfunStatus = 200;

        public FakeCatalog() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/w/api.php", exchange -> {
                requests.add(exchange.getRequestURI().toString()); agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
                var query = query(exchange.getRequestURI().getRawQuery());
                String body;
                if (wikiStatus != 200) body = "{}";
                else if ("query".equals(query.get("action"))) {
                    var hits = new JsonArray();
                    for (String title : searches.getOrDefault(query.get("srsearch"), List.of())) { var hit = new JsonObject(); hit.addProperty("title", title); hits.add(hit); }
                    var inner = new JsonObject(); inner.add("search", hits);
                    var root = new JsonObject(); root.add("query", inner); body = root.toString();
                } else {
                    String text = pages.get(query.get("page"));
                    var root = new JsonObject();
                    if (text == null) { var error = new JsonObject(); error.addProperty("code", "missingtitle"); root.add("error", error); }
                    else { var parse = new JsonObject(); parse.addProperty("title", query.get("page")); parse.addProperty("revid", 123456); parse.addProperty("wikitext", text); root.add("parse", parse); }
                    body = root.toString();
                }
                send(exchange, wikiStatus, body);
            });
            server.createContext("/db/", exchange -> {
                requests.add(exchange.getRequestURI().toString()); agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
                String name = exchange.getRequestURI().getPath().substring("/db/".length());
                String body = files.get(name);
                send(exchange, lensfunStatus != 200 ? lensfunStatus : body == null ? 404 : 200, body == null ? "" : body);
            });
            server.start();
        }
        /** Standard data: the α7 IV and both 24-70 GM lenses. */
        public FakeCatalog standard() {
            searches.put("Sony α7 IV", List.of("Sony α7 IV", "List of Sony α cameras"));
            searches.put("Sony FE 24-70mm F2.8 GM", List.of("Sony FE 24-70mm F2.8 GM II", "Sony FE 24-70mm F2.8 GM"));
            searches.put("Sony FE 24-70mm F2.8 GM II", List.of("Sony FE 24-70mm F2.8 GM II"));
            pages.put("Sony α7 IV", A7IV_WIKITEXT);
            pages.put("Sony FE 24-70mm F2.8 GM", GM_WIKITEXT);
            pages.put("Sony FE 24-70mm F2.8 GM II", GM2_WIKITEXT);
            files.put("mil-sony.xml", SONY_LENSFUN);
            return this;
        }
        public String wiki() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/w/api.php"; }
        public String lensfun() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/db/"; }
        public Map<String, String> environment() { return Map.of("PHOTOPILOT_SPEC_WIKI_ENDPOINT", wiki(), "PHOTOPILOT_SPEC_LENSFUN_ENDPOINT", lensfun()); }
        private static Map<String, String> query(String raw) {
            var result = new HashMap<String, String>();
            if (raw == null) return result;
            for (String pair : raw.split("&")) {
                int split = pair.indexOf('=');
                if (split > 0) result.put(pair.substring(0, split), URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
            return result;
        }
        private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        }
        @Override public void close() { server.stop(0); }
    }

    /** Combined environment for a bootstrap that only reaches the given offline fakes. */
    public static Map<String, String> environment(Path root, com.photopilot.requirement.FakeGemini gemini, FakeCatalog catalog) {
        var environment = new HashMap<>(gemini.environment(root));
        environment.putAll(catalog.environment());
        return environment;
    }
    /** Decodes the inline image of a recorded Gemini request body. */
    public static BufferedImage sentImage(String requestBody) throws Exception {
        var parts = com.google.gson.JsonParser.parseString(requestBody).getAsJsonObject().getAsJsonArray("contents").get(0)
            .getAsJsonObject().getAsJsonArray("parts");
        for (var part : parts) if (part.getAsJsonObject().has("inlineData")) {
            byte[] bytes = Base64.getDecoder().decode(part.getAsJsonObject().getAsJsonObject("inlineData").get("data").getAsString());
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        }
        return null;
    }
    public static byte[] read(Path file) throws Exception { return Files.readAllBytes(file); }
}
