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
 * {@link ExifRecord}s. The content type is detected from the bytes, not the extension. Files are read once into memory,
 * locally; nothing is modified, copied or sent anywhere. A folder contributes its own JPEG/JSON files (not
 * subfolders). Each selected path gets an outcome; one unreadable file never discards the records of the others.
 */
public class ExifLibraryAdapter implements MetadataExtractor {
    public static final String JPEG = "jpeg", EXIFTOOL_JSON = "exiftool-json";
    public static final Set<String> DEFAULT_FORMATS = Set.of(JPEG, EXIFTOOL_JSON);
    public static final long MAX_JPEG_BYTES = 100L * 1024 * 1024, MAX_EXPORT_BYTES = 20L * 1024 * 1024;
    public static final int MAX_FILES = 2000, MAX_EXPORT_ENTRIES = 20_000;
    private static final Pattern FOCAL = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:\\s*mm)?(?:\\s*\\(.*\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPOSURE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*([0-9]+(?:\\.[0-9]+)?))?(?:\\s*s(?:ec)?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern F_NUMBER = Pattern.compile("(?:f\\s*/?\\s*)?([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("([0-9]{1,9})(?:\\.0+)?");
    private static final Pattern COMPENSATION = Pattern.compile("([+\\-−]?)\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*/\\s*([0-9]+))?(?:\\s*EV)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4})[:-](\\d{2})[:-](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?\\s*(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern OFFSET = Pattern.compile("Z|[+-]\\d{2}:\\d{2}");
    private static final List<String> GROUPS = List.of("", "EXIF:", "IFD0:", "ExifIFD:");

    private Set<String> supportedFormats;

    public ExifLibraryAdapter(Set<String> supportedFormats) {
        this.supportedFormats = Set.copyOf(supportedFormats);
        if (!DEFAULT_FORMATS.containsAll(this.supportedFormats)) throw new IllegalArgumentException("Unsupported metadata format: " + supportedFormats);
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
            if (selected == null) continue;
            if (!Files.isDirectory(selected)) { candidates.add(selected); continue; }
            try (var entries = Files.list(selected)) {
                var found = entries.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|json)"))
                    .sorted().toList();
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
        for (Path file : candidates) {
            String name = name(file);
            if (!Files.exists(file)) { outcomes.add(outcome(file, "", Status.MISSING, "review.file.missing")); continue; }
            if (!Files.isRegularFile(file)) { outcomes.add(outcome(file, "", Status.UNSUPPORTED, "review.file.notRegular")); continue; }
            try {
                if (!realPaths.add(file.toRealPath())) { outcomes.add(outcome(file, "", Status.DUPLICATE, "review.file.duplicatePath")); continue; }
                if (!Files.isReadable(file)) { outcomes.add(outcome(file, "", Status.UNREADABLE, "review.file.unreadable")); continue; }
                var kind = kind(file);
                if (kind.isEmpty() || !supportedFormats.contains(kind.get())) {
                    outcomes.add(new FileOutcome(name, "", Status.UNSUPPORTED, 0, List.of(LocalizedMessage.of("review.file.unsupportedType", detected(file)))));
                    continue;
                }
                boolean jpeg = kind.get().equals(JPEG);
                String label = jpeg ? "JPEG" : "EXPORT";
                long size = Files.size(file), limit = jpeg ? MAX_JPEG_BYTES : MAX_EXPORT_BYTES;
                if (size > limit) { outcomes.add(new FileOutcome(name, label, Status.TOO_LARGE, 0, List.of(LocalizedMessage.of("review.file.tooLarge", limit / (1024 * 1024))))); continue; }
                byte[] bytes = Files.readAllBytes(file);
                String hash = sha256(bytes);
                if (contents.containsKey(hash)) {
                    outcomes.add(new FileOutcome(name, label, Status.DUPLICATE, 0, List.of(LocalizedMessage.of("review.file.duplicateContent", contents.get(hash)))));
                    continue;
                }
                contents.put(hash, name);
                var read = jpeg ? jpeg(name, bytes) : export(name, bytes);
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

    /** Supported content type detected from the first bytes: JPEG by signature, ExifTool JSON by structure and name. */
    private static Optional<String> kind(Path file) throws IOException {
        byte[] head = head(file);
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return Optional.of(JPEG);
        String text = new String(head, StandardCharsets.UTF_8).replace("﻿", "").stripLeading();
        boolean json = name(file).toLowerCase(Locale.ROOT).endsWith(".json") && (text.startsWith("[") || text.startsWith("{"));
        return json ? Optional.of(EXIFTOOL_JSON) : Optional.empty();
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
}
