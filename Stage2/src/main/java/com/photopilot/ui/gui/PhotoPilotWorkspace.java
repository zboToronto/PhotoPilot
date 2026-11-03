package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Lightweight application shell. No services, persistence, or feature execution. */
final class PhotoPilotWorkspace extends BorderPane {
    private static final double SIDEBAR_WIDTH = 240, COMPACT_SIDEBAR_WIDTH = 212, COMPACT_BELOW = 1000;
    private static final String HOME = "HOME", USER_SETTINGS = "USER_SETTINGS", APP_SETTINGS = "APP_SETTINGS";
    private final FeaturePane detail;
    private final HomeDashboard home;
    private final ApplicationSettingsPane applicationSettings;
    private UserSettingsPane userSettings;
    private final VBox content = new VBox();
    private final ToggleGroup selection = new ToggleGroup();
    private final ToggleButton homeButton;
    private final ToggleButton userSettingsButton;
    private final ToggleButton applicationSettingsButton;
    private final StackPane avatar = new StackPane();
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Map<String, ToggleButton> navigation = new LinkedHashMap<>();
    private final Label storageNotice = FeaturePane.label("", "feedback-text");
    private final Label profileName = FeaturePane.label("", "profile-current");
    private final Button userButton = new Button();
    private final ContextMenu userMenu = new ContextMenu();
    private final MenuItem switchWorkspace;
    private final ScrollPane contentScroll;
    private String selectedId;
    private long selectionVersion;

