package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.LocalProfile;
import java.util.*;
import java.util.function.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Modeless profile picker and editor using the existing theme, asynchronous manager calls and explicit archive confirmation. */
final class ProfileDialog extends Stage {
    private final ProfileManager profiles;
    private final UiOperations operations;
    private final LocalizationService language;
    private final Consumer<Optional<LocalProfile>> activeChanged;
    private final FxLocalizer texts;
    private final ListView<LocalProfile> list = new ListView<>();
    private final TextField name = new TextField();
    private final VBox controls = new VBox(14);
    private final ScrollPane scroll = new ScrollPane();
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private boolean working, disposed;
    private record Snapshot(List<LocalProfile> profiles, Optional<LocalProfile> active) { }

    ProfileDialog(Window owner, ProfileManager profiles, UiOperations operations, Consumer<UUID> select,
                  Consumer<Optional<LocalProfile>> activeChanged, LocalizationService language) {
        initOwner(owner); this.profiles = profiles; this.operations = operations; this.language = language; this.activeChanged = activeChanged;
        var content = new VBox(16); content.setPadding(new Insets(24)); content.getStyleClass().add("settings-content");
        texts = new FxLocalizer(content, language); texts.useReadableFont(content); texts.text(titleProperty(), "profile.manage");
        list.setId("profile-list"); list.setPrefHeight(230); list.setMinHeight(150);
        texts.text(list.accessibleTextProperty(), "profile.choose"); list.setPlaceholder(texts.label("profile.empty", "secondary-text"));
        list.setCellFactory(view -> new ListCell<>() {
            protected void updateItem(LocalProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                var icon = FeaturePane.label("●", "profile-avatar");
                icon.setMinWidth(Region.USE_PREF_SIZE);
                var title = FeaturePane.label(item.displayName(), "section-title");
                var identity = FeaturePane.label(item.id().toString(), "secondary-text");
                var names = new VBox(4, title, identity); names.setMinWidth(0); HBox.setHgrow(names, Priority.ALWAYS);
                var row = new HBox(12, icon, names); row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                row.prefWidthProperty().bind(widthProperty().subtract(26));
                row.maxWidthProperty().bind(widthProperty().subtract(26));
                setGraphic(row); setText(null); setAccessibleText(item.displayName() + " " + item.id());
            }
        });
        texts.text(name.promptTextProperty(), "profile.nameHint"); texts.text(name.accessibleTextProperty(), "profile.name"); texts.inputMenu(name); name.setId("profile-name");
        var label = texts.label("profile.name", "section-title"); label.setLabelFor(name);
        list.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> { if (value != null) name.setText(value.displayName()); });
        Button enter = button("profile.enter", "profile-enter", () -> { var item = list.getSelectionModel().getSelectedItem(); if (item != null) select.accept(item.id()); }, true);
        Button rename = button("profile.rename", "profile-rename", () -> {
            var item = list.getSelectionModel().getSelectedItem(); String value = name.getText();
            if (item != null) mutate(() -> profiles.renameProfile(item.id(), value), "profile.renamed", LocalProfile::id);
        }, false);
        Button delete = button("profile.delete", "profile-delete", () -> {
            var item = list.getSelectionModel().getSelectedItem();
            if (item != null && GuiProfileController.confirm(this, language, "profile.deleteTitle", "profile.deletePrompt", "profile.delete", item.displayName(), item.id()))
                mutate(() -> profiles.deleteProfile(item.id(), true), "profile.deleted", value -> null);
        }, false);
        for (Button selected : List.of(enter, rename, delete)) selected.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Button create = button("profile.create", "profile-create", () -> {
            String value = name.getText(); mutate(() -> profiles.createProfile(value), "profile.created", LocalProfile::id);
        }, true);
        controls.getChildren().addAll(list, new FlowPane(10, 8, enter, button("profile.refresh", "profile-refresh", this::refresh, false)), label, name,
            new FlowPane(10, 8, create, rename, delete), texts.label("profile.policy", "secondary-text"));
        feedback.setId("profile-feedback"); feedback.setMaxWidth(Double.MAX_VALUE);
        content.getChildren().addAll(texts.label("profile.choose", "feature-title"), texts.label("profile.description", "secondary-text"), controls);
        scroll.setId("profiles-scroll"); scroll.setContent(content); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var feedbackBox = new VBox(feedback); feedbackBox.setPadding(new Insets(12, 24, 12, 24));
        feedbackBox.getStyleClass().add("feedback-box");
        feedbackBox.visibleProperty().bind(feedback.textProperty().isNotEmpty()); feedbackBox.managedProperty().bind(feedbackBox.visibleProperty());
        var root = new BorderPane(scroll); root.setBottom(feedbackBox);
        Scene scene = new Scene(root, 660, 620); scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        setScene(scene); setMinWidth(480); setMinHeight(480);
        setOnCloseRequest(event -> { if (working) { event.consume(); message("profile.wait"); } });
        setOnHidden(event -> { disposed = true; texts.close(); });
    }
    private Button button(String key, String id, Runnable action, boolean primary) {
        var button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(event -> action.run()); return button;
    }
    void freeze(boolean value) { working = value; controls.setDisable(value); }
    void message(String key) { texts.text(feedback.textProperty(), key); }
    void failure(Result<?> result) { texts.bind(feedback.textProperty(), () -> String.join("; ", result.errors(language))); }
    void refresh() { var selected = list.getSelectionModel().getSelectedItem(); refresh(selected == null ? null : selected.id()); }
    private void refresh(UUID selected) {
        if (disposed) return; freeze(true);
        operations.run(() -> {
            var loaded = profiles.listProfiles(); if (!loaded.isSuccess()) return Result.<Snapshot>failure(loaded);
            return Result.success(new Snapshot(loaded.value().orElseThrow(), profiles.activeProfile().value()));
        }, result -> {
            if (disposed) return; freeze(false);
            if (!result.isSuccess()) { failure(result); return; }
            var snapshot = result.value().orElseThrow(); activeChanged.accept(snapshot.active()); list.getItems().setAll(snapshot.profiles());
            UUID choose = selected == null ? snapshot.active().map(LocalProfile::id).orElse(null) : selected;
            snapshot.profiles().stream().filter(p -> p.id().equals(choose)).findFirst().ifPresent(item -> { list.getSelectionModel().select(item); list.scrollTo(item); });
            if (snapshot.active().isEmpty()) message("profile.unavailable");
        });
    }
    private <T> void mutate(Supplier<Result<T>> action, String success, Function<T, UUID> selection) {
        freeze(true);
        operations.run(action, result -> {
            if (disposed) return;
            if (!result.isSuccess()) { freeze(false); failure(result); return; }
            message(success); refresh(selection.apply(result.value().orElseThrow()));
        });
    }
}
