package com.photopilot.model;

import com.photopilot.i18n.LocalizedMessage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of reading one photo's shutter counter. A result exists whenever the file itself could be read; its status
 * says whether a usable counter was found. A missing counter is never replaced by zero or an estimate.
 *
 * @param fileName file name only (no folder), for display
 * @param fileSize bytes on disk
 * @param fileType format reported by ExifTool (JPEG, ARW, NEF, DNG)
 * @param counter the selected counter, present only for {@link Status#SUCCESS}
 * @param otherCounters further counter fields found, each with its relation to the selected one
 * @param reason why no counter is shown (status other than SUCCESS)
 * @param notes explanations and warnings: conflicts, documented caveats, shutter type of this photo
 */
public record ShutterCountResult(String fileName, long fileSize, String fileType, Status status,
                                 Optional<String> make, Optional<String> model, Optional<CameraModel.Brand> brand,
                                 Optional<CameraModel> catalogModel, Optional<String> captureDate,
                                 Optional<CounterReading> counter, List<CounterReading> otherCounters,
                                 Optional<LocalizedMessage> reason, List<LocalizedMessage> notes) {
    public ShutterCountResult {
        Objects.requireNonNull(fileName); Objects.requireNonNull(fileType); Objects.requireNonNull(status);
        Objects.requireNonNull(make); Objects.requireNonNull(model); Objects.requireNonNull(brand);
        Objects.requireNonNull(catalogModel); Objects.requireNonNull(captureDate); Objects.requireNonNull(counter);
        otherCounters = List.copyOf(otherCounters); Objects.requireNonNull(reason); notes = List.copyOf(notes);
        if ((status == Status.SUCCESS) != counter.isPresent()) throw new IllegalArgumentException("A counter is present exactly for SUCCESS");
    }

    public enum Status {
        SUCCESS,
        /** The photo has camera MakerNotes but no usable counter field for this model. */
        NO_COUNTER,
        /** A counter field exists but its value is not a valid count (zero, "n/a", out of range, unreadable). */
        INVALID_COUNTER,
        /** No manufacturer MakerNotes: edited, exported or converted files often drop them. */
        MAKERNOTES_MISSING,
        /** The photo has no camera make/model. */
        NO_CAMERA,
        /** The photo is from a camera brand this checker does not support. */
        UNSUPPORTED_BRAND
    }

    public enum Relation { SELECTED, MATCHES_SELECTED, DIFFERS_FROM_SELECTED, SEPARATE_COUNTER }

    /** One counter field: its tag, where it was found, the original value and what it counts. */
    public record CounterReading(String tag, String directory, String source, long value, String original, CounterKind kind, Relation relation) {
        public CounterReading {
            Objects.requireNonNull(tag); Objects.requireNonNull(directory); Objects.requireNonNull(source);
            Objects.requireNonNull(original); Objects.requireNonNull(kind); Objects.requireNonNull(relation);
        }
        public String table() { int dash = directory.lastIndexOf('-'); return dash < 0 ? directory : directory.substring(dash + 1); }
    }

    /** How the detected camera compares with the model chosen in the selector. */
    public enum ModelCheck { NOT_SELECTED, MATCH, MISMATCH, BRAND_MISMATCH, UNKNOWN }
}
