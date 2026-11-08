package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.LocalProfile;
import java.util.*;
import java.util.function.*;
import javafx.animation.*;
import javafx.beans.property.*;
import javafx.css.PseudoClass;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.scene.transform.Scale;
import javafx.stage.*;
import javafx.util.Duration;

/** Full-window workspace chooser: pick a user and continue. Management lives in UserSettingsPane. */
final class UserSelectionPane extends Region {
    // Authored in reference-image pixels and scaled as one unit, so proportions hold at every window size.
    private static final double DESIGN_WIDTH = 1120, DESIGN_HEIGHT = 941, TILE_WIDTH = 280, TILE_HEIGHT = 264, AVATAR_SIZE = 132;
    private static final double[][] ROWS = { {132, 66}, {198, 50}, {268, 68}, {374, 284}, {696, 65}, {782, 58} };
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current"), ENGLISH = PseudoClass.getPseudoClass("english");
    private final Window owner;
    private Map<UUID, Optional<byte[]>> avatars = Map.of();
    private List<LocalProfile> users = List.of();
    private final ObjectProperty<LocalProfile> selected = new SimpleObjectProperty<>();
    private final ProfileManager profiles;
    private final UiOperations operations;
    private final LocalizationService language;
    private final Consumer<Optional<LocalProfile>> activeChanged;
    private final FxLocalizer texts;
    private final Label feedback = FeaturePane.label("", "selection-feedback");
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final List<Region> rows = new ArrayList<>();
    private final Pane composition = new Pane() {
        @Override protected void layoutChildren() {
            for (int row = 0; row < rows.size(); row++) rows.get(row).resizeRelocate(0, ROWS[row][0], getWidth(), ROWS[row][1]);
        }
    };
    private final ProfileTile left, centre, right;
    private final Button previous, next, enter, create;
    private final Label empty;
    private final HBox strip;
    private Alert error;
    private boolean working, disposed, focusTile = true;

    UserSelectionPane(Window owner, ProfileManager profiles, UiOperations operations, Consumer<UUID> select,
                  Consumer<Optional<LocalProfile>> activeChanged, LocalizationService language) {
        this.owner = owner; this.profiles = profiles; this.operations = operations; this.language = language; this.activeChanged = activeChanged;
        texts = new FxLocalizer(this, language); texts.useReadableFont(this); setId("user-selection"); getStyleClass().add("user-selection");
        texts.onRefresh(() -> pseudoClassStateChanged(ENGLISH, language.language() != Language.SIMPLIFIED_CHINESE));
        // The wave background fills the whole window behind the scaled composition; only the cards blur it.
        composition.getTransforms().add(scale); getChildren().addAll(new ThemeBackdrop(this, ThemeBackdrop.CHOOSER_SURFACES), composition);
        left = new ProfileTile(); centre = new ProfileTile(); right = new ProfileTile();
        previous = chevron("profile-previous", "profile.previous", AppIcons.PREVIOUS, -1);
        next = chevron("profile-next", "profile.next", AppIcons.NEXT, 1);
        empty = texts.label("profile.empty", "selection-empty");
        strip = new HBox(35, previous, left, centre, right, next, empty); strip.setAlignment(Pos.TOP_CENTER); strip.setId("profile-strip");
        enter = texts.button("profile.enter"); enter.setId("profile-enter"); enter.getStyleClass().setAll("selection-continue");
        enter.setGraphic(AppIcons.outline(AppIcons.ARROW, "continue-icon")); enter.setContentDisplay(ContentDisplay.RIGHT);
        enter.setPrefSize(284, 65); enter.setMinSize(284, 65); enter.disableProperty().bind(selected.isNull());
        enter.setOnAction(event -> { if (!working && selected.get() != null) select.accept(selected.get().id()); });
        // First run only: the one management action this screen keeps, so an empty registry is never a dead end.
        create = texts.button("profile.create"); create.setId("profile-create"); create.getStyleClass().setAll("selection-continue");
        create.setPrefSize(284, 65); create.setMinSize(284, 65); create.setOnAction(event -> create());
        feedback.setId("profile-feedback"); feedback.setWrapText(true); feedback.setMaxWidth(920);
        // Square logo sized to its 66 px design row, so the reference layout below it is unchanged.
        var logo = new StackPane(AppIcons.logo(ROWS[0][1])); logo.setId("selection-logo");
        addRows(logo, texts.label("app.title", "selection-brand"), texts.label("profile.choose", "selection-heading"),
            strip, new HBox(enter, create), feedback);
        addEventFilter(KeyEvent.KEY_PRESSED, this::navigate);
        showTiles();
    }
    private void addRows(Region... nodes) {
        for (Region node : nodes) { var row = new HBox(node); row.setAlignment(Pos.TOP_CENTER); rows.add(row); composition.getChildren().add(row); }
    }
    @Override protected void layoutChildren() {
        double factor = Math.min(getWidth() / DESIGN_WIDTH, getHeight() / DESIGN_HEIGHT);
        if (factor <= 0) return;
        scale.setX(factor); scale.setY(factor);
        composition.resizeRelocate(0, (getHeight() - DESIGN_HEIGHT * factor) / 2, getWidth() / factor, DESIGN_HEIGHT);
    }
    @Override protected double computeMinWidth(double height) { return 0; }
    @Override protected double computeMinHeight(double width) { return 0; }
    @Override protected double computePrefWidth(double height) { return DESIGN_WIDTH; }
    @Override protected double computePrefHeight(double width) { return 790; }
    private Button chevron(String id, String label, String icon, int direction) {
        var button = new Button(); button.setId(id); button.getStyleClass().setAll("selection-chevron");
        button.setGraphic(AppIcons.outline(icon, "chevron-icon")); button.setOnAction(event -> move(direction));
        texts.text(button.accessibleTextProperty(), label); HBox.setMargin(button, new Insets((TILE_HEIGHT - 48) / 2, 0, 0, 0));
        return button;
    }
    private void navigate(KeyEvent event) {
        switch (event.getCode()) {
            case LEFT -> move(-1);
            case RIGHT -> move(1);
            case ENTER -> enter.fire();
            default -> { return; }
        }
        event.consume();
    }
    private void move(int direction) {
        if (users.size() > 1) choose(users.get(Math.floorMod(index() + direction, users.size())));
    }
    private int index() { return selected.get() == null ? -1 : users.indexOf(selected.get()); }
    private void choose(LocalProfile value) {
        if (working || value.equals(selected.get())) return;
        selected.set(value); showTiles();
        if (users.size() > 2) centre.requestFocus();
        var settle = new FadeTransition(Duration.millis(140), strip); settle.setFromValue(.72); settle.setToValue(1); settle.play();
    }
    /** The selected user always takes the centre slot, except that two users stay put to remain symmetric. */
    private void showTiles() {
        int count = users.size(), at = index();
        if (count == 2) { left.show(users.get(0)); centre.show(users.get(1)); right.show(null); }
        else {
            centre.show(count == 0 ? null : users.get(at));
            left.show(count < 3 ? null : users.get(Math.floorMod(at - 1, count)));
            right.show(count < 3 ? null : users.get(Math.floorMod(at + 1, count)));
        }
        for (Node arrow : List.of(previous, next)) { arrow.setVisible(count > 3); arrow.setManaged(count > 3); }
        empty.setVisible(count == 0); empty.setManaged(count == 0);
        enter.setVisible(count > 0); enter.setManaged(count > 0);
        create.setVisible(count == 0); create.setManaged(count == 0);
    }
    void dispose() { disposed = true; if (error != null) error.close(); texts.close(); }
    private void create() {
        if (working) return;
        AppDialogs.name(owner, language, "profile.create", "").ifPresent(value -> {
            freeze(true);
            operations.run(() -> profiles.createProfile(value), result -> {
                if (disposed) return;
                freeze(false);
                if (result.isSuccess()) refresh(result.value().orElseThrow().id()); else failure(result);
            });
        });
    }
    void freeze(boolean value) { working = value; composition.setMouseTransparent(value); }
    void message(String key) { texts.text(feedback.textProperty(), key); }
    void failure(Result<?> result) {
        message("user.operationFailed");
        if (error != null) error.close();
        error = AppDialogs.error(owner, language, "user.operationFailed"); error.show();
    }
    void refresh() { var item = selected.get(); refresh(item == null ? null : item.id()); }
    private void refresh(UUID preferred) {
        if (disposed || working) return; freeze(true);
        operations.run(() -> UserSnapshot.load(profiles), result -> {
            if (disposed) return; freeze(false);
            if (!result.isSuccess()) { failure(result); return; }
            var snapshot = result.value().orElseThrow(); avatars = snapshot.avatars(); activeChanged.accept(snapshot.active()); users = snapshot.users();
            UUID active = snapshot.active().map(LocalProfile::id).orElse(null);
            selected.set(find(preferred).or(() -> find(active)).or(() -> users.stream().findFirst()).orElse(null));
            showTiles();
            if (focusTile) { centre.requestFocus(); focusTile = false; }
            if (snapshot.active().isEmpty()) message("profile.unavailable");
        });
    }
    private Optional<LocalProfile> find(UUID id) { return users.stream().filter(user -> user.id().equals(id)).findFirst(); }
    List<LocalProfile> users() { return users; }
    LocalProfile selectedUser() { return selected.get(); }
    void select(UUID id) { find(id).ifPresent(this::choose); }

