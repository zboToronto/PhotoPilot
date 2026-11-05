package com.photopilot.model;

import java.util.List;
import java.util.Objects;
import com.photopilot.i18n.LocalizedMessage;

/**
 * F11 UC12 result of reading the selected files: the extracted records and one outcome per selected path, so an
 * unreadable file is reported without discarding the others. Names are file names only, never directories.
 * {@code fingerprint} identifies the exact content that produced the records (empty when nothing was read).
 */
public record MetadataScan(List<ExifRecord> records, List<FileOutcome> files, String fingerprint) {
    public enum Status {
        /** Metadata read (possibly with some missing fields). */ READ,
        /** Read with library/format warnings or skipped entries. */ PARTIAL,
        /** A supported file without any EXIF metadata. */ NO_METADATA,
        UNSUPPORTED, MISSING, UNREADABLE, TOO_LARGE, CORRUPT, INVALID_STRUCTURE,
        /** Same path or content selected again; counted once, so not a failure. */ DUPLICATE;
        public boolean failed() { return this != READ && this != PARTIAL && this != NO_METADATA && this != DUPLICATE; }
    }
    /** {@code kind}: "JPEG", "EXPORT" or "" when the content type was not recognised. */
    public record FileOutcome(String name, String kind, Status status, int records, List<LocalizedMessage> notes) {
        public FileOutcome { Objects.requireNonNull(name); Objects.requireNonNull(kind); Objects.requireNonNull(status); notes = List.copyOf(notes); }
    }
    public MetadataScan { records = List.copyOf(records); files = List.copyOf(files); Objects.requireNonNull(fingerprint); }
    public long failedFiles() { return files.stream().filter(file -> file.status().failed()).count(); }
}
