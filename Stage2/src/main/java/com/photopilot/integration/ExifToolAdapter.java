package com.photopilot.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.photopilot.model.CameraMetadata;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shutter-count metadata through the bundled ExifTool: read-only JSON output ({@code -json}, no write option is ever
 * passed), all duplicates ({@code -a}) qualified by their metadata path ({@code -G5} with the {@code SavePath} API
 * option) so counters from different MakerNotes tables stay apart, raw numeric values ({@code -n}), and UTF-8 file names
 * ({@code -charset filename=utf8}) so any Windows path works. Only the tags below are requested: no GPS position, serial
 * number or owner name is read out, and the file path in ExifTool's output is discarded. {@link #reviewJson} reads the
 * capture settings of RAW photos for the post-shoot review through the same process (one request at a time, same timeout).
 */
public final class ExifToolAdapter implements CameraMetadataReader, AutoCloseable {
    /** Camera identity, capture date, file type and every counter or shutter-type field the rules consider. */
    static final List<String> TAGS = List.of("Make", "Model", "SonyModelID", "MakerNoteVersion", "DateTimeOriginal", "CreateDate",
        "FileType", "FileTypeExtension", "MIMEType", "ShutterCount", "ShutterCount2", "ShutterCount3", "ImageCount",
        "MechanicalShutterCount", "Shutter", "ShutterType", "ShutterMode", "Error", "Warning");
    /**
     * Post-shoot review (RAW photos): the capture settings ExifLibraryAdapter maps for statistics, plus FileType,
     * DNGVersion and Make to confirm the RAW format. No GPS, serial number, owner or maker-note counter is requested.
     */
    static final List<String> REVIEW_TAGS = List.of("FileType", "DNGVersion", "Make", "Model", "LensMake", "LensModel",
        "FocalLength", "ExposureTime", "FNumber", "ISO", "ExposureCompensation", "DateTimeOriginal", "OffsetTimeOriginal", "Error", "Warning");
    private final ExifToolProcess process;

    public ExifToolAdapter(ExifToolProcess process) { this.process = process; }

    /** The bundled ExifTool's version (starts the process if needed). */
    public String version() throws ExifToolException { return process.execute(List.of("-ver")).stdout().strip(); }

    @Override public CameraMetadata read(Path file) throws ExifToolException {
        List<String> arguments = new ArrayList<>(List.of("-json", "-a", "-G5", "-n", "-api", "SavePath=1", "-charset", "filename=utf8"));
        for (String tag : TAGS) arguments.add("-" + tag);
        arguments.add(argument(file));
        var response = process.execute(arguments);
        return parse(response.stdout(), response.stderr());
    }

    /**
     * ExifTool's {@code -json -n} answer for the {@link #REVIEW_TAGS} of one file: one value per tag chosen by ExifTool's
     * own priority (no {@code -a}/{@code -G}), raw numbers, read-only. Only the metadata is parsed by ExifTool; no pixel
     * data is decoded and nothing is loaded into this process beyond the JSON text.
     */
    public String reviewJson(Path file) throws ExifToolException {
        List<String> arguments = new ArrayList<>(List.of("-json", "-n", "-charset", "filename=utf8"));
        for (String tag : REVIEW_TAGS) arguments.add("-" + tag);
        arguments.add(argument(file));
        return process.execute(arguments).stdout();
    }

    /** Absolute and normalized: never starts with "-" or "#", so ExifTool cannot read it as an option or a comment. */
    private static String argument(Path file) { return file.toAbsolutePath().normalize().toString(); }

    /** Parses ExifTool's JSON for one file; an empty answer carries the error ExifTool printed (without the path). */
    static CameraMetadata parse(String json, List<String> stderr) {
        List<CameraMetadata.Field> fields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Optional<String> error = Optional.empty();
        if (!json.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) {
                    for (var entry : root.getAsJsonArray().get(0).getAsJsonObject().entrySet()) {
                        String key = entry.getKey();
                        if (key.equals("SourceFile")) continue;
                        int colon = key.lastIndexOf(':');
                        String directory = colon < 0 ? "" : key.substring(0, colon), tag = key.substring(colon + 1);
                        JsonElement value = entry.getValue();
                        String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
                        if (tag.equals("Error")) error = Optional.of(text);
                        else if (tag.equals("Warning")) warnings.add(text);
                        else fields.add(new CameraMetadata.Field(directory, tag, text));
                    }
                }
            } catch (JsonParseException | IllegalStateException malformed) {
                error = Optional.of("Unreadable ExifTool output");
            }
        }
        if (fields.isEmpty() && error.isEmpty()) error = stderr.stream().map(String::strip).filter(line -> line.startsWith("Error:"))
            .map(line -> line.substring(6).strip()).map(line -> line.contains(" - ") ? line.substring(0, line.indexOf(" - ")) : line).findFirst()
            .or(() -> Optional.of("No metadata returned"));
        return new CameraMetadata(fields, error, warnings);
    }

    @Override public void close() { process.close(); }
}
