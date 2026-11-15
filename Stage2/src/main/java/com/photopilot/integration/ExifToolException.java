package com.photopilot.integration;

/** A failure of the bundled ExifTool process itself (not of a photo it read). */
public final class ExifToolException extends Exception {
    public enum Kind {
        /** The bundled package is missing, incomplete or not for this operating system. */
        UNAVAILABLE,
        /** The process could not be started. */
        STARTUP,
        /** No complete answer within the request timeout; the process was stopped. */
        TIMEOUT,
        /** The process ended or its pipe broke during a request. */
        TERMINATED,
        /** The owner closed ExifTool (user switch or application exit). */
        CLOSED
    }
    private final Kind kind;
    public ExifToolException(Kind kind, String message) { super(message); this.kind = kind; }
    public ExifToolException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
