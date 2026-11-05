package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.MetadataScan;

/**
 * F11 UC12 outcome of one import: per-file results, the calculated statistics (if any photograph had usable metadata)
 * and what happened to them. Only SAVED and REPLACED wrote to storage; every other status left the session unchanged.
 */
public record ExifAnalysis(UUID sessionId, MetadataScan scan, Optional<ExifStatistics> statistics, Optional<ExifStatistics> previous,
        Status status, List<LocalizedMessage> notices) {
    public enum Status {
        /** Saved as the session's first statistics. */ SAVED,
        /** Saved, replacing earlier statistics after the user explicitly asked to replace them. */ REPLACED,
        /** The same files were analysed before; the saved statistics are identical and were kept. */ UNCHANGED,
        /** Different statistics already saved; nothing written until the user explicitly replaces them. */ REPLACE_REQUIRED,
        /** No photograph had usable metadata; nothing written and earlier statistics kept. */ NO_USABLE_DATA,
        /** Calculated but the atomic save failed; storage unchanged. */ SAVE_FAILED;
        public boolean persisted() { return this == SAVED || this == REPLACED || this == UNCHANGED; }
    }
    public ExifAnalysis {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(scan); Objects.requireNonNull(statistics); Objects.requireNonNull(previous);
        Objects.requireNonNull(status); notices = List.copyOf(notices);
    }
}
