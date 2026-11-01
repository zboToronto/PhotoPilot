package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Workspace launcher: actual feature IDs and availability, with no demo records or duplicate services. */
final class HomeDashboard extends VBox {
    private final FxLocalizer texts;
    private final Label greeting = FeaturePane.label("", "feature-title");
    private final StackPane avatar = new StackPane();
    HomeDashboard(LocalizationService language, Consumer<String> navigate) {
        super(24); setId("home-dashboard"); getStyleClass().add("home-dashboard"); setMaxWidth(1000);
        texts = new FxLocalizer(this, language);
        var introduction = new VBox(8, texts.label("home.eyebrow", "navigation-group"), greeting, texts.label("home.intro", "secondary-text"));
        introduction.setMinWidth(0); HBox.setHgrow(introduction, Priority.ALWAYS);
        introduction.getChildren().forEach(node -> ((Label) node).setMinWidth(0));
        var hero = new HBox(18, avatar, introduction);
        hero.setAlignment(Pos.CENTER_LEFT); hero.getStyleClass().add("home-hero");
        var tiles = new TilePane(16, 16); tiles.setPrefColumns(2); tiles.setPrefTileHeight(210); tiles.setTileAlignment(Pos.TOP_LEFT);
        tiles.prefTileWidthProperty().bind(tiles.widthProperty().subtract(16).divide(2));
        tiles.getChildren().addAll(
            card("equipment", "F05", "◈", navigate), card("sessions", "F01", "▤", navigate),
            card("calculator", "F08", "ƒ", navigate), card("planning", "F10", "◇", navigate),
            card("assistant", "F02", "✦", navigate));
        getChildren().addAll(hero, tiles, texts.label("home.local", "secondary-text"));
    }
    private Button card(String module, String feature, String symbol, Consumer<String> navigate) {
        var title = texts.label("home." + module, "section-title");
        var body = texts.label("home." + module + ".description", "secondary-text");
        var state = texts.label("home." + (feature.equals("F10") || feature.equals("F02") ? "planned" : "ready"), "module-status");
        var contents = new VBox(10, FeaturePane.label(symbol, "module-symbol"), title, body, state);
        var card = new Button(); card.setGraphic(contents); card.setId("home-" + feature);
        card.getStyleClass().add("module-card"); card.setPrefSize(240, 210); card.setMinSize(0, 210);
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        texts.text(card.accessibleTextProperty(), "home." + module); card.setOnAction(event -> navigate.accept(feature));
        return card;
    }
    void user(Optional<com.photopilot.model.LocalProfile> user) {
        texts.bind(greeting.textProperty(), () -> user.map(p -> texts.service().text("home.welcome", p.displayName())).orElseGet(() -> texts.service().text("profile.unavailable")));
    }
    void avatar(String name, Optional<byte[]> bytes) { avatar.getChildren().setAll(UserAvatar.view(name, bytes, 64)); }
    void dispose() { texts.close(); }
}
