package com.photopilot.ui.gui;

import com.photopilot.model.PhotographySession;
import com.photopilot.workflow.SessionWorkflow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;

/**
 * The session chooser of a session page (Shooting Requirements ... Post-Shoot Review) tied to the window's current
 * session ({@link SessionWorkflowModel#current()}). After a list load it selects, in order: the session an open draft
 * belongs to, the current session when this page lists it, and otherwise the page's previous selection or the first
 * listed session. When the current session exists but this page cannot list it (an editing page and a completed
 * session, or the review page and a session not shot yet) nothing is selected and the page says why, instead of
 * silently showing another session. A choice the user makes in the chooser (or an explicit hand-off by id) makes that
 * session the chosen one; selections made by a load never change it.
 *
 * <p>Below the chooser one secondary line gives the shown session's place in the workflow ("Ready to shoot · 57% · 4 of
 * 7 milestones · Next: Photography Plan"), read from the window's workflow model. It uses the page's own localizer.
 */
final class SessionChooser {
    private final ComboBox<PhotographySession> box;
    private final FxLocalizer texts;
    private final Label context = FeaturePane.label("", "session-context");
    private SessionWorkflowModel sessions;
    private Optional<SessionWorkflow> unavailable = Optional.empty();
    private boolean selecting;

    SessionChooser(ComboBox<PhotographySession> box, FxLocalizer texts) {
        this.box = Objects.requireNonNull(box); this.texts = Objects.requireNonNull(texts);
        context.setId(box.getId() + "-context");
        box.valueProperty().addListener((property, old, value) -> {
            if (value != null) unavailable = Optional.empty();
            if (!selecting && value != null && sessions != null) sessions.activate(value.id());
            render();
        });
        render();
    }

    /** Joins the window's session context; until then (or without a window, as in page tests) the page behaves as before. */
    void use(SessionWorkflowModel sessions) {
        this.sessions = sessions;
        sessions.listen(this::render);
        render();
    }

    /**
     * Selects among {@code eligible} after a list load (see the class comment). {@code kept} is the session of an open
     * draft (or an explicit request) or null; with nothing to select the value is kept unless {@code clearWhenEmpty}.
     */
    void select(List<PhotographySession> eligible, UUID kept, boolean clearWhenEmpty) {
        UUID previous = box.getValue() == null ? null : box.getValue().id();
        var current = sessions == null ? Optional.<SessionWorkflow>empty() : sessions.current();
        selecting = true;
        try {
            unavailable = Optional.empty();
            if (pick(eligible, kept)) return;
            if (current.isPresent()) {
                if (pick(eligible, current.get().sessionId())) return;
                unavailable = current; box.setValue(null); return;
            }
            if (pick(eligible, previous)) return;
            if (!eligible.isEmpty()) box.setValue(eligible.getFirst());
            else if (clearWhenEmpty) box.setValue(null);
        } finally { selecting = false; render(); }
    }
    private boolean pick(List<PhotographySession> eligible, UUID wanted) {
        if (wanted == null) return false;
        var match = eligible.stream().filter(session -> session.id().equals(wanted)).findFirst();
        match.ifPresent(box::setValue);
        return match.isPresent();
    }

    /** The workflow line (or the reason the current session is not shown); placed below the chooser by the page. */
    Label context() { return context; }
    ObservableValue<PhotographySession> selected() { return box.valueProperty(); }

    private void render() {
        var service = texts.service();
        if (unavailable.isPresent()) {
            var missing = unavailable.get();
            texts.bind(context.textProperty(), () -> service.text("workflow.chooser.unavailable", missing.title(), service.text("session.state." + missing.lifecycle())));
            show(true); return;
        }
        var shown = Optional.ofNullable(box.getValue()).flatMap(value -> sessions == null ? Optional.empty() : sessions.workflow(value.id()));
        if (shown.isEmpty()) { texts.bind(context.textProperty(), () -> ""); show(false); return; }
        var workflow = shown.get();
        texts.bind(context.textProperty(), () -> WorkflowTexts.context(workflow, service));
        show(true);
    }
    private void show(boolean visible) { context.setVisible(visible); context.setManaged(visible); }
}
