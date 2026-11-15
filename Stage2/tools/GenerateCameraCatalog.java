import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regenerates src/main/resources/catalog/exiftool-camera-models.json from a saved copy of the official ExifTool-tested
 * camera list (https://exiftool.org/models.html). Only the Sony and Nikon sections are kept, exactly as listed; which
 * entries are photographic cameras, their series and their shutter-count support are decided in CameraModelCatalog.
 *
 * Run from Stage2:  java tools/GenerateCameraCatalog.java <saved models.html> <retrieved yyyy-mm-dd> <ExifTool version>
 */
public final class GenerateCameraCatalog {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <models.html> <retrieved yyyy-mm-dd> <exiftool version>");
        String html = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Matcher total = Pattern.compile("from the following (\\d+) camera models").matcher(html);
        if (!total.find()) throw new IllegalStateException("Not the ExifTool-tested camera models page");
        Map<String, List<String>> brands = new LinkedHashMap<>();
        for (String brand : List.of("Sony", "Nikon")) brands.put(brand, section(html, brand));
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"source\": \"https://exiftool.org/models.html\",\n");
        json.append("  \"sourceTitle\": \"ExifTool-tested Camera Models\",\n");
        json.append("  \"retrieved\": \"").append(args[1]).append("\",\n");
        json.append("  \"exiftoolVersion\": \"").append(args[2]).append("\",\n");
        json.append("  \"sourceModelCount\": ").append(total.group(1)).append(",\n");
        json.append("  \"brands\": {\n");
        int b = 0;
        for (var entry : brands.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": [\n");
            List<String> names = entry.getValue();
            for (int i = 0; i < names.size(); i++)
                json.append("      \"").append(names.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append(i + 1 < names.size() ? "\",\n" : "\"\n");
            json.append(++b < brands.size() ? "    ],\n" : "    ]\n");
        }
        json.append("  }\n}\n");
        Path out = Path.of("src", "main", "resources", "catalog", "exiftool-camera-models.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        brands.forEach((brand, names) -> System.out.println(brand + ": " + names.size() + " listed entries"));
    }

    /** The names listed under one brand anchor, in page order, with entities decoded and whitespace collapsed. */
    private static List<String> section(String html, String brand) {
        Matcher m = Pattern.compile("<a name='" + brand + "' class=th>" + brand + "</a>\\s*</td></tr>(.*?)(?=<tr><td colspan=4>|</table>)", Pattern.DOTALL).matcher(html);
        if (!m.find()) throw new IllegalStateException("Brand section not found: " + brand);
        List<String> names = new ArrayList<>();
        for (String cell : m.group(1).split("<br>|</td><td>")) {
            String name = cell.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }
}
