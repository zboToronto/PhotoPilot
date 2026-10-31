package com.photopilot.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Local data identity, independent of its display name; not an authenticated user. */
public record LocalProfile(UUID id, String displayName, Instant createdAt, Instant updatedAt) {
    public LocalProfile {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        displayName = validName(displayName);
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Profile timestamps are inconsistent.");
    }
    public static String validName(String name) {
        if (name == null || name.strip().isEmpty() || name.strip().codePointCount(0, name.strip().length()) > 80
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Profile name must contain 1-80 characters without control characters.");
        return name.strip();
    }
    public LocalProfile renamed(String name) {
        Instant now = Instant.now();
        return new LocalProfile(id, name, createdAt, now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1));
    }
}
