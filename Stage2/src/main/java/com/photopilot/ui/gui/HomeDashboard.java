package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Workspace launcher: actual feature IDs and availability, with no demo records or duplicate services. */
final class HomeDashboard extends VBox {
    private static final double GAP = 12, NARROW = 760, CARD_HEIGHT = 124, NARROW_CARD_HEIGHT = 152;
    private final FxLocalizer texts;
    private final Label greeting = FeaturePane.label("", "page-title");
    private final StackPane avatar = new StackPane();
    HomeDashboard(LocalizationService language, Consumer<String> navigate) {
        super(16); setId("home-dashboard"); getStyleClass().add("page"); setMaxWidth(1180); setMinWidth(0);
        texts = new FxLocalizer(this, language);
        var identity = new VBox(0, greeting, texts.label("home.identity", "page-subtitle")); identity.setMinWidth(0);
        var hero = new HBox(14, avatar, identity); hero.setAlignment(Pos.CENTER_LEFT); hero.getStyleClass().add("surface-panel");
        var catalog = FeaturePresentation.all();
        long available = catalog.stream().filter(HomeDashboard::ready).count();
        var status = new VBox(4, texts.label("home.statusTitle", "group-title"), texts.label("home.statusValue", "status-value", available, catalog.size()),
            texts.label("home.statusDetail", "secondary-text"));
        status.getStyleClass().add("status-tile");
        getChildren().addAll(hero,
            section("home.readySection", card("equipment", "F05", catalog, navigate), card("sessions", "F01", catalog, navigate), card("calculator", "F08", catalog, navigate)),
            section("home.plannedSection", card("planning", "F10", catalog, navigate), card("assistant", "F02", catalog, navigate), status));
    }
    private static boolean ready(FeaturePresentation feature) { return feature.status() == FeaturePresentation.Status.IMPLEMENTED; }
    /** Equal columns at every width, so no card is left oversized or alone in a row. */
    private VBox section(String heading, Region... cells) {
        var row = new HBox(GAP, cells); row.setMinWidth(0);
        for (Region cell : cells) {
            HBox.setHgrow(cell, Priority.ALWAYS); cell.setPrefWidth(0); cell.setMinWidth(0); cell.setMaxWidth(Double.MAX_VALUE);
            cell.prefHeightProperty().bind(Bindings.when(widthProperty().lessThan(NARROW)).then(NARROW_CARD_HEIGHT).otherwise(CARD_HEIGHT)); cell.minHeightProperty().bind(cell.prefHeightProperty());
        }
        var section = new VBox(GAP, texts.label(heading, "group-title"), row); section.getStyleClass().add("surface-panel"); return section;
    }
    private Button card(String module, String feature, List<FeaturePresentation> catalog, Consumer<String> navigate) {
        boolean open = ready(catalog.stream().filter(entry -> entry.id().equals(feature)).findFirst().orElseThrow());
        var icon = AppIcons.slot(AppIcons.feature(feature), "card-icon", 32); icon.getStyleClass().add("card-icon-box");
        var title = texts.label("home." + module, "card-title"); title.setMinWidth(0);
        var heading = new HBox(10, icon, title); heading.setAlignment(Pos.CENTER_LEFT);
        var contents = new VBox(8, heading, texts.label("home." + module + ".description", "secondary-text"), texts.label(open ? "home.ready" : "home.planned", "card-action"));
        var card = new Button(); card.setGraphic(contents); card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY); card.setId("home-" + feature);
        card.getStyleClass().setAll("module-card"); if (!open) card.getStyleClass().add("module-card-planned");
        contents.maxWidthProperty().bind(card.widthProperty().subtract(32));
        texts.text(card.accessibleTextProperty(), "home." + module); card.setOnAction(event -> navigate.accept(feature));
        return card;
    }
    void user(Optional<com.photopilot.model.LocalProfile> user) {
        texts.bind(greeting.textProperty(), () -> user.map(p -> texts.service().text("home.welcome", p.displayName())).orElseGet(() -> texts.service().text("profile.unavailable")));
    }
    void avatar(String name, Optional<byte[]> bytes) { avatar.getChildren().setAll(UserAvatar.portrait(name, bytes, 52)); }
    void dispose() { texts.close(); }
}