    PhotoPilotWorkspace() { this(LocalizationService.english()); }
    PhotoPilotWorkspace(LocalizationService localization) {
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        texts.useReadableFont(this);
        detail = new FeaturePane(localization);
        home = new HomeDashboard(localization, id -> navigation.get(id).fire());
        applicationSettings = new ApplicationSettingsPane(localization);
        getStyleClass().add("workspace");
        storageNotice.setId("storage-notice");
        storageNotice.getStyleClass().add("feedback-box");
        storageNotice.setMaxWidth(Double.MAX_VALUE);
        storageNotice.setVisible(false); storageNotice.setManaged(false);
        switchWorkspace = menuItem("menu-switch-workspace", "menu.switchWorkspace", () -> { });
        setTop(new VBox(header(), storageNotice));

        content.setAlignment(Pos.TOP_CENTER);
        content.getStyleClass().add("content-surface");
        contentScroll = scroll(content, "content-scroll");
        setCenter(contentScroll);
        VBox sidebar = new VBox(1);
        sidebar.getStyleClass().add("sidebar");
        homeButton = item("nav-home", AppIcons.HOUSE, "home.title", this::showHome); sidebar.getChildren().add(homeButton);
        String previousGroup = "";
        for (FeaturePresentation feature : FeaturePresentation.all()) {
            if (!feature.group().equals(previousGroup)) {
                Label group = FeaturePane.label("", "navigation-group");
                texts.bind(group.textProperty(), () -> feature.group(localization));
                sidebar.getChildren().add(group);
                previousGroup = feature.group();
            }
            ToggleButton button = item("nav-" + feature.id(), AppIcons.feature(feature.id()), null, () -> showFeature(feature));
            texts.bind(button.textProperty(), () -> feature.title(localization));
            texts.bind(button.accessibleTextProperty(), () -> localization.text("feature.navigationAccessible", feature.id(), feature.title(localization)));
            if (feature.status() != FeaturePresentation.Status.IMPLEMENTED) button.getStyleClass().add("navigation-item-planned");
            navigation.put(feature.id(), button);
            sidebar.getChildren().add(button);
        }
        sidebar.getChildren().add(texts.label("group.preferences", "navigation-group"));
        userSettingsButton = item("nav-user-settings", AppIcons.PERSON, "menu.userSettings", this::showUserSettings);
        userSettingsButton.setVisible(false); userSettingsButton.setManaged(false);
        applicationSettingsButton = item("nav-app-settings", AppIcons.GEAR, "menu.appSettings", this::showApplicationSettings);
        sidebar.getChildren().addAll(userSettingsButton, applicationSettingsButton);
        ScrollPane navigationScroll = scroll(sidebar, "navigation-scroll");
        navigationScroll.setId("navigation-scroll");
        widthProperty().addListener((o, old, value) -> sidebarWidth(navigationScroll, value.doubleValue()));
        sidebarWidth(navigationScroll, SIDEBAR_WIDTH * 2);
        // Arrow keys follow display order across groups; Tab reaches content actions.
        var buttons = new java.util.ArrayList<ToggleButton>(); buttons.add(homeButton); buttons.addAll(navigation.values());
        buttons.add(userSettingsButton); buttons.add(applicationSettingsButton);
        for (ToggleButton button : buttons) button.setOnKeyPressed(event -> {
            var shown = buttons.stream().filter(ToggleButton::isManaged).toList();
            int index = shown.indexOf(button);
            int next = switch (event.getCode()) {
                case DOWN -> Math.min(index + 1, shown.size() - 1);
                case UP -> Math.max(index - 1, 0);
                case HOME -> 0;
                case END -> shown.size() - 1;
                default -> -1;
            };
            if (next >= 0) {
                ToggleButton target = shown.get(next);
                target.fire();
                target.requestFocus();
                double overflow = sidebar.getBoundsInLocal().getHeight() - navigationScroll.getViewportBounds().getHeight();
                if (overflow > 0) navigationScroll.setVvalue(Math.max(0, Math.min(1,
                    (target.getBoundsInParent().getMinY() - 40) / overflow)));
                event.consume();
            }
        });
        setLeft(navigationScroll);
        showHome();
    }
    private static void sidebarWidth(ScrollPane sidebar, double windowWidth) {
        double width = windowWidth < COMPACT_BELOW ? COMPACT_SIDEBAR_WIDTH : SIDEBAR_WIDTH;
        sidebar.setPrefWidth(width); sidebar.setMinWidth(width); sidebar.setMaxWidth(width);
    }
    private ToggleButton item(String id, String icon, String label, Runnable action) {
        ToggleButton button = new ToggleButton();
        if (label != null) texts.text(button.textProperty(), label);
        button.setId(id); button.setToggleGroup(selection); button.getStyleClass().add("navigation-item");
        button.setGraphic(AppIcons.slot(icon, "navigation-icon", 20)); button.setMaxWidth(Double.MAX_VALUE); button.setMinHeight(36);
        // Repeated clicks must not clear the current workspace selection.
        button.setOnAction(event -> { button.setSelected(true); action.run(); });
        return button;
    }
    private void showFeature(FeaturePresentation feature) {
        selectedId = feature.id();
        selectionVersion++;
        content.getChildren().setAll(detail);
        detail.show(feature);
        contentScroll.setVvalue(0);
    }
    private void showPage(String id, ToggleButton button, Region page) {
        detail.suspendModules();
        selectedId = id; selectionVersion++; button.setSelected(true);
        content.getChildren().setAll(page); contentScroll.setVvalue(0);
    }
    void showHome() { showPage(HOME, homeButton, home); }
    boolean atHome() { return HOME.equals(selectedId); }
    void showUserSettings() { showPage(USER_SETTINGS, userSettingsButton, userSettings); userSettings.refresh(); }
    boolean atUserSettings() { return USER_SETTINGS.equals(selectedId); }
    void showApplicationSettings() { showPage(APP_SETTINGS, applicationSettingsButton, applicationSettings); }
    boolean atApplicationSettings() { return APP_SETTINGS.equals(selectedId); }
    void showAvatar(String name, java.util.Optional<byte[]> bytes) {
        avatar.getChildren().setAll(UserAvatar.portrait(name, bytes, 28)); home.avatar(name, bytes);
    }

