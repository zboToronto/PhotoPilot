import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Writes third-party/exiftool/MANIFEST.sha256 for the vendored official ExifTool Windows package: one line per file,
 * "sha256 size path", path relative to third-party/exiftool with forward slashes, sorted. The runtime uses it to find,
 * extract (from a JAR) and verify the bundle; tests use it to detect missing or altered files.
 *
 * Run from Stage2 after replacing the vendored package:  java tools/ExifToolManifest.java exiftool-13.62_64
 */
public final class ExifToolManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: java tools/ExifToolManifest.java <package folder name>");
        Path base = Path.of("third-party", "exiftool");
        Path root = base.resolve(args[0]);
        if (!Files.isRegularFile(root.resolve("exiftool_files").resolve("exiftool.pl")))
            throw new IllegalArgumentException("Not an ExifTool Windows package: " + root);
        List<String> lines = new ArrayList<>();
        lines.add("# PhotoPilot bundle manifest for the official ExifTool Windows 64-bit package (see NOTICE.md).");
        lines.add("# Format: sha256 size path (relative to third-party/exiftool). Regenerate with tools/ExifToolManifest.java.");
        lines.add("# package " + args[0]);
        List<String> entries = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                entries.add(hash(file) + " " + Files.size(file) + " " + base.relativize(file).toString().replace('\\', '/'));
        }
        entries.sort((a, b) -> a.substring(a.indexOf(' ', 65) + 1).compareTo(b.substring(b.indexOf(' ', 65) + 1)));
        lines.addAll(entries);
        Files.write(base.resolve("MANIFEST.sha256"), lines, StandardCharsets.UTF_8);
        System.out.println("Wrote " + entries.size() + " entries to " + base.resolve("MANIFEST.sha256"));
    }

    private static String hash(Path file) throws IOException, java.security.NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
