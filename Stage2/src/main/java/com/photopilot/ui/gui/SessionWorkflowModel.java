package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.repository.StoreChanges;
import com.photopilot.workflow.SessionWorkflow;
import java.util.*;
import javafx.application.Platform;

/**
 * The active user's session workflow progress in this window: each session's {@link SessionWorkflow} from the shared
 * SessionWorkflowService, and the active session. The sidebar, the page context bar, Home and the session list all
 * read this one representation; none computes progress itself.
 *
 * <p>Reads: {@link #refresh()} runs one overview (one sessions read, one inventory read) on the window's ordered local
 * lane, when Home or the session list is shown (which also picks up changes made by another process, e.g. the CLI)
 * and after an inventory change. Writes of this process are followed as {@link StoreChanges} announces them: a saved
 * session is evaluated on the writing thread and applied on the FX thread before the saving task's own completion, so
 * a page's "next" guidance already reflects the save. Nothing polls; everything else runs on the FX thread.
 *
 * <p>The chosen session lives in memory for this window only. It is set only by explicit choices (resume, open or
 * create in the session list, choosing a session on a session page, a hand-off from Inspiration) and cleared when that
 * session is deleted; without one the latest unfinished session is current (see {@link #current()}). A page that
 * cannot show the current session (e.g. a completed session on an editing page) says so instead of showing another.
 */
final class SessionWorkflowModel implements AutoCloseable {
    private final PhotoPilotFacade app;
    private final UiOperations operations;
    private final Map<UUID, SessionWorkflow> workflows = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final AutoCloseable subscription;
    private UUID active;
    private long generation;
    private boolean loading, again, loaded, closed;

    SessionWorkflowModel(PhotoPilotFacade app, UiOperations operations) {
        this.app = Objects.requireNonNull(app, "app"); this.operations = Objects.requireNonNull(operations, "operations");
        subscription = app.changes().listen(this::changed);
    }

    /** Runs on the thread that wrote; only immutable results cross to the FX thread. */
    private void changed(StoreChanges.Change change) {
        if (closed) return;
        switch (change.kind()) {
            case SESSION_SAVED -> {
                var workflow = app.workflow().evaluate(change.session().orElseThrow());
                Platform.runLater(() -> apply(workflow));
            }
            case SESSION_DELETED -> { UUID id = change.sessionId().orElseThrow(); Platform.runLater(() -> remove(id)); }
            case INVENTORY_SAVED -> Platform.runLater(this::refresh);
        }
    }

    /** Re-reads every session's progress; a request while one is loading is folded into one more read afterwards. */
    void refresh() {
        if (closed) return;
        if (loading) { again = true; return; }
        loading = true; long ticket = ++generation;
        operations.run(() -> app.workflow().overview(), result -> {
            loading = false;
            if (closed) return;
            // A save announced while this read ran is newer than the read; the read is dropped and repeated.
            if (result.isSuccess() && ticket == generation) {
                workflows.clear();
                result.value().orElseThrow().forEach(workflow -> workflows.put(workflow.sessionId(), workflow));
                loaded = true;
                if (active != null && !workflows.containsKey(active)) active = null;
                changed();
            }
            if (again || ticket != generation) { again = false; refresh(); }
        });
    }

    private void apply(SessionWorkflow workflow) {
        if (closed) return;
        var known = workflows.get(workflow.sessionId());
        // Announcements from two lanes can arrive out of order; the later stored version wins.
        if (known != null && known.session().updatedAt().isAfter(workflow.session().updatedAt())) return;
        generation++;
        if (loading) again = true;
        workflows.put(workflow.sessionId(), workflow);
        changed();
    }

    private void remove(UUID id) {
        if (closed) return;
        generation++;
        if (loading) again = true;
        boolean removed = workflows.remove(id) != null, wasActive = id.equals(active);
        if (wasActive) active = null;
        if (removed || wasActive) changed();
    }

    /** Explicitly chosen session (see the class comment); unknown ids are loaded first. */
    void activate(UUID id) {
        if (closed || id == null || id.equals(active)) return;
        active = id;
        if (!workflows.containsKey(id)) refresh();
        changed();
    }
    /**
     * The session the window works on: the explicitly chosen one while it exists, otherwise the most recently changed
     * unfinished (Draft or Planned) session, otherwise none. The sidebar, Home and the session pages all use it.
     */
    Optional<SessionWorkflow> current() {
        var chosen = active == null ? null : workflows.get(active);
        if (chosen != null) return Optional.of(chosen);
        return workflows.values().stream().filter(w -> w.lifecycle().equals("DRAFT") || w.lifecycle().equals("PLANNED"))
            .max(Comparator.comparing(w -> w.session().updatedAt()));
    }
    Optional<UUID> currentId() { return current().map(SessionWorkflow::sessionId); }
    /** Whether the current session was chosen explicitly (not the latest unfinished one by default). */
    boolean chosen() { return active != null && workflows.containsKey(active); }
    Optional<SessionWorkflow> workflow(UUID id) { return id == null ? Optional.empty() : Optional.ofNullable(workflows.get(id)); }
    /** Every session's progress in the repository's order (latest shooting date first). */
    List<SessionWorkflow> all() { return List.copyOf(workflows.values()); }
    /** Whether a complete overview has been read at least once. */
    boolean loaded() { return loaded; }

    /** {@code listener} runs on the FX thread after every change, until the model is closed. */
    void listen(Runnable listener) { listeners.add(Objects.requireNonNull(listener)); }
    private void changed() { for (var listener : List.copyOf(listeners)) listener.run(); }

    @Override public void close() {
        if (closed) return;
        closed = true; listeners.clear();
        try { subscription.close(); } catch (Exception ignored) { /* the subject only removes the listener */ }
    }
}