    private HBox header() {
        Label brand = texts.label("app.title", "brand");
        profileName.setId("profile-active"); profileName.setWrapText(false); profileName.setMaxWidth(220); profileName.setMinHeight(Region.USE_COMPUTED_SIZE);
        var identity = new HBox(8, avatar, profileName, AppIcons.outline(AppIcons.CHEVRON_DOWN, "user-menu-chevron")); identity.setAlignment(Pos.CENTER_LEFT);
        userButton.setId("user-menu"); userButton.getStyleClass().setAll("user-menu"); userButton.setGraphic(identity); userButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        userButton.setVisible(false); userButton.setManaged(false);
        userButton.setOnAction(event -> {
            var bounds = userButton.localToScreen(userButton.getBoundsInLocal());
            userMenu.show(userButton, bounds.getMaxX(), bounds.getMaxY() + 6); userMenu.setX(bounds.getMaxX() - userMenu.getWidth());
        });
        userMenu.getStyleClass().add("app-menu"); userMenu.getScene().getRoot().setStyle("-fx-background-color: transparent;");
        userMenu.getItems().addAll(menuItem("menu-user-settings", "menu.userSettings", this::showUserSettings),
            menuItem("menu-app-settings", "menu.appSettings", this::showApplicationSettings), new SeparatorMenuItem(), switchWorkspace);
        HBox header = new HBox(10, AppIcons.logo(26), brand, spacer(), userButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }
    private MenuItem menuItem(String id, String label, Runnable action) {
        var item = new MenuItem(); item.setId(id); texts.text(item.textProperty(), label); item.setOnAction(event -> action.run()); return item;
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

    void connectCapabilities(CapabilityPane capability) { detail.connectCapabilities(capability); }

    void connectCalculator(CalculatorPane calculator) { detail.connectCalculator(calculator); }
    void connectSessions(SessionPane sessions) { detail.connectSessions(sessions); }
    void selectSessions() { navigation.get("F01").fire(); }
    void connectRequirements(RequirementPane requirements) { detail.connectRequirements(requirements); }
    void selectRequirements() { navigation.get("F03").fire(); }

    void connectInventory(InventoryPane inventory) { detail.connectInventory(inventory); }

    void showFeedback(String message) { detail.showFeedback(message); }
    void showFeedback(java.util.function.Supplier<String> message) { detail.showFeedback(message); }
    void showStorageNotice(LocalizedMessage notice) {
        texts.bind(storageNotice.textProperty(), () -> notice.render(localization));
        storageNotice.setVisible(true); storageNotice.setManaged(true);
    }
    long selectionVersion() { return selectionVersion; }
    void connectUserSettings(UserSettingsPane pane) {
        userSettings = pane; userSettingsButton.setVisible(true); userSettingsButton.setManaged(true);
    }
    UserSettingsPane userSettings() { return userSettings; }
    ContextMenu userMenu() { return userMenu; }
    void connectProfiles(Runnable switchUser) {
        userButton.setVisible(true); userButton.setManaged(true); switchWorkspace.setOnAction(event -> switchUser.run());
    }
    void showProfile(java.util.Optional<com.photopilot.model.LocalProfile> profile) {
        home.user(profile);
        texts.bind(profileName.textProperty(), () -> profile.map(com.photopilot.model.LocalProfile::displayName).orElseGet(() -> localization.text("profile.unavailable")));
    }
    void freeze(boolean value) { setDisable(value); }
    void dispose() {
        texts.close(); home.dispose(); detail.dispose(); applicationSettings.dispose();
        if (userSettings != null) userSettings.dispose();
    }

    /** Exercises real controls in the existing opt-in graphical smoke mode. */
    void verifyNavigation() {
        if (navigation.size() != 11) throw new IllegalStateException("Expected 11 feature entries");
        for (var entry : navigation.entrySet()) {
            contentScroll.setVvalue(1);
            entry.getValue().fire();
            if (!entry.getKey().equals(selectedId) || !detail.feedbackText().isEmpty() || contentScroll.getVvalue() != 0) {
                throw new IllegalStateException("Feature selection did not reset content: " + entry.getKey());
            }
            if (!detail.statusText().equals(localization.text("feature.status." + (java.util.Set.of("F01", "F03", "F05", "F08").contains(entry.getKey()) ? "IMPLEMENTED" : entry.getKey().equals("F06") ? "IN_PROGRESS" : "SCAFFOLDED"))) || !detail.workflowDisabled()) {
                throw new IllegalStateException("Unexpected scaffold availability: " + entry.getKey());
            }
            detail.checkStatusButton().fire();
            String expected = entry.getKey().equals("F01") ? localization.text("session.ready")
                : entry.getKey().equals("F03") ? localization.text("requirement.ready") : entry.getKey().equals("F05")
                ? detail.inventoryConnected() ? localization.text("feature.inventoryReady")
                    : localization.text("feature.noWorkflow", "F05", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F08") ? localization.text("feature.calculatorReady", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F06") ? localization.text("capability.scope")
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
