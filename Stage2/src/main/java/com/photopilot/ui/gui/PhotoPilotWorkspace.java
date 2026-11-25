package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import com.photopilot.workflow.Milestone;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Lightweight application shell. No services, persistence, or feature execution.
 *
 * <p>The sidebar is organised around the photography session: General (Home, Photography Sessions, Discover
 * Inspiration), Active Session (the current session's progress summary and its seven milestone pages in workflow
 * order), Equipment Management, Photography Tools and Settings. Every feature keeps its page and its stable entry id
 * ({@code nav-F01} ... {@code nav-F11}). Camera Shutter Count has a dedicated page. Equipment, Tools and Settings
 * can be collapsed (expanded by default; a collapsed section opens again when one of its entries is selected).
 */
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
    private final ToggleButton shootButton, reportButton, shutterButton;
    private final StackPane avatar = new StackPane();
    private final LocalizationService localization;
    private final FxLocalizer texts;
    /** The eleven feature entries (the smoke check walks exactly these); the other entries are kept apart. */
    private final Map<String, ToggleButton> navigation = new LinkedHashMap<>();
    /** Pages in display order for Up/Down/Home/End. */
    private final List<ToggleButton> order = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final SessionSidebar sessionSidebar;
    private final Label storageNotice = FeaturePane.label("", "feedback-text");
    private final Label profileName = FeaturePane.label("", "profile-current");
    /** Read-only current-user indicator (avatar and name); switching users lives in User Settings. */
    private final HBox identity = new HBox(8);
    private final ScrollPane contentScroll;
    private final VBox sidebar = new VBox(1);
    private final ScrollPane navigationScroll;
    private SessionWorkflowModel workflow;
    private Runnable sessionOverview = () -> { }, shootCompletion = () -> { };
    /** Makes a continuation's session the chosen one before its page opens (set with the workflow model). */
    private java.util.function.BiConsumer<String, java.util.UUID> continuation = (target, id) -> { };
    private String selectedId;
    private long selectionVersion;

    /** One sidebar section: a heading (a toggle when collapsible) and its entries. */
    private final class Section {
        final Labeled header; final List<Node> items; final boolean collapsible; final String key; boolean expanded = true;
        final Set<Node> withheld = new HashSet<>();
        Section(String id, String key, boolean collapsible, Node... items) {
            this.collapsible = collapsible; this.items = List.of(items); this.key = key;
            if (collapsible) {
                var toggle = new Button(); toggle.setId(id); toggle.getStyleClass().addAll("navigation-group", "navigation-group-toggle");
                toggle.setMaxWidth(Double.MAX_VALUE); toggle.setAlignment(Pos.CENTER_LEFT); toggle.setContentDisplay(ContentDisplay.RIGHT);
                toggle.setGraphic(AppIcons.slot(AppIcons.CHEVRON_DOWN, "navigation-chevron", 14)); toggle.setOnAction(event -> toggle());
                header = toggle;
            } else { header = FeaturePane.label("", "navigation-group"); if (id != null) header.setId(id); }
            texts.text(header.textProperty(), key);
            texts.bind(header.accessibleTextProperty(), () -> collapsible
                ? localization.text(expanded ? "nav.section.expanded" : "nav.section.collapsed", localization.text(key)) : localization.text(key));
            FxLocalizer.tooltipWhenClipped(header);
            sidebar.getChildren().add(header); sidebar.getChildren().addAll(items);
        }
        boolean holds(Node node) { return items.contains(node); }
        void toggle() { expanded = !expanded; apply(); }
        void expand() { if (!expanded) { expanded = true; apply(); } }
        void withhold(Node node, boolean value) { if (value) withheld.add(node); else withheld.remove(node); apply(); }
        void apply() {
            for (Node item : items) {
                // The entry of the page on screen stays visible in a collapsed section.
                boolean shown = !withheld.contains(item) && (expanded || item instanceof ToggleButton button && button.isSelected());
                item.setVisible(shown); item.setManaged(shown);
            }
            header.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("collapsed"), !expanded);
            texts.bind(header.accessibleTextProperty(), () -> collapsible
                ? localization.text(expanded ? "nav.section.expanded" : "nav.section.collapsed", localization.text(key)) : localization.text(key));
        }
    }

    PhotoPilotWorkspace() { this(LocalizationService.english()); }
    PhotoPilotWorkspace(LocalizationService localization) {
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        texts.useReadableFont(this);
        detail = new FeaturePane(localization);
        home = new HomeDashboard(localization);
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
        sidebar.getStyleClass().add("sidebar");
        var features = new LinkedHashMap<String, FeaturePresentation>();
        FeaturePresentation.all().forEach(feature -> features.put(feature.id(), feature));
        homeButton = item("nav-home", AppIcons.HOUSE, "home.title", this::showHome);
        for (var feature : features.values()) {
            ToggleButton button = item("nav-" + feature.id(), AppIcons.feature(feature.id()), null, () -> showFeature(feature));
            texts.bind(button.textProperty(), () -> feature.title(localization));
            texts.bind(button.accessibleTextProperty(), () -> feature.title(localization));
            if (feature.status() != FeaturePresentation.Status.IMPLEMENTED) button.getStyleClass().add("navigation-item-planned");
            navigation.put(feature.id(), button);
        }
        shootButton = item("nav-shoot", AppIcons.CHECK, "workflow.milestone.SHOOT", this::showShootCompletion);
        reportButton = item("nav-capability-report", AppIcons.CHART, "nav.capabilityReport", () -> showTool(FeaturePane.Tool.CAPABILITY_REPORT, features.get("F06")));
        shutterButton = item("nav-shutter", AppIcons.IMAGE, "nav.shutterCount", () -> showTool(FeaturePane.Tool.SHUTTER_COUNT, features.get("F05")));
        userSettingsButton = item("nav-user-settings", AppIcons.PERSON, "menu.userSettings", this::showUserSettings);
        applicationSettingsButton = item("nav-app-settings", AppIcons.GEAR, "menu.appSettings", this::showApplicationSettings);

        sessionSidebar = new SessionSidebar(texts, () -> navigation.get("F01").fire());
        var milestones = new LinkedHashMap<Milestone, ToggleButton>();
        for (var milestone : Milestone.values()) milestones.put(milestone, milestone == Milestone.SHOOT ? shootButton : navigation.get(milestone.target()));
        milestones.forEach(sessionSidebar::row);

        sections.add(new Section("nav-group-general", "nav.group.general", false, homeButton, navigation.get("F01"), navigation.get("F02")));
        var session = new ArrayList<Node>(); session.add(sessionSidebar.block()); session.addAll(milestones.values());
        sections.add(new Section("nav-group-session", "nav.group.session", false, session.toArray(Node[]::new)));
        sections.add(new Section("nav-group-equipment", "nav.group.equipment", true, navigation.get("F05"), navigation.get("F04"), reportButton));
        sections.add(new Section("nav-group-tools", "nav.group.tools", true, navigation.get("F08"), shutterButton));
        sections.add(new Section("nav-group-settings", "nav.group.settings", true, userSettingsButton, applicationSettingsButton));
        sections.getLast().withhold(userSettingsButton, true);
        for (var child : sidebar.getChildren())
            if (child instanceof ToggleButton button && button.getStyleClass().contains("navigation-item")) order.add(button);

        navigationScroll = scroll(sidebar, "navigation-scroll");
        navigationScroll.setId("navigation-scroll");
        stableGutter(navigationScroll, sidebar);
        widthProperty().addListener((o, old, value) -> sidebarWidth(navigationScroll, value.doubleValue()));
        sidebarWidth(navigationScroll, SIDEBAR_WIDTH * 2);
        // Arrow keys follow the sidebar's display order across sections (shown entries only); Tab reaches content actions.
        // Handle keys before ToggleGroup traversal, which follows creation order.
        for (ToggleButton button : order) button.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            var shown = order.stream().filter(Node::isManaged).toList();
            int index = shown.indexOf(button);
            if (index < 0) return;
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
                reveal(target);
                event.consume();
            }
        });
        setLeft(navigationScroll);
        // Next-step "Open ..." actions inside pages bubble up here and use the normal sidebar navigation.
        // A continuation computed for a given session opens its page with that session, not with whichever is current.
        addEventHandler(NavigationRequest.NAVIGATE, request -> {
            if (!isDisabled()) request.session().ifPresent(id -> continuation.accept(request.target(), id));
            select(request.target()); request.consume();
        });
        showHome();
    }

    /** Scrolls the sidebar so {@code target} is in view (it may sit below a section heading). */
    private void reveal(Region target) {
        double overflow = sidebar.getBoundsInLocal().getHeight() - navigationScroll.getViewportBounds().getHeight();
        if (overflow <= 0) return;
        double top = sidebar.sceneToLocal(target.localToScene(target.getBoundsInLocal())).getMinY();
        navigationScroll.setVvalue(Math.max(0, Math.min(1, (top - 40) / overflow)));
    }

    /**
     * Shows the page of a stable identifier exactly as its sidebar entry would: F01-F11, HOME, or a session/tool entry
     * ({@link NavigationRequest#SHOOT}, {@link NavigationRequest#SESSION}, {@link NavigationRequest#REPORT},
     * {@link NavigationRequest#SHUTTER}).
     */
    void select(String target) {
        if (isDisabled() || target == null) return;
        switch (target) {
            case HOME -> homeButton.fire();
            case NavigationRequest.SHOOT -> shootButton.fire();
            case NavigationRequest.REPORT -> reportButton.fire();
            case NavigationRequest.SHUTTER -> shutterButton.fire();
            case NavigationRequest.SESSION -> { navigation.get("F01").fire(); sessionOverview.run(); }
            default -> { var button = navigation.get(target); if (button != null) button.fire(); }
        }
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
        // Repeated clicks must not clear the current workspace selection; a collapsed section opens for its selected entry.
        button.setOnAction(event -> {
            button.setSelected(true);
            for (var section : sections) { if (section.holds(button)) section.expand(); section.apply(); }
            action.run();
        });
        return button;
    }
    private void showFeature(FeaturePresentation feature) { showFeature(feature, null, null, feature.id()); }
    private void showFeature(FeaturePresentation feature, FeaturePane.Tool tool, String titleKey, String id) {
        selectedId = id;
        selectionVersion++;
        content.getChildren().setAll(detail);
        detail.show(feature, tool, titleKey);
        contentScroll.setVvalue(0);
    }
    private void showTool(FeaturePane.Tool tool, FeaturePresentation feature) { showFeature(feature, tool, null, tool.name()); }
    /** Milestone 06: the sessions page titled Shooting Completion, at the current session's lifecycle action. */
    private void showShootCompletion() {
        showFeature(FeaturePresentation.all().getFirst(), null, "workflow.milestone.SHOOT", NavigationRequest.SHOOT);
        shootCompletion.run();
    }
    private void showPage(String id, ToggleButton button, Region page) {
        detail.suspendModules();
        selectedId = id; selectionVersion++; button.setSelected(true);
        for (var section : sections) section.apply();
        content.getChildren().setAll(page); contentScroll.setVvalue(0);
    }
    /** Home re-reads the active user's sessions each time it is shown (the same activate-on-show refresh as the feature pages). */
    void showHome() { showPage(HOME, homeButton, home); home.refresh(); }
    /** No load here: during startup the chooser must find the worker queue idle; Home loads when it is next shown. */
    void connectHome(HomeDashboard.Links links) { home.connect(links); }
    boolean atHome() { return HOME.equals(selectedId); }
    void showUserSettings() { showPage(USER_SETTINGS, userSettingsButton, userSettings); userSettings.refresh(); }
    boolean atUserSettings() { return USER_SETTINGS.equals(selectedId); }
    void showApplicationSettings() { showPage(APP_SETTINGS, applicationSettingsButton, applicationSettings); }
    boolean atApplicationSettings() { return APP_SETTINGS.equals(selectedId); }
    void showAvatar(String name, java.util.Optional<byte[]> bytes) {
        avatar.getChildren().setAll(UserAvatar.portrait(name, bytes, 28)); home.avatar(name, bytes);
    }

    /**
     * Joins the window's session workflow model: the Active Session section follows its current session. {@code overview}
     * opens the current session in Photography Sessions; {@code shoot} does so at its lifecycle action. Nothing is read
     * here; the model loads when Home or Photography Sessions is shown.
     */
    void connectWorkflow(SessionWorkflowModel model, Runnable overview, Runnable shoot, java.util.function.BiConsumer<String, java.util.UUID> forSession) {
        workflow = model; sessionOverview = overview; shootCompletion = shoot; continuation = forSession;
        model.listen(this::renderWorkflow);
        renderWorkflow();
    }
    private void renderWorkflow() { sessionSidebar.render(workflow == null ? Optional.empty() : workflow.current()); }
    /** The current session as the sidebar shows it (empty without a model or a session). */
    Optional<com.photopilot.workflow.SessionWorkflow> currentSession() { return workflow == null ? Optional.empty() : workflow.current(); }

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
    void connectShutterCount(ShutterCountPane shutterCount) { detail.connectShutterCount(shutterCount); }
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
        userSettings = pane; sections.getLast().withhold(userSettingsButton, false);
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
            if (!detail.statusText().equals(localization.text("feature.status.IMPLEMENTED")) || detail.statusShown() || !detail.workflowDisabled()) {
                throw new IllegalStateException("Unexpected scaffold availability: " + entry.getKey());
            }
            detail.checkStatusButton().fire();
            String noWorkflow = localization.text("feature.noWorkflow", entry.getValue().getText());
            String expected = entry.getKey().equals("F01") ? localization.text("session.ready")
                : entry.getKey().equals("F02") ? detail.discoveryConnected() ? localization.text("discover.ready") : noWorkflow
                : entry.getKey().equals("F03") ? localization.text("requirement.ready")
                : entry.getKey().equals("F04") ? detail.importConnected() ? localization.text("import.ready") : noWorkflow
                : entry.getKey().equals("F05") ? detail.inventoryConnected() ? localization.text("feature.inventoryReady") : noWorkflow
                : entry.getKey().equals("F08") ? localization.text("feature.calculatorReady")
                : entry.getKey().equals("F06") ? localization.text("capability.scope")
                : entry.getKey().equals("F07") ? detail.matchingConnected() ? localization.text("match.ready") : noWorkflow
                : entry.getKey().equals("F09") ? detail.environmentConnected() ? localization.text("env.ready") : noWorkflow
                : entry.getKey().equals("F10") ? detail.planningConnected() ? localization.text("plan.status") : noWorkflow
                : entry.getKey().equals("F11") ? detail.reviewConnected() ? localization.text("review.ready.status") : noWorkflow
                    : localization.text("feature.unavailable", entry.getValue().getText());
            if (!detail.feedbackText().equals(expected)) {
                throw new IllegalStateException("Missing status feedback: " + entry.getKey());
            }
        }
        // The session and tool entries open their pages (the session ones only when a session is current).
        for (var entry : List.of(shootButton, reportButton, shutterButton)) {
            entry.fire();
            if (!entry.isSelected()) throw new IllegalStateException("Entry not selected: " + entry.getId());
        }
        navigation.get("F01").fire();
        navigation.get("F01").fire();
        if (!navigation.get("F01").isSelected()) throw new IllegalStateException("Selection was cleared");
    }
}
