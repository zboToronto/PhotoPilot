package com.photopilot.progress;

import com.photopilot.i18n.LocalizedMessage;
import java.util.List;

/**
 * Coarse classification of an operation failure, derived from the existing localized failure keys (never from free
 * provider text). It decides retry eligibility, the persistence status of a failed write and the next-step guidance.
 */
public enum ErrorKind {
    NONE, RATE_LIMITED, SERVICE_UNAVAILABLE, TIMEOUT, NETWORK, CREDENTIALS, INVALID_RESPONSE,
    STORAGE_BUSY, CONFLICT, STORAGE, CANCELLED,
    /** A business rule, prerequisite or validation rejected the request; retrying unchanged does not help. */
    REJECTED;

    /** True for failures of an external service that the user can retry later (or continue manually). */
    public boolean external() {
        return this == RATE_LIMITED || this == SERVICE_UNAVAILABLE || this == TIMEOUT || this == NETWORK || this == INVALID_RESPONSE;
    }

    public static ErrorKind of(List<LocalizedMessage> messages) {
        if (messages == null || messages.isEmpty()) return NONE;
        for (var message : messages) {
            var kind = of(message.key());
            if (kind != REJECTED) return kind;
        }
        return REJECTED;
    }

    public static ErrorKind of(String key) {
        if (key == null) return REJECTED;
        if (key.equals("progress.cancelled") || key.endsWith(".interrupted")) return CANCELLED;
        if (key.endsWith(".rateLimited")) return RATE_LIMITED;
        if (key.equals("ai.error.serviceUnavailable") || key.endsWith(".unavailable") && !key.startsWith("error.")
            || key.equals("env.error.provider")) return SERVICE_UNAVAILABLE;
        if (key.endsWith(".timeout") || key.endsWith("Timeout")) return TIMEOUT;
        if (key.endsWith(".network") || key.endsWith("Network")) return NETWORK;
        if (key.startsWith("ai.error.key") || key.endsWith(".authentication") || key.endsWith(".notConfigured") || key.equals("ai.error.modelNotFound")) return CREDENTIALS;
        if (key.equals("ai.error.invalidResponse") || key.equals("ai.error.emptyResponse") || key.endsWith(".malformed") || key.endsWith(".emptyAnswer")
            || key.equals("ai.error.malformedOutput") || key.equals("ai.error.unexpectedTool") || key.equals("ai.error.toolFailures")
            || key.equals("ai.error.stepLimit")) return INVALID_RESPONSE;
        if (key.endsWith(".busy")) return STORAGE_BUSY;
        if (key.equals("error.session.changed") || key.equals("error.inventory.changed") || key.endsWith(".stale")
            || key.endsWith("error.staleStatistics") || key.endsWith("error.stalePlan")) return CONFLICT;
        if (key.equals("error.session.storage") || key.equals("error.inventory.read") || key.equals("error.inventory.write")) return STORAGE;
        return REJECTED;
    }

    /** What a failed write means for the stored data; only used when the failure happened while saving. */
    public PersistenceStatus persistence() {
        return switch (this) {
            case CONFLICT -> PersistenceStatus.CONFLICT;
            case STORAGE_BUSY -> PersistenceStatus.RETRY_REQUIRED;
            default -> PersistenceStatus.SAVE_FAILED;
        };
    }
}
