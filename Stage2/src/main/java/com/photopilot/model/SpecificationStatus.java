package com.photopilot.model;

/**
 * F04 provenance of one reviewed import field. Only {@link #EXTERNALLY_VERIFIED} values become F05
 * {@code verifiedFields}; a user-confirmed value is never treated as external evidence.
 */
public enum SpecificationStatus {
    /** Matches retrieved external specification evidence (source and retrieval time retained). */
    EXTERNALLY_VERIFIED,
    /** Entered or changed by the user during review. */
    USER_PROVIDED,
    /** Read from the image by the language model; untrusted until verified or confirmed. */
    AI_PROPOSED,
    /** No value, or sources (including the image reading) disagree; never saved as a verified value. */
    UNKNOWN_OR_CONFLICTING
}
