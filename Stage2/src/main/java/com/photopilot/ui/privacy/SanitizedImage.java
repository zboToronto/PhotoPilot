package com.photopilot.ui.privacy;

import java.util.Objects;

/**
 * Stage 1 UML element 42 (zero-based): {only boundary constructs; transient; never persisted}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class SanitizedImage {
    private byte[] bytes;
    private String mediaType;
    private boolean userApproved;

    SanitizedImage(byte[] bytes, String mediaType) {
        this.bytes = bytes.clone();
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    }

    public void clear() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: SanitizedImage.clear");
    }
}
