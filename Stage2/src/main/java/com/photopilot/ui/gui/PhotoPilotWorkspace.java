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
    /** Read-only current-user indicator (avatar and name); switching users lives in User Settings. */
    private final HBox identity = new HBox(8);
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
        // Decorative wave background behind the unchanged shell; it blurs only what shows through translucent surfaces.
        getChildren().add(new ThemeBackdrop(this, ThemeBackdrop.WORKSPACE_SURFACES));
        storageNotice.setId("storage-notice");
        storageNotice.getStyleClass().add("feedback-box");
        storageNotice.setMaxWidth(Double.MAX_VALUE);
        storageNotice.setVisible(false); storageNotice.setManaged(false);
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
        stableGutter(navigationScroll, sidebar);
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
    /**
     * Keeps the scroll bar's width reserved while it is hidden, so navigation rows keep one position and width whether
     * or not the list overflows. Without it, a translation whose font is slightly taller (Chinese) could make the list
     * overflow and every row would narrow by the scroll bar's width on a language switch.
     */
    private static void stableGutter(ScrollPane scroll, VBox sidebar) {
        var gutter = javafx.css.PseudoClass.getPseudoClass("scroll-gutter");
        scroll.skinProperty().addListener((property, old, skin) -> {
            for (javafx.scene.Node node : scroll.lookupAll(".scroll-bar"))
                if (node instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL && bar.getParent() == scroll) {
                    bar.visibleProperty().addListener((shown, was, visible) -> sidebar.pseudoClassStateChanged(gutter, !visible));
                    sidebar.pseudoClassStateChanged(gutter, !bar.isVisible());
                }
        });
        sidebar.pseudoClassStateChanged(gutter, true);
    }
    private ToggleButton item(String id, String icon, String label, Runnable action) {
        ToggleButton button = new ToggleButton();
        if (label != null) texts.text(button.textProperty(), label);
        button.setId(id); button.setToggleGroup(selection); button.getStyleClass().add("navigation-item");
        button.setGraphic(AppIcons.slot(icon, "navigation-icon", 20)); button.setMaxWidth(Double.MAX_VALUE); button.setMinHeight(36);
        FxLocalizer.tooltipWhenClipped(button);
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
        profileName.setTextOverrun(OverrunStyle.ELLIPSIS); FxLocalizer.tooltipWhenClipped(profileName);
        // Display only: not focusable, no pointer cursor, no menu. It follows the active user automatically.
        identity.getChildren().setAll(avatar, profileName); identity.setAlignment(Pos.CENTER_LEFT);
        identity.setId("user-indicator"); identity.getStyleClass().add("user-indicator"); identity.setMinWidth(Region.USE_PREF_SIZE);
        identity.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        identity.setVisible(false); identity.setManaged(false);
        HBox header = new HBox(10, AppIcons.logo(26), brand, spacer(), identity);
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

    void connectCapabilities(CapabilityPane capability) { detail.connectCapabilities(capability); }

    void connectCalculator(CalculatorPane calculator) { detail.connectCalculator(calculator); }
    void connectSessions(SessionPane sessions) { detail.connectSessions(sessions); }
    void selectSessions() { navigation.get("F01").fire(); }
    void connectRequirements(RequirementPane requirements) { detail.connectRequirements(requirements); }
    void selectRequirements() { navigation.get("F03").fire(); }
    void connectMatching(GearMatchPane matching) { detail.connectMatching(matching); }
    void selectMatching() { navigation.get("F07").fire(); }
    void connectEnvironment(EnvironmentPane environment) { detail.connectEnvironment(environment); }
    void selectEnvironment() { navigation.get("F09").fire(); }
    void connectDiscovery(DiscoveryPane discovery) { detail.connectDiscovery(discovery); }
    void selectDiscovery() { navigation.get("F02").fire(); }
    void connectPlanning(PlanPane planning) { detail.connectPlanning(planning); }
    void selectPlanning() { navigation.get("F10").fire(); }
    void connectReview(ReviewPane review) { detail.connectReview(review); }
    void selectReview() { navigation.get("F11").fire(); }

    void connectInventory(InventoryPane inventory) { detail.connectInventory(inventory); }
    void selectInventory() { navigation.get("F05").fire(); }
    void connectImport(GearImportPane gearImport) { detail.connectImport(gearImport); }
    void selectImport() { navigation.get("F04").fire(); }

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
    /** Shows the current-user indicator once a profile session is connected. */
    void connectProfiles() { identity.setVisible(true); identity.setManaged(true); }
    void showProfile(java.util.Optional<com.photopilot.model.LocalProfile> profile) {
        home.user(profile);
        java.util.function.Supplier<String> name = () -> profile.map(com.photopilot.model.LocalProfile::displayName).orElseGet(() -> localization.text("profile.unavailable"));
        texts.bind(profileName.textProperty(), name);
        texts.bind(identity.accessibleTextProperty(), () -> localization.text("user.indicatorAccessible", name.get()));
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
            if (!detail.statusText().equals(localization.text("feature.status.IMPLEMENTED")) || !detail.workflowDisabled()) {
                throw new IllegalStateException("Unexpected scaffold availability: " + entry.getKey());
            }
            detail.checkStatusButton().fire();
            String expected = entry.getKey().equals("F01") ? localization.text("session.ready")
                : entry.getKey().equals("F02") ? detail.discoveryConnected() ? localization.text("discover.ready")
                    : localization.text("feature.noWorkflow", "F02", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F03") ? localization.text("requirement.ready")
                : entry.getKey().equals("F04") ? detail.importConnected() ? localization.text("import.ready")
                    : localization.text("feature.noWorkflow", "F04", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F05")
                ? detail.inventoryConnected() ? localization.text("feature.inventoryReady")
                    : localization.text("feature.noWorkflow", "F05", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F08") ? localization.text("feature.calculatorReady", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F06") ? localization.text("capability.scope")
                : entry.getKey().equals("F07") ? detail.matchingConnected() ? localization.text("match.ready")
                    : localization.text("feature.noWorkflow", "F07", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F09") ? detail.environmentConnected() ? localization.text("env.ready")
                    : localization.text("feature.noWorkflow", "F09", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F10") ? detail.planningConnected() ? localization.text("plan.status")
                    : localization.text("feature.noWorkflow", "F10", localization.text("feature.status.IMPLEMENTED"))
                : entry.getKey().equals("F11") ? detail.reviewConnected() ? localization.text("review.ready.status")
                    : localization.text("feature.noWorkflow", "F11", localization.text("feature.status.IMPLEMENTED"))
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
