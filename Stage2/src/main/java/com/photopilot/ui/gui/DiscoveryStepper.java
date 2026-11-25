package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryGuide.Stage;
import com.photopilot.i18n.LocalizationService;
import java.util.*;
import java.util.function.Consumer;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;

/**
 * Guided Discovery progress indicator: one button per stage (a numbered circle, or a check mark once completed, and the
 * stage name) joined by connector lines. Each step names its state in words for assistive technology and keeps a shape
 * per state, so nothing depends on colour. When the row is too narrow for every name, only the current step keeps its
 * name; the others keep their circle, tooltip and accessible text. It holds no navigation rule: the page decides which
 * steps are enabled and what a click does.
 */
final class DiscoveryStepper extends HBox {
    enum State { CURRENT, COMPLETED, UPCOMING }
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current"), COMPLETED = PseudoClass.getPseudoClass("completed"),
        UPCOMING = PseudoClass.getPseudoClass("upcoming"), DONE = PseudoClass.getPseudoClass("done");
    private record Step(Button button, Label number, Node check, VBox text, Label title, Tooltip tip) { }
    private final LocalizationService localization;
    private final Map<Stage, Step> steps = new EnumMap<>(Stage.class);
    private final List<Region> lines = new ArrayList<>();
    private final Map<Stage, State> states = new EnumMap<>(Stage.class);
    private Stage current = Stage.LOCATION_TIME;

    DiscoveryStepper(FxLocalizer texts, Consumer<Stage> select) {
        localization = texts.service();
        getStyleClass().add("discover-stepper"); setId("discover-stepper"); setAlignment(Pos.CENTER_LEFT); setMinWidth(0);
        for (var stage : Stage.values()) {
            if (stage.ordinal() > 0) {
                var line = new Region(); line.getStyleClass().add("discover-step-line");
                line.setMinSize(12, 2); line.setPrefSize(64, 2); line.setMaxSize(Double.MAX_VALUE, 2);
                HBox.setHgrow(line, Priority.ALWAYS); lines.add(line); getChildren().add(line);
            }
            var number = new Label(String.valueOf(stage.number())); number.getStyleClass().add("discover-step-number");
            var check = AppIcons.outline(AppIcons.CHECK, "discover-step-check");
            var circle = new StackPane(number, check); circle.getStyleClass().add("discover-step-circle");
            circle.setMinSize(30, 30); circle.setPrefSize(30, 30); circle.setMaxSize(30, 30);
            Label caption = texts.label("discover.step.number", "discover-step-caption", stage.number());
            Label title = texts.label(stage.titleKey(), "discover-step-title");
            caption.setWrapText(false); title.setWrapText(false); caption.setMinWidth(Region.USE_PREF_SIZE); title.setMinWidth(Region.USE_PREF_SIZE);
            var text = new VBox(1, caption, title); text.setAlignment(Pos.CENTER_LEFT);
            var graphic = new HBox(10, circle, text); graphic.setAlignment(Pos.CENTER_LEFT);
            var button = new Button(); button.setGraphic(graphic); button.getStyleClass().add("discover-step");
            button.setId("discover-step-" + stage.number()); button.setMnemonicParsing(false); button.setMinWidth(Region.USE_PREF_SIZE);
            var tip = new Tooltip(); button.setTooltip(tip);
            button.setOnAction(event -> select.accept(stage));
            steps.put(stage, new Step(button, number, check, text, title, tip));
            states.put(stage, stage == Stage.LOCATION_TIME ? State.CURRENT : State.UPCOMING);
            getChildren().add(button);
        }
        texts.onRefresh(() -> { describe(); fit(); });
        widthProperty().addListener((property, old, width) -> fit());
    }

    /**
     * Shows {@code current} and each stage's state; {@code enabled} are the steps a click may open. {@code done} are the
     * stages already completed (validated), also the one being shown again: the line after a done stage is drawn as done,
     * so returning to an earlier step never breaks the progress line.
     */
    void update(Stage current, Map<Stage, State> next, Set<Stage> enabled, Set<Stage> done) {
        this.current = current; states.putAll(next);
        steps.forEach((stage, step) -> {
            State state = states.get(stage);
            step.button().pseudoClassStateChanged(CURRENT, state == State.CURRENT);
            step.button().pseudoClassStateChanged(COMPLETED, state == State.COMPLETED);
            step.button().pseudoClassStateChanged(UPCOMING, state == State.UPCOMING);
            step.button().setDisable(!enabled.contains(stage));
            step.number().setVisible(state != State.COMPLETED); step.check().setVisible(state == State.COMPLETED);
        });
        for (int i = 0; i < lines.size(); i++) {
            var from = Stage.values()[i];
            lines.get(i).pseudoClassStateChanged(DONE, done.contains(from) || states.get(from) == State.COMPLETED);
        }
        describe(); fit();
    }
    State state(Stage stage) { return states.get(stage); }
    /** Whether the connector after {@code stage} is drawn as done (tests). */
    boolean lineDone(Stage stage) { return lines.get(stage.ordinal()).getPseudoClassStates().contains(DONE); }
    Button button(Stage stage) { return steps.get(stage).button(); }

    private void describe() {
        steps.forEach((stage, step) -> {
            String text = localization.text("discover.step.accessible", stage.number(), Stage.values().length, localization.text(stage.titleKey()),
                localization.text("discover.step.state." + states.get(stage).name().toLowerCase(Locale.ROOT)));
            step.button().setAccessibleText(text); step.tip().setText(text);
        });
    }
    /** Keeps every step name while they fit; otherwise only the current step's name (no clipping, no wrapping). */
    private void fit() {
        double width = getWidth();
        if (width <= 0) return;
        double needed = lines.size() * 28;
        for (var step : steps.values()) needed += 30 + 10 + 24 + Math.max(step.title().prefWidth(-1), 0);
        boolean narrow = width < needed;
        steps.forEach((stage, step) -> { boolean shown = !narrow || stage == current; step.text().setVisible(shown); step.text().setManaged(shown); });
    }
}
