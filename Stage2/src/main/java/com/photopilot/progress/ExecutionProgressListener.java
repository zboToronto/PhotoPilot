package com.photopilot.progress;

/**
 * Observer of operation progress. Called on the thread that changed the operation (usually a worker thread), in
 * publication order; GUI listeners hand the event to their UI thread, CLI listeners print it. A listener must not block
 * and must not call back into the operation.
 */
@FunctionalInterface
public interface ExecutionProgressListener {
    void onProgress(ExecutionProgressEvent event);
}
