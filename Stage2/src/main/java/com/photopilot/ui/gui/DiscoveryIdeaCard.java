package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.application.DiscoveryPresentation.CheckView;
import com.photopilot.application.DiscoveryPresentation.IdeaView;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * One Guided Discovery recommendation as a structured card, built only from {@link DiscoveryPresentation#view}: status,
 * genre and number, the subject, key facts, the deterministic checks ("Checked by PhotoPilot": every finding of a check
 * that did not pass and every caution of a passing check stay visible, then the app's notes about Gemini's statements),
 * Gemini's statements ("Suggested by AI", with its own limitations) and a closed Details disclosure for the remaining
 * evidence, so nothing visible is repeated there and nothing is dropped. The card's accessible text is the complete shared idea text
 * the CLI prints. Not-feasible ideas never get continue or save actions.
 */
final class DiscoveryIdeaCard {
    private DiscoveryIdeaCard() { }

    /** The card's actions; {@code savedIn} names the target session that already holds this idea. */
    record Actions(Runnable proceed, Runnable save, Runnable dismiss, Optional<String> savedIn) { }

    static VBox build(ActivitySuggestion idea, int number, FxLocalizer texts, Actions actions) {
        LocalizationService l = texts.service();
        IdeaView view = DiscoveryPresentation.view(idea, l);
        Feasibility status = idea.feasibility();
        var card = new VBox(14); card.setId("discover-idea-" + number);
        card.getStyleClass().addAll("discover-idea", "discover-idea-" + tone(status));
        card.setAccessibleText(String.join("\n", view.lines()));

        var top = new HBox(8, status(status, view.status(), "discover-status")); top.setAlignment(Pos.CENTER_LEFT);
        if (!idea.genre().isBlank()) top.getChildren().add(text(idea.genre(), "discover-genre"));
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(spacer, text(l.text("discover.guided.ideaNumber", number), "discover-idea-number"));

        var heading = new VBox(4, text(idea.subject(), "discover-idea-title"), text(view.statusDetail(), "discover-idea-detail"));

        var facts = new CardGrid(300, 10, 2);
        facts.getChildren().add(fact(AppIcons.PIN, view.where()));
        facts.getChildren().add(fact(AppIcons.HISTORY, view.when()));
        view.distance().ifPresent(line -> facts.getChildren().add(fact(AppIcons.ROUTE, line)));
        view.light().ifPresent(line -> facts.getChildren().add(fact(AppIcons.SUN, line)));
        view.equipment().ifPresent(line -> facts.getChildren().add(fact(AppIcons.CAMERA_OUTLINE, line)));

        // The key facts are Gemini's proposal (the distance is computed from it); the checks below say what was verified.
        var sourceText = text(l.text("discover.guided.factsSource"), "discover-section-caption"); HBox.setHgrow(sourceText, Priority.ALWAYS);
        var source = new HBox(8, AppIcons.slot(AppIcons.SPARKLES, "discover-caption-icon", 16), sourceText);
        source.setAlignment(Pos.CENTER_LEFT); source.setId("discover-facts-source-" + number);

        var sections = new CardGrid(340, 14, 2);
        sections.getChildren().addAll(checked(view, l, number), suggested(view, l, number));

        card.getChildren().addAll(top, heading, new VBox(8, source, facts), sections);
        details(view, l, number).ifPresent(card.getChildren()::add);
        card.getChildren().add(actions(idea, status, number, texts, actions));
        return card;
    }

    /**
     * "Checked by PhotoPilot": the verification count, one row per check and, visibly below each row, the findings that
     * must stay in view ({@link CheckView#shown}): every finding of a check that did not pass, and the cautions of a
     * passing check (forecast warnings, travel limitations), each with a warning mark in the warn tone.
     */
    private static VBox checked(IdeaView view, LocalizationService l, int number) {
        var box = new VBox(10); box.getStyleClass().addAll("discover-section", "discover-checked"); box.setId("discover-checked-" + number);
        box.getChildren().addAll(sectionHeading(AppIcons.SHIELD, l.text("discover.guided.checkedTitle"), "discover-section-icon"),
            text(DiscoveryPresentation.verification(view, l), "discover-section-caption"));
        for (CheckView check : view.checks()) {
            var name = text(check.name(), "discover-check-name"); name.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(name, Priority.ALWAYS);
            var row = new HBox(8, mark(check.status(), "discover-check-icon"), name, status(check.status(), check.statusText(), "discover-check-status"));
            row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("discover-check"); row.setAccessibleText(check.line());
            box.getChildren().add(row);
            if (check.shown().isEmpty()) continue;
            boolean passed = check.status() == Feasibility.FEASIBLE;
            var findings = new VBox(4); findings.setId("discover-findings-" + number + "-" + check.criterion());
            findings.getStyleClass().addAll("discover-findings", passed ? "discover-findings-caution" : "discover-findings-" + tone(check.status()));
            for (String finding : check.shown()) findings.getChildren().add(passed ? caution(finding, "discover-finding") : bullet(finding, "discover-finding"));
            box.getChildren().add(findings);
        }
        // PhotoPilot's own notes about Gemini's statements (dropped statements, unverified place): deterministic, so here.
        for (String note : view.notes()) {
            var row = new HBox(8, AppIcons.slot(AppIcons.INFO, "discover-note-icon", 16), text(note, "discover-note"));
            row.setAlignment(Pos.TOP_LEFT); HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
            box.getChildren().add(row);
        }
        return box;
    }

    /** "Suggested by AI": Gemini's reasoning and its own limitations. */
    private static VBox suggested(IdeaView view, LocalizationService l, int number) {
        var box = new VBox(10); box.getStyleClass().addAll("discover-section", "discover-suggested"); box.setId("discover-suggested-" + number);
        box.getChildren().addAll(sectionHeading(AppIcons.SPARKLES, l.text("discover.guided.aiTitle"), "discover-section-icon"),
            text(l.text("discover.guided.aiCaption"), "discover-section-caption"), text(view.rationale(), "discover-text"));
        view.limitationsTitle().ifPresent(title -> {
            box.getChildren().add(text(title, "discover-subheading"));
            for (String item : view.limitations()) box.getChildren().add(bullet(item, "discover-text"));
        });
        return box;
    }

    /**
     * Closed by default: the setting, the remaining evidence of passed checks ({@link CheckView#details}; their cautions
     * are already shown under the check rows) and the ranking reasons.
     */
    private static Optional<TitledPane> details(IdeaView view, LocalizationService l, int number) {
        var content = new VBox(8);
        view.genre().ifPresent(line -> content.getChildren().add(text(line, "discover-text")));
        view.setting().ifPresent(line -> content.getChildren().add(text(line, "discover-text")));
        for (CheckView check : view.checks()) {
            if (check.details().isEmpty()) continue;
            content.getChildren().add(text(check.line(), "discover-subheading"));
            for (String finding : check.details()) content.getChildren().add(bullet(finding, "discover-text"));
        }
        view.rank().ifPresent(line -> content.getChildren().add(text(line, "discover-caption")));
        if (content.getChildren().isEmpty()) return Optional.empty();
        var pane = new TitledPane(l.text("discover.guided.details"), content);
        pane.setId("discover-idea-details-" + number); pane.setExpanded(false); pane.setAnimated(false);
        pane.getStyleClass().add("discover-disclosure");
        return Optional.of(pane);
    }

    private static FlowPane actions(ActivitySuggestion idea, Feasibility status, int number, FxLocalizer texts, Actions actions) {
        var l = texts.service();
        var row = new FlowPane(10, 10); row.getStyleClass().add("discover-idea-actions"); row.setAlignment(Pos.CENTER_LEFT);
        if (status != Feasibility.INFEASIBLE) {
            var proceed = button(texts, "discover.continue", "discover-continue-" + number, AppIcons.ARROW_RIGHT, status == Feasibility.FEASIBLE);
            proceed.setAccessibleText(l.text("discover.guided.actionFor", l.text("discover.continue"), idea.subject()));
            proceed.setOnAction(event -> actions.proceed().run());
            Button save;
            if (actions.savedIn().isPresent()) {
                save = button(texts, "discover.guided.savedState", "discover-save-" + number, AppIcons.CHECK_CIRCLE, false);
                save.setDisable(true);
                save.setAccessibleText(l.text("discover.guided.savedIn", idea.subject(), actions.savedIn().get()));
            } else {
                save = button(texts, "discover.guided.save", "discover-save-" + number, AppIcons.BOOKMARK, false);
                save.setAccessibleText(l.text("discover.guided.actionFor", l.text("discover.guided.save"), idea.subject()));
                save.setOnAction(event -> actions.save().run());
            }
            row.getChildren().addAll(proceed, save);
        }
        var dismiss = button(texts, "discover.dismiss", "discover-dismiss-" + number, AppIcons.DISMISS, false);
        dismiss.getStyleClass().add("discover-quiet");
        dismiss.setAccessibleText(l.text("discover.guided.actionFor", l.text("discover.dismiss"), idea.subject()));
        dismiss.setOnAction(event -> actions.dismiss().run());
        row.getChildren().add(dismiss);
        return row;
    }

    static Button button(FxLocalizer texts, String key, String id, String icon, boolean primary) {
        var button = texts.button(key); button.setId(id); button.setMnemonicParsing(false);
        button.getStyleClass().addAll(primary ? "primary-button" : "secondary-button", "discover-action");
        button.setGraphic(AppIcons.slot(icon, primary ? "discover-button-icon" : "discover-button-icon-secondary", 16));
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }
    /** A status word with its own shape (check, question mark, cross): never colour alone. */
    static HBox status(Feasibility status, String word, String style) {
        var chip = new HBox(6, mark(status, "discover-status-icon"), text(word, "discover-status-text"));
        chip.setAlignment(Pos.CENTER_LEFT); chip.getStyleClass().addAll(style, "discover-tone-" + tone(status)); chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }
    static StackPane mark(Feasibility status, String style) {
        String shape = switch (status) { case FEASIBLE -> AppIcons.CHECK_CIRCLE; case UNVERIFIED -> AppIcons.QUESTION; case INFEASIBLE -> AppIcons.CROSS_CIRCLE; };
        var slot = AppIcons.slot(shape, style, 16); slot.getStyleClass().add("discover-tone-" + tone(status)); return slot;
    }
    static String tone(Feasibility status) { return switch (status) { case FEASIBLE -> "ok"; case UNVERIFIED -> "warn"; case INFEASIBLE -> "bad"; }; }
    static HBox sectionHeading(String icon, String title, String iconStyle) {
        var row = new HBox(8, AppIcons.slot(icon, iconStyle, 18), text(title, "discover-section-heading")); row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    private static HBox fact(String icon, String line) {
        var label = text(line, "discover-fact"); HBox.setHgrow(label, Priority.ALWAYS); label.setMaxWidth(Double.MAX_VALUE);
        var row = new HBox(8, AppIcons.slot(icon, "discover-fact-icon", 18), label); row.setAlignment(Pos.TOP_LEFT); row.setMinWidth(0);
        return row;
    }
    static HBox bullet(String line, String style) {
        var dot = new Region(); dot.getStyleClass().add("discover-bullet"); dot.setMinSize(5, 5); dot.setPrefSize(5, 5); dot.setMaxSize(5, 5);
        var box = new StackPane(dot); box.setMinSize(10, 18); box.setPrefSize(10, 18); box.setMaxSize(10, 18);
        var label = text(line, style); HBox.setHgrow(label, Priority.ALWAYS); label.setMaxWidth(Double.MAX_VALUE);
        var row = new HBox(6, box, label); row.setAlignment(Pos.TOP_LEFT); row.setMinWidth(0);
        return row;
    }
    /** A caution line: the warning shape in the warn tone, then the text (never colour alone). */
    static HBox caution(String line, String style) {
        var label = text(line, style); HBox.setHgrow(label, Priority.ALWAYS); label.setMaxWidth(Double.MAX_VALUE);
        var row = new HBox(6, AppIcons.slot(AppIcons.WARNING, "discover-caution-icon", 16), label); row.setAlignment(Pos.TOP_LEFT); row.setMinWidth(0);
        row.getStyleClass().add("discover-caution");
        return row;
    }
    static Label text(String value, String style) { var label = FeaturePane.label(value, style); label.setMinWidth(0); return label; }
}
