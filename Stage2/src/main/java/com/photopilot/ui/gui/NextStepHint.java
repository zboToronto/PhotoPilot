package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.progress.NextStep;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * "Next: ..." line for guidance before an operation (a missing prerequisite) with an Open button when the step lives on
 * another page. The step comes from {@link com.photopilot.application.NextSteps}; this only presents it.
 */
final class NextStepHint extends HBox {
    private final LocalizationService localization;
    private final Label text = FeaturePane.label("", "progress-next");
    private final Button open = new Button();
    private Optional<NextStep> step = Optional.empty();
    /** "Open {page}" for a prerequisite elsewhere; "Continue to {page}" for the session's next milestone. */
    private String action = "progress.open";
    /** The session a continuation belongs to; following it opens the page with that session. */
    private java.util.UUID session;

    NextStepHint(LocalizationService localization, String id) {
        super(10);
        this.localization = localization;
        setId(id); setAlignment(Pos.CENTER_LEFT); getStyleClass().add("next-step-hint");
        text.setId(id + "-text"); HBox.setHgrow(text, Priority.ALWAYS); text.setMaxWidth(Double.MAX_VALUE);
        open.setId(id + "-open"); open.getStyleClass().add("secondary-button"); open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(event -> step.flatMap(NextStep::target).ifPresent(target -> open.fireEvent(new NavigationRequest(target, session))));
        getChildren().addAll(text, open);
        show(Optional.empty());
    }

    void show(Optional<NextStep> next) {
        step = next; action = "progress.open"; session = null;
        render();
    }
    /** {@code sessionId}'s recommended next milestone, offered as a continuation for that session. */
    void continueWith(Optional<NextStep> next, java.util.UUID sessionId) {
        step = next; action = "progress.continue"; session = sessionId;
        render();
    }
    /** Re-renders in the current language. */
    void render() {
        boolean visible = step.isPresent();
        setVisible(visible); setManaged(visible);
        text.setText(step.map(s -> localization.text("progress.next", s.message().render(localization))).orElse(""));
        var target = step.flatMap(NextStep::target);
        open.setVisible(target.isPresent()); open.setManaged(target.isPresent());
        target.ifPresent(page -> open.setText(localization.text(action, localization.text(NavigationRequest.titleKey(page)))));
    }
    String text() { return text.getText(); }
}
