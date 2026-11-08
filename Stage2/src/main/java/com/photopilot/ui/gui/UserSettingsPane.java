package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.LocalProfile;
import java.time.*;
import java.util.*;
import java.util.function.*;
import javafx.animation.PauseTransition;
import javafx.beans.binding.*;
import javafx.beans.property.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

/**
 * In-app user management. Lifecycle and avatar I/O stay in ProfileManager. The Switch button of a listed user is the
 * entry point for changing the active user; it only hands the id to the application's existing switch (the owner's
 * callback, with its unsaved-input confirmation), and editing a user here never changes the session.
 */
final class UserSettingsPane extends VBox {
    private final Window owner;
    private final ProfileManager profiles;
    private final UiOperations operations;
    private final LocalizationService language;
    private final Consumer<Optional<LocalProfile>> activeChanged;
    private final Consumer<UUID> switchUser;
    private final FxLocalizer texts;
    private final ObjectProperty<LocalProfile> active = new SimpleObjectProperty<>(), selected = new SimpleObjectProperty<>();
    private final BooleanProperty working = new SimpleBooleanProperty();
    private final StackPane summaryAvatar = new StackPane();
    private final Label summaryName = FeaturePane.label("", "user-summary-name");
    private final Label summaryDetail = FeaturePane.label("", "secondary-text");
    private final VBox rows = new VBox(2);
    private final ToggleGroup rowGroup = new ToggleGroup();
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final PauseTransition notice = new PauseTransition(Duration.seconds(5));
    private Map<UUID, Optional<byte[]>> avatars = Map.of();
    private List<LocalProfile> users = List.of();
    private Alert error;
    private boolean disposed;

