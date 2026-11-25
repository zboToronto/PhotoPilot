package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.CameraMetadataReader;
import com.photopilot.integration.ExifToolException;
import com.photopilot.model.CameraMetadata;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CameraModel.Brand;
import com.photopilot.model.ShutterCountResult;
import com.photopilot.model.ShutterCountResult.ModelCheck;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shutter count checker shared by GUI and CLI: validates the selected file, reads its metadata with the bundled
 * ExifTool (read-only, local), applies {@link ShutterCountRules}, and compares the detected camera with the selected
 * model. Photos are never modified, copied, uploaded or stored; nothing about them is persisted.
 */
public final class ShutterCountService {
    /** File name extensions the checker accepts. */
    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "arw", "nef", "dng");
    /** Formats ExifTool must report for the file to be checked. */
    private static final Map<String, Set<String>> FORMATS = Map.of("JPEG", Set.of("jpg", "jpeg"), "ARW", Set.of("arw"), "NEF", Set.of("nef"), "DNG", Set.of("dng"));
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private final CameraMetadataReader reader;
    private final CameraModelCatalog catalog;

    public ShutterCountService(CameraMetadataReader reader, CameraModelCatalog catalog) {
        this.reader = Objects.requireNonNull(reader);
        this.catalog = Objects.requireNonNull(catalog);
    }

    public CameraModelCatalog catalog() { return catalog; }

    public Result<ShutterCountResult> check(Path file) {
        if (file == null) return Result.failureKey("shutter.error.noFile");
        Path path;
        try { path = file.toAbsolutePath().normalize(); } catch (InvalidPathException | java.io.IOError invalid) { return Result.failureKey("shutter.error.badPath"); }
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (path.toString().indexOf('\n') >= 0 || path.toString().indexOf('\r') >= 0) return Result.failureKey("shutter.error.badPath");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) return Result.failureKey("shutter.error.unsupportedFormat", name);
        if (!Files.exists(path)) return Result.failureKey("shutter.error.notFound", name);
        if (!Files.isRegularFile(path)) return Result.failureKey("shutter.error.notFile", name);
        long size;
        byte[] header;
        try {
            size = Files.size(path);
            if (size == 0) return Result.failureKey("shutter.error.empty", name);
            if (size > MAX_BYTES) return Result.failureKey("shutter.error.tooLarge", name);
            try (InputStream in = Files.newInputStream(path)) { header = in.readNBytes(4); }
        } catch (IOException | SecurityException unreadable) {
            return Result.failureKey("shutter.error.unreadable", name);
        }
        if (!signatureMatches(extension, header)) return Result.failureKey("shutter.error.contentMismatch", name, extension.toUpperCase(Locale.ROOT));

        CameraMetadata metadata;
        try {
            // Local only: the bundled ExifTool reads the file (it starts on the first check of a context).
            com.photopilot.progress.Progress.stage("shutter.read");
            metadata = reader.read(path);
            com.photopilot.progress.Progress.validating("shutter.interpret");
        } catch (ExifToolException failure) {
            return Result.failureKey(switch (failure.kind()) {
                case UNAVAILABLE -> "shutter.error.exiftoolMissing";
                case STARTUP -> "shutter.error.exiftoolStart";
                case TIMEOUT -> "shutter.error.timeout";
                case TERMINATED -> "shutter.error.exiftoolStopped";
                case CLOSED -> "shutter.error.closed";
            });
        }
        if (metadata.error().isPresent()) {
            String error = metadata.error().get().toLowerCase(Locale.ROOT);
            if (error.contains("not found")) return Result.failureKey("shutter.error.notFound", name);
            if (error.contains("opening") || error.contains("permission")) return Result.failureKey("shutter.error.unreadable", name);
            if (error.contains("empty")) return Result.failureKey("shutter.error.empty", name);
            return Result.failureKey("shutter.error.corrupt", name);
        }
        // A damaged file still gets a FileType, with a warning such as "Bad IFD0 directory" or "JPEG format error".
        boolean damaged = metadata.warnings().stream().map(warning -> warning.toLowerCase(Locale.ROOT))
            .anyMatch(warning -> warning.contains("format error") || warning.contains("bad ") || warning.contains("truncat") || warning.contains("corrupt"));
        if (damaged && metadata.make().isEmpty() && metadata.model().isEmpty()) return Result.failureKey("shutter.error.corrupt", name);
        String type = metadata.fileType().orElse("");
        if (!FORMATS.containsKey(type)) return Result.failureKey("shutter.error.unsupportedDetected", name, type.isEmpty() ? "?" : type);

        Optional<CameraModel> detected = catalog.detect(metadata.make().orElse(""), metadata.model().orElse(null));
        var interpretation = ShutterCountRules.interpret(metadata, detected);
        var notes = new ArrayList<LocalizedMessage>();
        if (!FORMATS.get(type).contains(extension)) notes.add(LocalizedMessage.of("shutter.note.extensionMismatch", extension.toUpperCase(Locale.ROOT), type));
        notes.addAll(interpretation.notes());
        if (metadata.make().flatMap(Brand::fromMake).isPresent() && metadata.model().isPresent() && detected.isEmpty())
            notes.add(LocalizedMessage.of("shutter.note.notInCatalog", metadata.model().get()));
        metadata.warnings().forEach(warning -> notes.add(LocalizedMessage.of("shutter.note.exiftoolWarning", warning)));
        return Result.success(new ShutterCountResult(name, size, type, interpretation.status(), metadata.make(), metadata.model(),
            metadata.make().flatMap(Brand::fromMake), detected, metadata.captureDate(), interpretation.counter(), interpretation.others(),
            interpretation.reason(), notes));
    }

    /** How the camera that took the photo compares with the brand and (optional) model chosen in the selector. */
    public ModelCheck compare(ShutterCountResult result, Optional<Brand> selectedBrand, Optional<CameraModel> selectedModel) {
        if (result.brand().isEmpty()) return selectedModel.isPresent() ? ModelCheck.UNKNOWN : ModelCheck.NOT_SELECTED;
        Brand brand = result.brand().get();
        if (selectedModel.isPresent() && selectedModel.get().brand() != brand) return ModelCheck.BRAND_MISMATCH;
        if (selectedModel.isEmpty()) return selectedBrand.isPresent() && selectedBrand.get() != brand ? ModelCheck.BRAND_MISMATCH : ModelCheck.NOT_SELECTED;
        if (result.catalogModel().isPresent()) return result.catalogModel().get().equals(selectedModel.get()) ? ModelCheck.MATCH : ModelCheck.MISMATCH;
        return ModelCheck.UNKNOWN;
    }

    /** JPEG starts with FF D8 FF; ARW, NEF and DNG are TIFF-based ("II*\0" or "MM\0*"). */
    private static boolean signatureMatches(String extension, byte[] header) {
        if (header.length < 4) return false;
        if (extension.equals("jpg") || extension.equals("jpeg")) return (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
        boolean intel = header[0] == 'I' && header[1] == 'I' && header[2] == 42 && header[3] == 0;
        boolean motorola = header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == 42;
        return intel || motorola;
    }
}
