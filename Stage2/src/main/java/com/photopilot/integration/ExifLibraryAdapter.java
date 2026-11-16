package com.photopilot.integration;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.drew.imaging.FileType;
import com.drew.imaging.FileTypeDetector;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.photopilot.common.Result;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.FileOutcome;
import com.photopilot.model.MetadataScan.Status;

/**
 * Stage 1 UML element 102 (zero-based): {Adapter; JPEG EXIF library and exported metadata reader}
 * Adapts the metadata-extractor library (JPEG APP1 EXIF only: IFD0 make/model and the EXIF sub-IFD; GPS, maker notes,
 * thumbnails and XMP are never read) and ExifTool JSON exports ({@code exiftool -json}, with or without {@code -n}) to
 * {@link ExifRecord}s. The content type is detected from the bytes, not the extension. JPEG and JSON files are read
 * once into memory, locally; nothing is modified, copied or sent anywhere. A folder contributes its own JPEG/JSON files
 * (and RAW files when enabled; not subfolders). Each selected path gets an outcome; one unreadable file never discards
 * the records of the others.
 *
 * <p>Camera RAW files ({@link #RAW_TYPES}, when {@link #RAW} is enabled) are recognised by their format-specific header,
 * hashed while streaming (never held in memory, pixels never decoded), and their capture settings are read through the
 * bundled ExifTool ({@link RawReader}, one request at a time) and mapped exactly like an imported ExifTool JSON export.
 * The format is then confirmed from ExifTool's answer, not the extension.
 */