    UserSettingsPane(Window owner, ProfileManager profiles, UiOperations operations, Consumer<Optional<LocalProfile>> activeChanged,
                     Consumer<UUID> switchUser, LocalizationService language) {
        super(16); this.owner = owner; this.profiles = profiles; this.operations = operations; this.activeChanged = activeChanged; this.language = language;
        this.switchUser = Objects.requireNonNull(switchUser);
        setId("user-settings"); getStyleClass().add("page"); setMaxWidth(1180); setMinWidth(0);
        texts = new FxLocalizer(this, language);
        BooleanBinding noActive = active.isNull().or(working), noSelection = selected.isNull().or(working);
        var currentActions = new HBox(8, button("avatar.change", "current-avatar", "secondary-button", () -> changeAvatar(active.get()), noActive),
            button("profile.rename", "current-rename", "secondary-button", () -> rename(active.get()), noActive));
        currentActions.setAlignment(Pos.CENTER_RIGHT); currentActions.setMinWidth(Region.USE_PREF_SIZE);
        var names = new VBox(2, summaryName, summaryDetail); names.setMinWidth(0); HBox.setHgrow(names, Priority.ALWAYS);
        var summary = new HBox(16, summaryAvatar, names, currentActions); summary.setAlignment(Pos.CENTER_LEFT);

        var manageHeader = new HBox(8, texts.label("user.manage", "group-title"), spacer(),
            button("profile.create", "profile-create", "secondary-button", this::create, working),
            button("profile.refresh", "profile-refresh", "secondary-button", this::refresh, working));
        manageHeader.setAlignment(Pos.CENTER_LEFT);
        rows.setId("user-rows");
        var selectedActions = new FlowPane(8, 8, button("profile.rename", "profile-rename", "secondary-button", () -> rename(selected.get()), noSelection),
            button("avatar.change", "avatar-import", "secondary-button", () -> changeAvatar(selected.get()), noSelection),
            button("avatar.remove", "avatar-remove", "secondary-button", this::removeAvatar, noSelection));

        var deleteButton = button("user.deleteSelected", "profile-delete", "danger-button", this::delete, noSelection.or(selected.isEqualTo(active)));
        var dangerText = new VBox(2, texts.label("user.deleteTitle", "group-title"), texts.label("user.deleteBody", "secondary-text"));
        dangerText.setMinWidth(0); HBox.setHgrow(dangerText, Priority.ALWAYS);
        deleteButton.setMinWidth(Region.USE_PREF_SIZE);
        var danger = new HBox(16, dangerText, deleteButton); danger.setAlignment(Pos.CENTER_LEFT);

        feedback.setId("profile-feedback"); feedback.managedProperty().bind(feedback.textProperty().isNotEmpty()); feedback.visibleProperty().bind(feedback.managedProperty());
        notice.setOnFinished(event -> texts.bind(feedback.textProperty(), () -> ""));
        getChildren().addAll(group(texts.label("menu.userSettings", "page-title"), summary), group(manageHeader, rows, selectedActions, feedback), group(danger));
    }
    private static VBox group(javafx.scene.Node... nodes) { var group = new VBox(12, nodes); group.getStyleClass().add("surface-panel"); return group; }
    private static Region spacer() { var space = new Region(); HBox.setHgrow(space, Priority.ALWAYS); return space; }
    private Button button(String key, String id, String style, Runnable action, javafx.beans.value.ObservableBooleanValue disabled) {
        var button = texts.button(key); button.setId(id); button.getStyleClass().add(style); button.setOnAction(event -> action.run());
        button.disableProperty().bind(disabled); return button;
    }
    private void create() { AppDialogs.name(owner, language, "profile.create", "").ifPresent(value -> mutate(() -> profiles.createProfile(value), "user.created.notice", LocalProfile::id)); }
    private void rename(LocalProfile user) {
        if (user != null) AppDialogs.name(owner, language, "profile.rename", user.displayName())
            .ifPresent(value -> mutate(() -> profiles.renameProfile(user.id(), value), "profile.renamed", LocalProfile::id));
    }
    private void delete() {
        var user = selected.get();
        if (user != null && GuiProfileController.confirm(owner, language, "profile.deleteTitle", "profile.deletePrompt", "profile.delete", user.displayName()))
            mutate(() -> profiles.deleteProfile(user.id(), true), "profile.deleted", value -> null);
    }
    private void changeAvatar(LocalProfile user) {
        if (user == null) return;
        var chooser = new FileChooser(); chooser.setTitle(language.text("avatar.change"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG / JPEG", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
        var source = chooser.showOpenDialog(owner); if (source != null) previewAvatar(user.id(), source.toPath());
    }
    void previewAvatar(UUID id, java.nio.file.Path source) {
        working.set(true);
        operations.run(() -> ProfileManager.prepareAvatar(source), result -> {
            if (disposed) return;
            working.set(false);
            if (!result.isSuccess()) { message("avatar.failed"); showError("avatar.failed"); return; }
            var bytes = result.value().orElseThrow();
            var preview = new Alert(Alert.AlertType.CONFIRMATION); AppDialogs.style(preview, owner);
            preview.setTitle(language.text("avatar.preview")); preview.setHeaderText(language.text("avatar.preview"));
            preview.getDialogPane().setId("avatar-preview");
            var circle = new StackPane(UserAvatar.portrait("", Optional.of(bytes), 160)); circle.setAlignment(Pos.CENTER);
            preview.getDialogPane().setContent(circle);
            var save = new ButtonType(language.text("avatar.save"), ButtonBar.ButtonData.OK_DONE);
            var cancel = new ButtonType(language.text("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            preview.getButtonTypes().setAll(save, cancel);
            if (preview.showAndWait().filter(save::equals).isPresent())
                mutate(() -> profiles.saveAvatar(id, bytes), "avatar.saved", value -> id);
        });
    }
    private void removeAvatar() {
        var user = selected.get();
        if (user != null) mutate(() -> profiles.removeAvatar(user.id()), "avatar.removed", value -> user.id());
    }
    private void message(String key) { notice.stop(); texts.text(feedback.textProperty(), key); }
    /** A short notice in this page's feedback line (for example after a switch); it clears itself like other notices. */
    void notice(String key) { message(key); notice.playFromStart(); }
    void failure() { message("user.operationFailed"); showError("user.operationFailed"); }
    private void showError(String key) {
        if (error != null) error.close();
        error = AppDialogs.error(owner, language, key); error.show();
    }
    void refresh() { var user = selected.get(); refresh(user == null ? null : user.id()); }
    private void refresh(UUID preferred) {
        if (disposed || working.get()) return;
        working.set(true);
        operations.run(() -> UserSnapshot.load(profiles), result -> {
            if (disposed) return;
            working.set(false);
            if (!result.isSuccess()) { failure(); return; }
            var snapshot = result.value().orElseThrow(); avatars = snapshot.avatars(); users = snapshot.users();
            active.set(snapshot.active().orElse(null)); activeChanged.accept(snapshot.active());
            selected.set(find(preferred).or(() -> snapshot.active()).or(() -> users.stream().findFirst()).orElse(null));
            showUsers();
        });
    }
    private Optional<LocalProfile> find(UUID id) { return users.stream().filter(user -> user.id().equals(id)).findFirst(); }
    private void showUsers() {
        var current = active.get();
        summaryAvatar.getChildren().setAll(UserAvatar.portrait(current == null ? "" : current.displayName(), current == null ? Optional.empty() : avatars.getOrDefault(current.id(), Optional.empty()), 72));
        if (current == null) { texts.text(summaryName.textProperty(), "profile.unavailable"); texts.bind(summaryDetail.textProperty(), () -> ""); }
        else {
            texts.bind(summaryName.textProperty(), current::displayName);
            var created = LocalDate.ofInstant(current.createdAt(), ZoneId.systemDefault());
            texts.bind(summaryDetail.textProperty(), () -> language.text("user.created", created.toString()));
        }
        rows.getChildren().setAll(users.stream().map(this::row).toList());
    }
    private StackPane row(LocalProfile user) {
        var name = new Label(user.displayName()); name.getStyleClass().add("user-row-name"); name.setMinWidth(0); name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(name, Priority.ALWAYS); name.setMaxWidth(Double.MAX_VALUE); FxLocalizer.tooltipWhenClipped(name);
        var content = new HBox(10, UserAvatar.portrait(user.displayName(), avatars.getOrDefault(user.id(), Optional.empty()), 30), name);
        content.setAlignment(Pos.CENTER_LEFT);
        var button = new ToggleButton(); button.getStyleClass().add("user-row"); button.setId("user-row-" + user.id()); button.setToggleGroup(rowGroup);
        button.setGraphic(content); button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY); button.setMaxWidth(Double.MAX_VALUE); button.setAccessibleText(user.displayName());
        content.prefWidthProperty().bind(button.widthProperty().subtract(24));
        button.setSelected(user.equals(selected.get()));
        // Rows behave like a selection list: clicking the chosen row keeps it chosen. Selecting never changes the session.
        button.setOnAction(event -> { button.setSelected(true); selected.set(user); });
        var line = new StackPane(button); line.setAlignment(Pos.CENTER_LEFT); line.getStyleClass().add("user-line");
        if (user.equals(active.get())) {
            // The active user is marked Current and offers no Switch action.
            var badge = texts.label("user.currentBadge", "status-badge"); badge.setId("user-current-badge"); badge.setWrapText(false); badge.setMinWidth(Region.USE_PREF_SIZE);
            content.getChildren().add(badge);
        } else {
            // A sibling above the row (never a button inside a button), so the row keeps its full-width selection highlight.
            var change = texts.button("user.switch"); change.setId("user-switch-" + user.id()); change.getStyleClass().addAll("secondary-button", "user-switch");
            texts.text(change.accessibleTextProperty(), "user.switchAccessible", user.displayName());
            change.setMinWidth(Region.USE_PREF_SIZE); change.disableProperty().bind(working);
            change.setOnAction(event -> switchUser.accept(user.id()));
            StackPane.setAlignment(change, Pos.CENTER_RIGHT); StackPane.setMargin(change, new javafx.geometry.Insets(0, 12, 0, 0));
            // Reserves the button's width inside the row, so a long name is shortened before it, never drawn under it.
            var reserve = new Region(); reserve.minWidthProperty().bind(change.widthProperty()); reserve.setMouseTransparent(true);
            content.getChildren().add(reserve);
            line.getChildren().add(change);
        }
        return line;
    }
    private <T> void mutate(Supplier<Result<T>> action, String success, Function<T, UUID> selection) {
        working.set(true);
        operations.run(action, result -> {
            if (disposed) return;
            working.set(false);
            if (!result.isSuccess()) { failure(); return; }
            message(success); notice.playFromStart(); refresh(selection.apply(result.value().orElseThrow()));
        });
    }
    void select(UUID id) { find(id).ifPresent(user -> { selected.set(user); showUsers(); }); }
    LocalProfile selectedUser() { return selected.get(); }
    List<LocalProfile> users() { return users; }
    void dispose() { disposed = true; notice.stop(); if (error != null) error.close(); texts.close(); }
}
