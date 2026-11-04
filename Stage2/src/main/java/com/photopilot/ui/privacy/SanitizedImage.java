package com.photopilot.ui.privacy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 42 (zero-based): {only boundary constructs; transient; never persisted}
 * F04: the re-encoded, user-approved copy that is the only image form allowed across the AI boundary.
 * Only {@link ImagePrivacyBoundary} constructs it; {@link #clear()} zeroes the bytes when processing ends.
 */
public class SanitizedImage {
    private byte[] bytes;
    private String mediaType;
    private boolean userApproved;
    private final Path copy;

    SanitizedImage(byte[] bytes, String mediaType) {
        this(bytes, mediaType, false, null);
    }

    SanitizedImage(byte[] bytes, String mediaType, boolean userApproved, Path copy) {
        this.bytes = bytes.clone();
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.userApproved = userApproved;
        this.copy = copy;
    }

    /** A copy of the approved bytes; empty when the user never approved them or they were already cleared. */
    public synchronized Optional<byte[]> approvedBytes() {
        return userApproved && bytes.length > 0 ? Optional.of(bytes.clone()) : Optional.empty();
    }
    public String mediaType() { return mediaType; }
    public synchronized boolean userApproved() { return userApproved; }
    public synchronized boolean cleared() { return bytes.length == 0; }
    public synchronized int size() { return bytes.length; }
    /** The application-created sanitized file these bytes came from; deleted by {@link ImagePrivacyBoundary#discard}. */
    Optional<Path> copy() { return Optional.ofNullable(copy); }

    /** Releases the transient image: bytes are zeroed and approval is withdrawn. Idempotent. */
    public synchronized void clear() {
        Arrays.fill(bytes, (byte) 0);
        bytes = new byte[0];
        userApproved = false;
    }
}
