package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import javafx.scene.control.Button;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Lightweight application shell. No services, persistence, or feature execution. */
final class PhotoPilotWorkspace extends BorderPane {
    private final FeaturePane detail;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Map<String, ToggleButton> navigation = new LinkedHashMap<>();
    private final Label footer = FeaturePane.label("", "footer-text");
    private final ScrollPane contentScroll;
    private String selectedId;
    private long selectionVersion;

    PhotoPilotWorkspace() { this(LocalizationService.english()); }
    PhotoPilotWorkspace(LocalizationService localization) {
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        texts.useReadableFont(this);
        detail = new FeaturePane(localization);
        getStyleClass().add("workspace");
        setTop(header());
        VBox content = new VBox(detail);
        content.setAlignment(Pos.TOP_CENTER);
        content.getStyleClass().add("content-surface");
        contentScroll = scroll(content, "content-scroll");
        setCenter(contentScroll);
        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        ToggleGroup selection = new ToggleGroup();
        String previousGroup = "";
        for (FeaturePresentation feature : FeaturePresentation.all()) {
            if (!feature.group().equals(previousGroup)) {
                Label group = FeaturePane.label("", "navigation-group");
                texts.bind(group.textProperty(), () -> feature.group(localization));
                sidebar.getChildren().add(group);
                previousGroup = feature.group();
            }
            ToggleButton button = new ToggleButton();
            texts.bind(button.textProperty(), () -> feature.title(localization));
            button.getStyleClass().add("navigation-item");
            button.setId("nav-" + feature.id());
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinHeight(38);
            texts.bind(button.accessibleTextProperty(), () -> localization.text("feature.navigationAccessible", feature.id(), feature.title(localization)));
            button.setToggleGroup(selection);
            button.setOnAction(event -> {
                // Repeated clicks must not clear the current workspace selection.
                button.setSelected(true);
                selectedId = feature.id();
                selectionVersion++;
                detail.show(feature);
                contentScroll.setVvalue(0);
                texts.bind(footer.textProperty(), () -> localization.text("feature.footer", feature.id(), feature.title(localization)));
            });
            navigation.put(feature.id(), button);
            sidebar.getChildren().add(button);
        }
        ScrollPane navigationScroll = scroll(sidebar, "navigation-scroll");
        navigationScroll.setPrefWidth(250);
        navigationScroll.setMinWidth(250);
        navigationScroll.setMaxWidth(250);
        // Arrow keys follow display order across groups; Tab reaches content actions.
        var buttons = navigation.values().stream().toList();
        for (int i = 0; i < buttons.size(); i++) {
            final int index = i;
            buttons.get(i).setOnKeyPressed(event -> {
                int next = switch (event.getCode()) {
                    case DOWN -> Math.min(index + 1, buttons.size() - 1);
                    case UP -> Math.max(index - 1, 0);
                    case HOME -> 0;
                    case END -> buttons.size() - 1;
                    default -> -1;
                };
                if (next >= 0) {
                    ToggleButton target = buttons.get(next);
                    target.fire();
                    target.requestFocus();
                    double overflow = sidebar.getBoundsInLocal().getHeight() - navigationScroll.getViewportBounds().getHeight();
                    if (overflow > 0) navigationScroll.setVvalue(Math.max(0, Math.min(1,
                        (target.getBoundsInParent().getMinY() - 40) / overflow)));
                    event.consume();
                }
            });
        }
        setLeft(navigationScroll);
        Label keyboardHint = texts.label("keyboard.navigation", "footer-hint");
        HBox bottom = new HBox(16, footer, spacer(), keyboardHint);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.getStyleClass().add("footer");
        setBottom(bottom);
        navigation.get("F01").fire();
    }

    private HBox header() {
        Label brand = texts.label("app.title", "brand");
        Label subtitle = texts.label("app.subtitle", "brand-subtitle");
        Label status = texts.label("app.build", "app-status");
        Button settings = texts.button("settings.title");
        settings.setId("settings-open"); settings.getStyleClass().add("secondary-button");
        settings.setOnAction(event -> new SettingsDialog(getScene().getWindow(), localization).show());
        HBox header = new HBox(18, brand, subtitle, spacer(), status, settings);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private static Region spacer() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }

    private static ScrollPane scroll(Region content, String style) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.getStyleClass().add(style);
        return scroll;
    }

    void connectCalculator(CalculatorPane calculator) { detail.connectCalculator(calculator); }

    void connectInventory(InventoryPane inventory) { detail.connectInventory(inventory); }

    void showFeedback(String message) { detail.showFeedback(message); }
    void showFeedback(java.util.function.Supplier<String> message) { detail.showFeedback(message); }
    long selectionVersion() { return selectionVersion; }

    /** Exercises real controls in the existing opt-in graphical smoke mode. */
    void verifyNavigation() {
        if (navigation.size() != 11) throw new IllegalStateException("Expected 11 feature entries");
        for (var entry : navigation.entrySet()) {
            contentScroll.setVvalue(1);
            entry.getValue().fire();
            if (!entry.getKey().equals(selectedId) || !detail.feedbackText().isEmpty() || contentScroll.getVvalue() != 0) {
                throw new IllegalStateException("Feature selection did not reset content: " + entry.getKey());
            }
            if (!detail.statusText().equals(localization.text("feature.status." + (entry.getKey().equals("F05") || entry.getKey().equals("F08") ? "IMPLEMENTED" : "SCAFFOLDED"))) || !detail.workflowDisabled()) {
                throw new IllegalStateException("Unexpected scaffold availability: " + entry.getKey());
            }
            detail.checkStatusButton().fire();
            String expected = entry.getKey().equals("F05")
                ? detail.inventoryConnected() ? localization.text("feature.inventoryReady")
                    : localization.text("feature.noWorkflow", "F05", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F08") ? localization.text("feature.calculatorReady", localization.text("feature.status.IMPLEMENTED"))
                    : localization.text("feature.unavailable", entry.getKey());
            if (!detail.feedbackText().equals(expected)) {
                throw new IllegalStateException("Missing status feedback: " + entry.getKey());
            }
        }
        navigation.get("F01").fire();
        navigation.get("F01").fire();
        if (!navigation.get("F01").isSelected()) throw new IllegalStateException("Selection was cleared");
    }
}