    /** A workspace card (avatar and name), activated like a button so keyboard and accessibility follow the desktop norm. */
    private final class ProfileTile extends Button {
        private final StackPane portrait = new StackPane();
        private final Rectangle ring = new Rectangle();
        private final Label name = new Label();
        private LocalProfile profile;

        ProfileTile() {
            getStyleClass().setAll("profile-tile"); setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            ring.getStyleClass().add("profile-ring"); ring.setMouseTransparent(true);
            name.getStyleClass().add("profile-name"); name.setMaxWidth(TILE_WIDTH - 36); name.setTextOverrun(OverrunStyle.ELLIPSIS);
            portrait.getStyleClass().add("profile-slot"); portrait.setMinSize(AVATAR_SIZE, AVATAR_SIZE); portrait.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
            var body = new VBox(portrait, name); body.setAlignment(Pos.CENTER);
            setGraphic(body); setPrefSize(TILE_WIDTH, TILE_HEIGHT); setMinSize(TILE_WIDTH, TILE_HEIGHT);
            setOnAction(event -> { if (profile != null) choose(profile); });
            addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                // Consumed so a double click cannot rotate the row a second time under the pointer.
                if (profile != null && event.getClickCount() == 2) { event.consume(); enter.fire(); }
            });
        }
        void show(LocalProfile value) {
            profile = value; setVisible(value != null); setManaged(value != null);
            if (value == null) return;
            boolean current = value.equals(selected.get());
            double size = current ? AVATAR_SIZE : 112;
            ring.setWidth(size); ring.setHeight(size); ring.setArcWidth(size * UserAvatar.TILE_ROUNDING); ring.setArcHeight(size * UserAvatar.TILE_ROUNDING);
            portrait.getChildren().setAll(UserAvatar.tile(value.displayName(), avatars.getOrDefault(value.id(), Optional.empty()), size), ring);
            name.setText(value.displayName());
            setAccessibleText(value.displayName()); pseudoClassStateChanged(CURRENT, current);
        }
    }
}
