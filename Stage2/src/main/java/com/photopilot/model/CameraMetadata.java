package com.photopilot.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The metadata a shutter count check needs, as read from one photo. Every value keeps where it was found:
 * {@code directory} is ExifTool's metadata path (family 5 group, for example
 * {@code TIFF-IFD0-ExifIFD-MakerNotes-Tag9050c}), so counters from different MakerNotes tables stay distinct.
 * Nothing here holds a file path, GPS position or serial number.
 *
 * @param error ExifTool's per-file error (for example "File format error"), if it could not read the file
 */
public record CameraMetadata(List<Field> fields, Optional<String> error, List<String> warnings) {
    public CameraMetadata {
        fields = List.copyOf(fields); Objects.requireNonNull(error); warnings = List.copyOf(warnings);
    }

    /** One tag value with its metadata path. */
    public record Field(String directory, String tag, String value) {
        public Field { Objects.requireNonNull(directory); Objects.requireNonNull(tag); Objects.requireNonNull(value); }
        /** The innermost directory: "Tag9050c", "MakerNotes", "ShotInfoD850", "IFD0" ... */
        public String table() { int dash = directory.lastIndexOf('-'); return dash < 0 ? directory : directory.substring(dash + 1); }
        public boolean inMakerNotes() { return ("-" + directory + "-").contains("-MakerNotes-"); }
        /** "MakerNotes › Tag9050c › ShutterCount": the part of the path from the MakerNotes onward. */
        public String source() {
            int start = directory.indexOf("MakerNotes");
            String path = start < 0 ? table() : directory.substring(start);
            return String.join(" › ", path.split("-")) + " › " + tag;
        }
    }

    public List<Field> all(String tag) { return fields.stream().filter(field -> field.tag().equals(tag)).toList(); }
    /** First non-blank value of a tag, in ExifTool's output order (IFD0 before IFD1 for Make/Model). */
    public Optional<String> first(String tag) {
        return all(tag).stream().map(Field::value).map(String::strip).filter(value -> !value.isEmpty()).findFirst();
    }
    public Optional<String> make() { return first("Make"); }
    public Optional<String> model() { return first("Model"); }
    public Optional<String> fileType() { return first("FileType"); }
    /** DateTimeOriginal, else CreateDate, as written by the camera ("yyyy:MM:dd HH:mm:ss"). */
    public Optional<String> captureDate() { return first("DateTimeOriginal").or(() -> first("CreateDate")); }
    public boolean makerNotesPresent() { return fields.stream().anyMatch(Field::inMakerNotes); }
}
