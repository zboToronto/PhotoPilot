package com.photopilot.ui.gui;

import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import com.photopilot.workflow.SessionWorkflow;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * The sidebar's Active Session section: a compact summary (session name, phase, a thin milestone bar with its
 * percentage, "4 of 7 milestones", the recommended next milestone) above the seven milestone rows. Each row keeps its
 * page title as text and shows a step badge in the icon slot: the step number while it is not started, otherwise a
 * shape per status. The current page keeps the normal selected fill; the recommended next milestone's badge turns
 * from an outline ring into a solid disc (and its accessible text says "recommended next step"), so the two are never
 * confused and neither relies on colour alone. Blocked and not-applicable rows are muted but stay clickable: the page explains what
 * comes first. Without a current session the summary is an empty state and the rows show neutral numbered badges.
 * It uses the workspace's localizer and draws only from {@link SessionWorkflowModel}.
 */
final class SessionSidebar {
    private static final PseudoClass RECOMMENDED = PseudoClass.getPseudoClass("recommended");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");
    private final FxLocalizer texts;
    private final VBox block = new VBox(2);
    private final Label title = FeaturePane.label("", "sidebar-session-title");
    private final Label phase = FeaturePane.label("", "sidebar-session-line");
    private final ProgressBar bar = WorkflowGlyphs.bar("sidebar-session-bar");
    private final Label count = FeaturePane.label("", "sidebar-session-line");
    private final Label next = FeaturePane.label("", "sidebar-session-next");
    private final Label empty = FeaturePane.label("", "sidebar-session-line");
    private final Button choose = new Button();
    private final Map<Milestone, Row> rows = new EnumMap<>(Milestone.class);

    private record Row(ToggleButton button, StackPane badge) { }

    SessionSidebar(FxLocalizer texts, Runnable chooseSession) {
        this.texts = texts;
        block.setId("sidebar-session"); block.getStyleClass().add("sidebar-session");
        for (var line : new Label[] { title, phase, count, next, empty }) single(line);
        title.setId("sidebar-session-title"); phase.setId("sidebar-session-phase"); count.setId("sidebar-session-count");
        next.setId("sidebar-session-next"); empty.setId("sidebar-session-empty");
        texts.text(empty.textProperty(), "workflow.sidebar.empty");
        choose.setId("sidebar-session-choose"); choose.getStyleClass().add("sidebar-link"); choose.setMinWidth(0); choose.setMaxWidth(Double.MAX_VALUE);
        choose.setAlignment(Pos.CENTER_LEFT); choose.setTextOverrun(OverrunStyle.ELLIPSIS); FxLocalizer.tooltipWhenClipped(choose);
        texts.text(choose.textProperty(), "workflow.sidebar.choose"); choose.setOnAction(event -> chooseSession.run());
        bar.setMinWidth(0); VBox.setMargin(bar, new javafx.geometry.Insets(4, 0, 3, 0));
        render(Optional.empty());
    }

    /** One line, shortened with an ellipsis (the full text stays readable as a tooltip and as the accessible name). */
    private static void single(Label label) {
        label.setWrapText(false); label.setMinWidth(0); label.setMaxWidth(Double.MAX_VALUE); label.setTextOverrun(OverrunStyle.ELLIPSIS);
        FxLocalizer.tooltipWhenClipped(label);
    }

    Region block() { return block; }

    /** Gives a milestone's sidebar row its step badge (the row's text stays the page title). */
    void row(Milestone milestone, ToggleButton button) {
        var badge = WorkflowGlyphs.badge(milestone.number());
        button.setGraphic(badge); button.getStyleClass().add("milestone-item");
        rows.put(milestone, new Row(button, badge));
    }

    /** Redraws the summary and the rows for {@code workflow} (empty: no current session). */
    void render(Optional<SessionWorkflow> workflow) {
        var service = texts.service();
        if (workflow.isEmpty()) {
            block.getChildren().setAll(empty, choose);
            block.pseudoClassStateChanged(MUTED, true);
            for (var entry : rows.entrySet()) {
                var row = entry.getValue(); var milestone = entry.getKey();
                WorkflowGlyphs.set(row.badge(), Optional.empty());
                row.button().pseudoClassStateChanged(RECOMMENDED, false); row.button().pseudoClassStateChanged(MUTED, false);
                row.button().setTooltip(null);
                texts.bind(row.button().accessibleTextProperty(), () -> service.text("workflow.row.plain", step(milestone), service.text(milestone.titleKey())));
            }
            return;
        }
        var current = workflow.get();
        block.pseudoClassStateChanged(MUTED, false);
        block.getChildren().setAll(title, phase, bar, count, next);
        title.setText(current.title());
        texts.bind(title.accessibleTextProperty(), () -> service.text("workflow.sidebar.session", current.title()));
        texts.bind(phase.textProperty(), () -> WorkflowTexts.phase(current, service));
        bar.setProgress(current.percent() / 100.0);
        texts.bind(bar.accessibleTextProperty(), () -> current.progress().render(service));
        texts.bind(count.textProperty(), () -> current.progress().render(service));
        var recommended = current.next();
        texts.bind(next.textProperty(), () -> WorkflowTexts.next(current, service));
        for (var entry : rows.entrySet()) {
            var milestone = entry.getKey(); var row = entry.getValue(); var state = current.state(milestone);
            boolean isNext = recommended.filter(m -> m.milestone() == milestone).isPresent();
            WorkflowGlyphs.set(row.badge(), Optional.of(state.status()));
            row.button().pseudoClassStateChanged(RECOMMENDED, isNext);
            row.button().pseudoClassStateChanged(MUTED, state.status() == MilestoneStatus.BLOCKED || state.status() == MilestoneStatus.NOT_APPLICABLE);
            var tooltip = new Tooltip(); tooltip.setWrapText(true); tooltip.setMaxWidth(300);
            texts.bind(tooltip.textProperty(), () -> service.text("workflow.row.tooltip", service.text(milestone.titleKey()),
                service.text(state.status().labelKey()), state.detail().render(service)));
            row.button().setTooltip(tooltip);
            texts.bind(row.button().accessibleTextProperty(), () -> {
                String text = service.text("workflow.row.accessible", step(milestone), service.text(milestone.titleKey()),
                    service.text(state.status().labelKey()), state.detail().render(service));
                return isNext ? service.text("workflow.row.recommended", text) : text;
            });
        }
    }

    private static String step(Milestone milestone) { return String.format("%02d", milestone.number()); }
}