public class ExifLibraryAdapter implements MetadataExtractor {
    public static final String JPEG = "jpeg", EXIFTOOL_JSON = "exiftool-json", RAW = "raw";
    /** JPEG EXIF and ExifTool JSON exports, read without any external program. */
    public static final Set<String> DEFAULT_FORMATS = Set.of(JPEG, EXIFTOOL_JSON);
    /** Also camera RAW files through ExifTool; needs a {@link RawReader}. */
    public static final Set<String> ALL_FORMATS = Set.of(JPEG, EXIFTOOL_JSON, RAW);
    public static final long MAX_JPEG_BYTES = 100L * 1024 * 1024, MAX_EXPORT_BYTES = 20L * 1024 * 1024, MAX_RAW_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FILES = 2000, MAX_EXPORT_ENTRIES = 20_000;
    /** Supported RAW extensions (matched case-insensitively) and the ExifTool FileType each file must have. */
    public static final Map<String, String> RAW_TYPES = Map.of("arw", "ARW", "raf", "RAF", "cr3", "CR3", "nef", "NEF", "dng", "DNG");
    /**
     * TIFF-based RAW formats without a marker tag of their own: ExifTool names a TIFF after its extension, so the camera
     * maker (Make) is the content evidence. DNG is confirmed by its DNGVersion tag instead.
     */
    private static final Map<String, List<String>> RAW_MAKERS = Map.of("ARW", List.of("SONY", "HASSELBLAD"), "NEF", List.of("NIKON"));
    /** After this many ExifTool timeouts or crashes in a row, the remaining RAW files of the scan are not sent to it. */
    private static final int RAW_FAILURE_LIMIT = 3;
    private static final Pattern FOCAL = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:\\s*mm)?(?:\\s*\\(.*\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPOSURE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*([0-9]+(?:\\.[0-9]+)?))?(?:\\s*s(?:ec)?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern F_NUMBER = Pattern.compile("(?:f\\s*/?\\s*)?([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("([0-9]{1,9})(?:\\.0+)?");
    private static final Pattern COMPENSATION = Pattern.compile("([+\\-−]?)\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*([0-9]+))?(?:\\s*EV)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4})[:-](\\d{2})[:-](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?\\s*(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern OFFSET = Pattern.compile("Z|[+-]\\d{2}:\\d{2}");
    private static final List<String> GROUPS = List.of("", "EXIF:", "IFD0:", "ExifIFD:");

    /** Reads the review tags of one RAW photo as ExifTool JSON ({@link ExifToolAdapter#reviewJson}); local, read-only. */
    @FunctionalInterface public interface RawReader { String json(Path file) throws ExifToolException; }

    private Set<String> supportedFormats;
    private final RawReader rawReader;

    public ExifLibraryAdapter(Set<String> supportedFormats) {
        this(supportedFormats, null);
    }

    public ExifLibraryAdapter(Set<String> supportedFormats, RawReader rawReader) {
        this.supportedFormats = Set.copyOf(supportedFormats);
        if (!ALL_FORMATS.containsAll(this.supportedFormats)) throw new IllegalArgumentException("Unsupported metadata format: " + supportedFormats);
        if (this.supportedFormats.contains(RAW) && rawReader == null) throw new IllegalArgumentException("RAW metadata needs an ExifTool reader");
        this.rawReader = rawReader;
    }

    public boolean supports(Path file) {
        try { return file != null && Files.isRegularFile(file) && kind(file).filter(supportedFormats::contains).isPresent(); }
        catch (IOException | RuntimeException unreadable) { return false; }
    }

    public Result<List<ExifRecord>> extract(List<Path> files) {
        var scan = scan(files);
        if (!scan.isSuccess()) return Result.failure(scan);
        var warnings = new ArrayList<String>();
        for (var file : scan.value().orElseThrow().files())
            for (var note : file.notes()) warnings.add(file.name() + ": " + EnglishMessages.text(note.key(), note.arguments().toArray()));
        return new Result<>(Optional.of(scan.value().orElseThrow().records()), List.of(), warnings);
    }

    public Result<MetadataScan> scan(List<Path> files) {
        if (files == null || files.stream().allMatch(Objects::isNull)) return Result.failureKey("review.error.noFiles");
        var outcomes = new ArrayList<FileOutcome>();
        var candidates = new ArrayList<Path>();
        for (Path selected : files) {
            if (candidates.size() > MAX_FILES) return Result.failureKey("review.error.tooManyFiles", MAX_FILES);
            if (selected == null) continue;
            if (!Files.isDirectory(selected)) { candidates.add(selected); continue; }
            try (var entries = Files.list(selected)) {
                // Name first (no file-system access), and at most one file more than the limit leaves room for: a huge
                // folder is never stat-ed or sorted in full and still fails below; smaller folders give the same list.
                var found = entries.filter(p -> folderCandidate(name(p))).filter(Files::isRegularFile)
                    .limit(MAX_FILES + 1L - candidates.size()).sorted().toList();
                if (found.isEmpty()) outcomes.add(outcome(selected, "", Status.UNSUPPORTED, "review.file.emptyFolder"));
                candidates.addAll(found);
            } catch (IOException | SecurityException denied) {
                outcomes.add(outcome(selected, "", Status.UNREADABLE, "review.file.folderUnreadable"));
            }
        }
        if (candidates.size() > MAX_FILES) return Result.failureKey("review.error.tooManyFiles", MAX_FILES);
        var records = new ArrayList<ExifRecord>();
        var realPaths = new HashSet<Path>();
        var contents = new HashMap<String, String>();
        var used = new TreeSet<String>();
        var rawRun = new RawRun();
        for (Path file : candidates) {
            String name = name(file);
            if (!Files.exists(file)) { outcomes.add(outcome(file, "", Status.MISSING, "review.file.missing")); continue; }
            if (!Files.isRegularFile(file)) { outcomes.add(outcome(file, "", Status.UNSUPPORTED, "review.file.notRegular")); continue; }
            try {
                if (!realPaths.add(file.toRealPath())) { outcomes.add(outcome(file, "", Status.DUPLICATE, "review.file.duplicatePath")); continue; }
                if (!Files.isReadable(file)) { outcomes.add(outcome(file, "", Status.UNREADABLE, "review.file.unreadable")); continue; }
                var kind = kind(file);
                String rawType = RAW_TYPES.get(extension(name));
                if (kind.isEmpty() && rawType != null && supportedFormats.contains(RAW)) {
                    // A RAW extension whose content does not start with that format's header.
                    outcomes.add(new FileOutcome(name, "", Status.UNSUPPORTED, 0, List.of(LocalizedMessage.of("review.file.rawMismatch", rawType, detected(file)))));
                    continue;
                }
                if (kind.isEmpty() || !supportedFormats.contains(kind.get())) {
                    outcomes.add(new FileOutcome(name, "", Status.UNSUPPORTED, 0, List.of(LocalizedMessage.of("review.file.unsupportedType", detected(file)))));
                    continue;
                }
                boolean jpeg = kind.get().equals(JPEG), rawFile = kind.get().equals(RAW);
                String label = jpeg ? "JPEG" : rawFile ? rawType : "EXPORT";
                long size = Files.size(file), limit = jpeg ? MAX_JPEG_BYTES : rawFile ? MAX_RAW_BYTES : MAX_EXPORT_BYTES;
                var tooLarge = new FileOutcome(name, label, Status.TOO_LARGE, 0, List.of(LocalizedMessage.of("review.file.tooLarge", limit / (1024 * 1024))));
                if (size > limit) { outcomes.add(tooLarge); continue; }
                // A RAW file is only hashed while streaming (bounded memory); JPEG and JSON are read once and parsed from
                // memory, at most one byte past the limit, so a file that grew after the size check is still refused.
                byte[] bytes = rawFile ? null : readAtMost(file, limit + 1);
                if (bytes != null && bytes.length > limit) { outcomes.add(tooLarge); continue; }
                String hash = rawFile ? sha256(file) : sha256(bytes);
                if (contents.containsKey(hash)) {
                    outcomes.add(new FileOutcome(name, label, Status.DUPLICATE, 0, List.of(LocalizedMessage.of("review.file.duplicateContent", contents.get(hash)))));
                    continue;
                }
                contents.put(hash, name);
                var read = jpeg ? jpeg(name, bytes) : rawFile ? raw(name, rawType, file, rawRun) : export(name, bytes);
                outcomes.add(read.outcome()); records.addAll(read.records());
                if (!read.records().isEmpty()) used.add(hash);
            } catch (IOException | SecurityException unreadable) {
                outcomes.add(outcome(file, "", Status.UNREADABLE, "review.file.unreadable"));
            }
        }
        if (outcomes.isEmpty()) return Result.failureKey("review.error.noFiles");
        return Result.success(new MetadataScan(records, outcomes, used.isEmpty() ? "" : sha256(String.join("\n", used).getBytes(StandardCharsets.UTF_8))));
    }

    private record Read(FileOutcome outcome, List<ExifRecord> records) { }

    /** JPEG EXIF through the library; a JPEG without EXIF is still a photograph whose metadata is missing. */
    private static Read jpeg(String name, byte[] bytes) {
        Metadata metadata;
        try { metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(bytes), List.of(new ExifReader())); }
        catch (Exception corrupt) { return new Read(new FileOutcome(name, "JPEG", Status.CORRUPT, 0, List.of(LocalizedMessage.of("review.file.corruptJpeg"))), List.of()); }
        Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class), exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (ifd0 == null && exif == null) {
            var empty = new ExifRecord(name, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Set.of());
            return new Read(new FileOutcome(name, "JPEG", Status.NO_METADATA, 1, List.of(LocalizedMessage.of("review.file.noExif"))), List.of(empty));
        }
        var invalid = new LinkedHashSet<String>();
        var camera = ExifRecord.equipmentName(text(ifd0, ExifIFD0Directory.TAG_MAKE), text(ifd0, ExifIFD0Directory.TAG_MODEL));
        var lens = ExifRecord.equipmentName(text(exif, ExifSubIFDDirectory.TAG_LENS_MAKE), text(exif, ExifSubIFDDirectory.TAG_LENS_MODEL));
        var focal = rational(exif, ExifSubIFDDirectory.TAG_FOCAL_LENGTH, "focalLength", invalid);
        var shutter = rational(exif, ExifSubIFDDirectory.TAG_EXPOSURE_TIME, "shutter", invalid);
        var fNumber = rational(exif, ExifSubIFDDirectory.TAG_FNUMBER, "aperture", invalid);
        var compensation = rational(exif, ExifSubIFDDirectory.TAG_EXPOSURE_BIAS, "exposureCompensation", invalid);
        var iso = iso(exif, invalid);
        var time = time(text(exif, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL), invalid);
        var offset = offset(text(exif, ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL)).or(() -> time.offset());
        var record = new ExifRecord(name, focal, shutter, fNumber, iso, camera, lens, compensation, time.local(), offset, invalid);
        boolean warned = metadata.hasErrors();
        return new Read(new FileOutcome(name, "JPEG", warned ? Status.PARTIAL : Status.READ, 1,
            warned ? List.of(LocalizedMessage.of("review.file.libraryWarnings")) : List.of()), List.of(record));
    }
    private static String text(Directory directory, int tag) {
        if (directory == null || !directory.containsTag(tag)) return "";
        return Objects.toString(directory.getString(tag), "").replace("\u0000", "");
    }
    private static Optional<Double> rational(Directory directory, int tag, String field, Set<String> invalid) {
        if (directory == null || !directory.containsTag(tag)) return Optional.empty();
        Rational value = directory.getRational(tag);
        if (value == null || value.getDenominator() == 0) { invalid.add(field); return Optional.empty(); }
        return Optional.of(value.doubleValue());
    }
    /** PhotographicSensitivity; 65535 means "65535 or more" (EXIF 2.3), so only the ISOSpeed tag can replace it. */
    private static Optional<Integer> iso(Directory directory, Set<String> invalid) {
        if (directory == null || !directory.containsTag(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)) return Optional.empty();
        Integer value = directory.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT);
        if (value != null && value == 65535 && directory.containsTag(ExifSubIFDDirectory.TAG_ISO_SPEED)) value = directory.getInteger(ExifSubIFDDirectory.TAG_ISO_SPEED);
        if (value == null || value == 65535) { invalid.add("iso"); return Optional.empty(); }
        return Optional.of(value);
    }

    /** ExifTool JSON: an array of objects (or one object) each naming its SourceFile; other JSON is rejected. */
    private static Read export(String name, byte[] bytes) {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { return failed(name, Status.CORRUPT, "review.file.encoding"); }
        if (text.startsWith("﻿")) text = text.substring(1);
        JsonElement root;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            root = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(reader, JsonElement.class);
            if (root == null || reader.peek() != JsonToken.END_DOCUMENT) return failed(name, Status.CORRUPT, "review.file.invalidJson");
        } catch (IOException | RuntimeException malformed) { return failed(name, Status.CORRUPT, "review.file.invalidJson"); }
        var entries = new ArrayList<JsonElement>();
        if (root.isJsonArray()) root.getAsJsonArray().forEach(entries::add);
        else if (root.isJsonObject()) entries.add(root);
        else return failed(name, Status.INVALID_STRUCTURE, "review.file.notExifTool");
        if (entries.size() > MAX_EXPORT_ENTRIES) return new Read(new FileOutcome(name, "EXPORT", Status.TOO_LARGE, 0,
            List.of(LocalizedMessage.of("review.file.tooManyEntries", MAX_EXPORT_ENTRIES))), List.of());
        var records = new ArrayList<ExifRecord>();
        int skipped = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof JsonObject entry) || raw(entry, "SourceFile", new HashSet<>(), "").isEmpty()) { skipped++; continue; }
            records.add(record(entry, name, i + 1));
        }
        if (records.isEmpty()) return failed(name, Status.INVALID_STRUCTURE, "review.file.notExifTool");
        var notes = skipped == 0 ? List.<LocalizedMessage>of() : List.of(LocalizedMessage.of("review.file.skippedEntries", skipped));
        return new Read(new FileOutcome(name, "EXPORT", skipped == 0 ? Status.READ : Status.PARTIAL, records.size(), notes), records);
    }
    private static Read failed(String name, Status status, String key) {
        return new Read(new FileOutcome(name, "EXPORT", status, 0, List.of(LocalizedMessage.of(key))), List.of());
    }
    private static ExifRecord record(JsonObject entry, String file, int index) {
        var invalid = new LinkedHashSet<String>();
        String source = raw(entry, "FileName", invalid, "").or(() -> raw(entry, "SourceFile", invalid, "")).map(ExifLibraryAdapter::lastSegment).orElse("");
        if (source.isBlank() || source.length() > 200) source = file + " #" + index;
        var camera = ExifRecord.equipmentName(raw(entry, "Make", invalid, "camera").orElse(""), raw(entry, "Model", invalid, "camera").orElse(""));
        var lens = ExifRecord.equipmentName(raw(entry, "LensMake", invalid, "lens").orElse(""), raw(entry, "LensModel", invalid, "lens").orElse(""));
        var focal = number(entry, "FocalLength", "focalLength", FOCAL, invalid);
        var shutter = number(entry, "ExposureTime", "shutter", EXPOSURE, invalid);
        var fNumber = number(entry, "FNumber", "aperture", F_NUMBER, invalid);
        var compensation = number(entry, "ExposureCompensation", "exposureCompensation", COMPENSATION, invalid);
        Optional<Integer> iso = Optional.empty();
        var isoText = raw(entry, "ISO", invalid, "iso");
        if (isoText.isPresent()) {
            Matcher m = ISO.matcher(isoText.get().strip());
            try { if (m.matches()) iso = Optional.of(Integer.parseInt(m.group(1))); else invalid.add("iso"); }
            catch (NumberFormatException overflow) { invalid.add("iso"); }
        }
        var time = time(raw(entry, "DateTimeOriginal", invalid, "captureTime").orElse(""), invalid);
        var offset = offset(raw(entry, "OffsetTimeOriginal", invalid, "").orElse("")).or(() -> time.offset());
        return new ExifRecord(source, focal, shutter, fNumber, iso, camera, lens, compensation, time.local(), offset, invalid);
    }
    /** The tag's scalar text (also under the common -G group prefixes); arrays/objects mark the field invalid. */
    private static Optional<String> raw(JsonObject entry, String tag, Set<String> invalid, String field) {
        for (String group : GROUPS) {
            JsonElement value = entry.get(group + tag);
            if (value == null || value.isJsonNull()) continue;
            if (!value.isJsonPrimitive()) { if (!field.isEmpty()) invalid.add(field); return Optional.empty(); }
            String text = value.getAsString().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
        return Optional.empty();
    }
    private static Optional<Double> number(JsonObject entry, String tag, String field, Pattern format, Set<String> invalid) {
        for (String group : GROUPS) {
            JsonElement value = entry.get(group + tag);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return Optional.of(value.getAsDouble());
        }
        var text = raw(entry, tag, invalid, field);
        if (text.isEmpty()) return Optional.empty();
        Matcher m = format.matcher(text.get());
        if (!m.matches()) { invalid.add(field); return Optional.empty(); }
        if (format == COMPENSATION) {
            double magnitude = Double.parseDouble(m.group(2));
            if (m.group(3) != null) { double d = Double.parseDouble(m.group(3)); if (d == 0) { invalid.add(field); return Optional.empty(); } magnitude /= d; }
            return Optional.of(m.group(1).equals("-") || m.group(1).equals("−") ? -magnitude : magnitude);
        }
        double first = Double.parseDouble(m.group(1));
        if (format == EXPOSURE && m.group(2) != null) {
            double d = Double.parseDouble(m.group(2));
            if (d == 0) { invalid.add(field); return Optional.empty(); }
            return Optional.of(first / d);
        }
        return Optional.of(first);
    }

    /** ExifTool state of one scan: once it is unavailable, or failed {@link #RAW_FAILURE_LIMIT} times in a row, it is not asked again. */
    private static final class RawRun { String blocked; int consecutiveFailures; }

    /**
     * One RAW photo through ExifTool, mapped like one entry of an ExifTool JSON export. The format is confirmed from the
     * answer: ExifTool's FileType must be the one of the extension, a DNG must carry DNGVersion, and an ARW/NEF (TIFF-based,
     * named by ExifTool after its extension) must name its camera maker. Missing values stay missing.
     */
    private Read raw(String name, String type, Path file, RawRun run) {
        if (run.blocked != null) return rawFailed(name, type, Status.UNREADABLE, run.blocked);
        String json;
        try {
            json = rawReader.json(file);
            run.consecutiveFailures = 0;
        } catch (ExifToolException failure) {
            String key = switch (failure.kind()) {
                case UNAVAILABLE, STARTUP, CLOSED -> "review.file.exiftoolUnavailable";
                case TIMEOUT -> "review.file.exiftoolTimeout";
                case TERMINATED -> "review.file.exiftoolFailed";
            };
            boolean unavailable = failure.kind() != ExifToolException.Kind.TIMEOUT && failure.kind() != ExifToolException.Kind.TERMINATED;
            if (unavailable || ++run.consecutiveFailures >= RAW_FAILURE_LIMIT) run.blocked = key;
            return rawFailed(name, type, Status.UNREADABLE, key);
        } catch (IllegalArgumentException unusablePath) {
            // ExifTool's argument protocol cannot carry a line break; such a path is reported, never sent.
            return rawFailed(name, type, Status.UNREADABLE, "review.file.unreadable");
        }
        JsonObject entry = firstEntry(json);
        if (entry == null) return rawFailed(name, type, Status.CORRUPT, "review.file.corruptRaw");
        String error = scalar(entry, "Error");
        if (!error.isEmpty()) {
            String lower = error.toLowerCase(Locale.ROOT);
            return lower.contains("opening") || lower.contains("permission") ? rawFailed(name, type, Status.UNREADABLE, "review.file.unreadable")
                : rawFailed(name, type, Status.CORRUPT, "review.file.corruptRaw");
        }
        String detected = scalar(entry, "FileType"), make = scalar(entry, "Make"), warning = scalar(entry, "Warning");
        if (!type.equals(detected)) return rawFailed(name, type, Status.UNSUPPORTED, "review.file.rawMismatch", type, detected.isEmpty() ? "?" : detected);
        String lowerWarning = warning.toLowerCase(Locale.ROOT);
        boolean damaged = lowerWarning.contains("format error") || lowerWarning.contains("bad ") || lowerWarning.contains("truncat") || lowerWarning.contains("corrupt");
        if (damaged && make.isEmpty() && scalar(entry, "Model").isEmpty()) return rawFailed(name, type, Status.CORRUPT, "review.file.corruptRaw");
        if (type.equals("DNG") && scalar(entry, "DNGVersion").isEmpty()) return rawFailed(name, type, Status.UNSUPPORTED, "review.file.rawMismatch", type, "TIFF");
        var makers = RAW_MAKERS.get(type);
        String upperMake = make.toUpperCase(Locale.ROOT);
        if (makers != null && makers.stream().noneMatch(upperMake::startsWith))
            return rawFailed(name, type, Status.UNSUPPORTED, "review.file.rawMismatch", type, "TIFF");
        // The record carries the file name only, never the path ExifTool echoes as SourceFile.
        entry.remove("SourceFile");
        entry.addProperty("FileName", name);
        ExifRecord record = record(entry, name, 1);
        if (record.missingFields().containsAll(ExifRecord.FIELDS) && record.invalidFields().isEmpty())
            return new Read(new FileOutcome(name, type, Status.NO_METADATA, 1, List.of(LocalizedMessage.of("review.file.noExif"))), List.of(record));
        return new Read(new FileOutcome(name, type, warning.isEmpty() ? Status.READ : Status.PARTIAL, 1,
            warning.isEmpty() ? List.of() : List.of(LocalizedMessage.of("review.file.libraryWarnings"))), List.of(record));
    }
    private static Read rawFailed(String name, String type, Status status, String key, Object... arguments) {
        return new Read(new FileOutcome(name, type, status, 0, List.of(LocalizedMessage.of(key, arguments))), List.of());
    }
    /** The first object of ExifTool's JSON array, or null when the answer is empty or not that shape. */
    private static JsonObject firstEntry(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonArray() && !root.getAsJsonArray().isEmpty() && root.getAsJsonArray().get(0).isJsonObject())
                return root.getAsJsonArray().get(0).getAsJsonObject();
        } catch (RuntimeException malformed) { /* reported as unreadable metadata */ }
        return null;
    }
    private static String scalar(JsonObject entry, String tag) {
        JsonElement value = entry.get(tag);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().strip();
    }

    private record Time(Optional<LocalDateTime> local, Optional<ZoneOffset> offset) { }
    /** EXIF "YYYY:MM:DD HH:MM:SS" (ExifTool may append fractions or an offset); all-zero placeholders are missing. */
    private static Time time(String text, Set<String> invalid) {
        String value = text.strip();
        if (value.isEmpty() || value.replaceAll("[0: -]", "").isEmpty()) return new Time(Optional.empty(), Optional.empty());
        Matcher m = DATE_TIME.matcher(value);
        if (!m.matches()) { invalid.add("captureTime"); return new Time(Optional.empty(), Optional.empty()); }
        try {
            var local = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            return new Time(Optional.of(local), m.group(7) == null ? Optional.empty() : offset(m.group(7)));
        } catch (DateTimeException impossible) {
            invalid.add("captureTime"); return new Time(Optional.empty(), Optional.empty());
        }
    }
    private static Optional<ZoneOffset> offset(String text) {
        String value = text.strip();
        if (!OFFSET.matcher(value).matches()) return Optional.empty();
        try { return Optional.of(ZoneOffset.of(value)); } catch (DateTimeException invalid) { return Optional.empty(); }
    }

    /**
     * Supported content type detected from the first bytes: JPEG by signature, ExifTool JSON by structure and name, RAW
     * when the extension names a RAW format and the content starts with that format's own header (TIFF for ARW, NEF and
     * DNG; "FUJIFILMCCD-RAW" for RAF; an ISO media "ftyp crx " box for CR3). ExifTool confirms RAW files afterwards.
     */
    private static Optional<String> kind(Path file) throws IOException {
        byte[] head = head(file);
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return Optional.of(JPEG);
        String rawType = RAW_TYPES.get(extension(name(file)));
        if (rawType != null) return rawHeader(rawType, head) ? Optional.of(RAW) : Optional.empty();
        String text = new String(head, StandardCharsets.UTF_8).replace("﻿", "").stripLeading();
        boolean json = name(file).toLowerCase(Locale.ROOT).endsWith(".json") && (text.startsWith("[") || text.startsWith("{"));
        return json ? Optional.of(EXIFTOOL_JSON) : Optional.empty();
    }
    private static boolean rawHeader(String type, byte[] head) {
        return switch (type) {
            case "ARW", "NEF", "DNG" -> head.length >= 4 && ((head[0] == 'I' && head[1] == 'I' && head[2] == 42 && head[3] == 0)
                || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42));
            case "RAF" -> ascii(head, 0, "FUJIFILMCCD-RAW");
            case "CR3" -> ascii(head, 4, "ftyp") && ascii(head, 8, "crx ");
            default -> false;
        };
    }
    private static boolean ascii(byte[] bytes, int offset, String expected) {
        byte[] wanted = expected.getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= offset + wanted.length && Arrays.equals(bytes, offset, offset + wanted.length, wanted, 0, wanted.length);
    }
    /** Folder entries considered: JPEG and JSON as before, plus the RAW extensions when RAW is enabled. */
    private boolean folderCandidate(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|json)") || (supportedFormats.contains(RAW) && RAW_TYPES.containsKey(extension(name)));
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static String detected(Path file) {
        try (InputStream in = new BufferedInputStream(new ByteArrayInputStream(head(file)))) {
            FileType type = FileTypeDetector.detectFileType(in);
            if (type != FileType.Unknown) return type.getName();
        } catch (IOException | RuntimeException unknown) { /* reported by extension below */ }
        String name = name(file);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "?" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
    private static byte[] head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return in.readNBytes(64); }
    }
    /** The whole file when it has at most {@code count} bytes, else its first {@code count} bytes (count is below 2 GB). */
    private static byte[] readAtMost(Path file, long count) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return in.readNBytes(Math.toIntExact(count)); }
    }
    private static FileOutcome outcome(Path file, String kind, Status status, String key) {
        return new FileOutcome(name(file), kind, status, 0, List.of(LocalizedMessage.of(key)));
    }
    /** File name only: folders of the user's file system are never shown, stored or sent. */
    private static String name(Path file) { return file.getFileName() == null ? file.toString() : file.getFileName().toString(); }
    private static String lastSegment(String path) { return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1).strip(); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** The same content hash computed while streaming the file through a 64 KiB buffer (used for RAW files). */
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
