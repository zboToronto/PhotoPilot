package com.photopilot.repository;

/**
 * Whether a failure was the normal cross-process writer contention of a local store ({@link AtomicFiles.Busy}: another
 * PhotoPilot window or command held the lock), as opposed to a damaged store or a configuration problem. Callers use it
 * only to word the message (Stage2OptRe1 C31: the CLI reported a busy store as a "configuration error"); the failed
 * operation still wrote nothing.
 */
public final class StorageBusy {
    private StorageBusy() { }

    public static boolean causedBy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
            if (cause instanceof AtomicFiles.Busy) return true;
        return false;
    }
}
