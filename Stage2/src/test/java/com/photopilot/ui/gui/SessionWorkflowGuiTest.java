package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.application.ProfileManager;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.PhotographySession;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.PlannedState;
import com.photopilot.state.ReviewedState;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import com.photopilot.workflow.SessionWorkflow;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The real workspace and controller over temporary storage: the session-centred sidebar
 * (sections, display order, collapsing, arrow keys, tool entries), the Active Session block following the window's
 * workflow model as the observed stores announce saves, Resume and the session pages' choosers, profile isolation, and
 * the sidebar's layout in both languages. PNGs for visual review are written to target/.
 */
class SessionWorkflowGuiTest {
    private static final PseudoClass RECOMMENDED = PseudoClass.getPseudoClass("recommended"), MUTED = PseudoClass.getPseudoClass("muted"),
        COLLAPSED = PseudoClass.getPseudoClass("collapsed");
    private static final List<String> HEADINGS = List.of("nav-group-general", "nav-group-session", "nav-group-equipment", "nav-group-tools", "nav-group-settings");
    private static final List<String> ENTRIES = List.of("nav-home", "nav-F01", "nav-F02", "sidebar-session", "nav-F03", "nav-F06", "nav-F07", "nav-F09",
        "nav-F10", "nav-shoot", "nav-F11", "nav-F05", "nav-F04", "nav-capability-report", "nav-F08", "nav-shutter", "nav-user-settings", "nav-app-settings");
    @TempDir Path root;
    ProfileManager manager; GuiProfileController controller; Stage stage;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @BeforeEach void launch() throws Exception {
        manager = new ProfileManager(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()));
        var initial = manager.activeProfile().value().orElseThrow();
        action(() -> { ThemeManager.get().select(Theme.Accent.BLUE); ThemeManager.get().select(Theme.Mode.LIGHT);
            stage = new Stage(); controller = new GuiProfileController(stage, manager, initial, context -> { }); });
        idle();
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
        assertTrue(fx(() -> controller.workspace().atHome()));
    }
    @AfterEach void close() throws Exception {
        action(() -> { ThemeManager.get().select(Theme.Accent.BLUE); ThemeManager.get().select(Theme.Mode.SYSTEM);
            if (controller != null) controller.close(); if (stage != null) stage.close(); });
        if (manager != null) manager.close();
    }

    // ---- Helpers. Methods without "throws" read nodes directly and must run on the FX thread (inside fx/action). ------
    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    void size(double width, double height) throws Exception { action(() -> { stage.setWidth(width); stage.setHeight(height); }); idle(); }
    void fire(String id) throws Exception { action(() -> ((ButtonBase) node(id)).fire()); idle(); }
    void language(String tag) throws Exception { action(() -> loc().selectLanguage(tag)); idle(); }
    void key(String id, KeyCode code) throws Exception {
        action(() -> { var entry = node(id); entry.requestFocus(); Event.fireEvent(entry, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)); });
        idle();
    }
    void snapshot(String name) throws Exception { AppearanceGuiTest.settle(); fx(() -> { CalculatorGuiTest.snapshot(controller.workspace(), name); return true; }); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) node(id)).getText()); }
    String selectedEntry() throws Exception {
        return fx(() -> sidebar().getChildren().stream().filter(n -> n instanceof ToggleButton b && b.isSelected()).map(Node::getId).findFirst().orElse(null));
    }
    String focused() throws Exception { return fx(() -> Optional.ofNullable(stage.getScene().getFocusOwner()).map(Node::getId).orElse(null)); }
    /** Sidebar rows drawn as the recommended next milestone. */
    Set<String> recommended() throws Exception {
        return fx(() -> sidebar().getChildren().stream().filter(n -> n.getPseudoClassStates().contains(RECOMMENDED)).map(Node::getId).collect(Collectors.toSet()));
    }
    /** Shown and taking space, for each id. */
    List<Boolean> shown(String... ids) throws Exception { return fx(() -> Arrays.stream(ids).map(id -> node(id).isVisible() && node(id).isManaged()).toList()); }
    UUID chosen(String chooser) throws Exception {
        return fx(() -> ((ComboBox<?>) node(chooser)).getValue() instanceof PhotographySession session ? session.id() : null);
    }
    /** Selects the session's row in Photography Sessions. */
    void pick(UUID id) throws Exception {
        assertTrue(fx(() -> { var row = table().getItems().stream().filter(s -> s.id().equals(id)).findFirst(); row.ifPresent(table().getSelectionModel()::select); return row.isPresent(); }));
    }
    String nextCell(UUID id) throws Exception {
        return fx(() -> String.valueOf(table().getColumns().get(4).getCellData(table().getItems().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow())));
    }

    Node node(String id) { return controller.workspace().lookup("#" + id); }
    VBox sidebar() { return (VBox) ((ScrollPane) node("navigation-scroll")).getContent(); }
    @SuppressWarnings("unchecked") TableView<PhotographySession> table() { return (TableView<PhotographySession>) node("session-table"); }
    FeaturePane page() { return (FeaturePane) controller.workspace().lookup(".feature-content"); }
    String featureTitle() { return ((Labeled) page().lookup(".feature-title")).getText(); }
    String eyebrow() { return ((Labeled) page().lookup(".eyebrow")).getText(); }
    static String rowId(Milestone milestone) { return milestone == Milestone.SHOOT ? "nav-shoot" : "nav-" + milestone.target(); }
    String badgeOf(Milestone milestone) {
        return ((ToggleButton) node(rowId(milestone))).getGraphic().getStyleClass().stream().filter(s -> s.startsWith("milestone-badge-")).collect(Collectors.joining(" "));
    }
    static String badge(MilestoneStatus status) { return "milestone-badge-" + status.name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    /** Visible on screen: the node and all its ancestors. */
    static boolean showing(Node node) {
        if (node == null || node.getScene() == null) return false;
        for (Node at = node; at != null; at = at.getParent()) if (!at.isVisible()) return false;
        return true;
    }
    static Bounds scene(Node node) { return LanguageLayoutGuiTest.scene(node); }

    PhotoPilotFacade app() { return manager.activeContext().facade(); }
    LocalizationService loc() { return manager.activeContext().localization(); }
    String t(String key, Object... args) { return loc().text(key, args); }
    SessionWorkflow progress(UUID id) { return app().workflow().of(id).value().orElseThrow(); }
    /** A new Draft through the facade, so the observed store announces it; the shoot is ahead (04 is still open). */
    PhotographySession create(String title, int daysAhead) {
        return app().sessions().create(title, LocalDate.now().plusDays(daysAhead), Optional.empty(), "").value().orElseThrow();
    }
    /** Confirms a requirement against the stored version, as F03 does. */
    void confirm(UUID id) {
        var stored = app().sessions().open(id).value().orElseThrow();
        assertTrue(app().planning().confirm(stored, HomeDashboardGuiTest.requirement("Portrait")).isSuccess());
    }
    String phase(SessionWorkflow w) { return w.attention() ? t("workflow.phaseAttention", t(w.phase().labelKey())) : t(w.phase().labelKey()); }
    String next(SessionWorkflow w) { return w.next().map(n -> t("progress.next", t(n.milestone().titleKey()))).orElseGet(() -> t("workflow.complete")); }
    /** The one-line context below a session page's chooser. */
    String context(SessionWorkflow w) { return t("workflow.context", t(w.phase().labelKey()), w.progress().render(loc()), next(w)); }
    /** The Active Session block and the seven rows show exactly {@code w}. */
    void sidebarShows(SessionWorkflow w) throws Exception {
        assertEquals(w.title(), text("sidebar-session-title"));
        assertEquals(phase(w), text("sidebar-session-phase"));
        assertEquals(w.progress().render(loc()), text("sidebar-session-count"), "the shared workflow's progress text");
        assertEquals(next(w), text("sidebar-session-next"));
        assertEquals(w.percent() / 100.0, fx(() -> ((ProgressBar) node("sidebar-session-bar")).getProgress()), 1e-9);
        assertNull(fx(() -> node("sidebar-session-empty")), "no empty state beside a session");
        for (var milestone : Milestone.values()) {
            assertEquals(badge(w.status(milestone)), fx(() -> badgeOf(milestone)), milestone + " badge");
            // Never colour alone: the status, its reason and "recommended" are in the row's accessible text.
            var state = w.state(milestone);
            String row = t("workflow.row.accessible", step(milestone), t(milestone.titleKey()), t(state.status().labelKey()), state.detail().render(loc()));
            boolean isNext = w.next().filter(n -> n.milestone() == milestone).isPresent();
            assertEquals(isNext ? t("workflow.row.recommended", row) : row, fx(() -> node(rowId(milestone)).getAccessibleText()));
        }
        assertEquals(w.next().map(n -> Set.of(rowId(n.milestone()))).orElse(Set.of()), recommended(), "exactly the next milestone is recommended");
    }
    static String step(Milestone milestone) { return String.format("%02d", milestone.number()); }

    @Test void sidebarFollowsTheWorkflowOrderWithCollapsibleSectionsKeysAndToolEntries() throws Exception {
        // Headings and entries in display order; every entry is addressable by its id.
        assertEquals(HEADINGS, fx(() -> sidebar().getChildren().stream().filter(n -> n.getStyleClass().contains("navigation-group")).map(Node::getId).toList()));
        assertEquals(ENTRIES, fx(() -> sidebar().getChildren().stream().filter(n -> !n.getStyleClass().contains("navigation-group")).map(Node::getId).toList()));
        assertEquals(List.of(t("nav.group.general"), t("nav.group.session"), t("nav.group.equipment"), t("nav.group.tools"), t("nav.group.settings")),
            fx(() -> HEADINGS.stream().map(id -> ((Labeled) node(id)).getText()).toList()));
        var items = fx(() -> sidebar().lookupAll(".navigation-item").stream().map(Node::getId).toList());
        assertFalse(items.contains(null), "every navigation item has an id: " + items);
        assertEquals(ENTRIES.stream().filter(id -> id.startsWith("nav-")).collect(Collectors.toSet()), new HashSet<>(items));
        assertTrue(fx(() -> ENTRIES.stream().allMatch(id -> node(id).isVisible())), "every section is expanded by default");
        assertNull(fx(() -> node("nav-assistant")));
        assertEquals(List.of(t("feature.F03.title"), t("workflow.milestone.SHOOT"), t("nav.capabilityReport"), t("nav.shutterCount")),
            fx(() -> List.of("nav-F03", "nav-shoot", "nav-capability-report", "nav-shutter").stream().map(id -> ((Labeled) node(id)).getText()).toList()));

        // No session yet: the empty state and its link, neutral numbered rows, no progress anywhere.
        assertTrue(fx(() -> showing(node("sidebar-session-empty")) && showing(node("sidebar-session-choose"))));
        assertEquals(t("workflow.sidebar.empty"), text("sidebar-session-empty"));
        assertEquals(t("workflow.sidebar.choose"), text("sidebar-session-choose"));
        for (String id : List.of("sidebar-session-title", "sidebar-session-phase", "sidebar-session-bar", "sidebar-session-count", "sidebar-session-next"))
            assertNull(fx(() -> node(id)), id + " is not shown without a session");
        for (var milestone : Milestone.values()) {
            assertEquals("milestone-badge-none", fx(() -> badgeOf(milestone)));
            assertEquals(t("workflow.row.plain", step(milestone), t(milestone.titleKey())), fx(() -> node(rowId(milestone)).getAccessibleText()));
        }
        assertEquals(Set.of(), recommended());
        snapshot("sidebar-empty");
        fire("sidebar-session-choose");
        assertEquals("nav-F01", selectedEntry(), "Choose a session opens Photography Sessions");
        assertFalse(fx(() -> controller.workspace().atHome()));

        // A milestone row's text is its page's title; the page names its workflow step.
        fire("nav-F03");
        assertEquals(text("nav-F03"), fx(this::featureTitle));
        assertEquals(t("workflow.eyebrow", "01", "07"), fx(this::eyebrow));

        // Up/Down follow the display order across sections (not the toggle group's creation order).
        key("nav-F02", KeyCode.DOWN); assertEquals("nav-F03", selectedEntry()); assertEquals("nav-F03", focused());
        key("nav-F03", KeyCode.UP); assertEquals("nav-F02", selectedEntry());
        key("nav-F11", KeyCode.DOWN); assertEquals("nav-F05", selectedEntry()); assertEquals("nav-F05", focused());
        key("nav-F05", KeyCode.UP); assertEquals("nav-F11", selectedEntry());
        key("nav-shutter", KeyCode.DOWN); assertEquals("nav-user-settings", selectedEntry()); assertEquals("nav-user-settings", focused());
        key("nav-user-settings", KeyCode.UP); assertEquals("nav-shutter", selectedEntry()); assertEquals("nav-shutter", focused());

        // Collapsing Equipment hides its entries and arrow keys skip them; firing the heading again shows them.
        fire("nav-F03");
        fire("nav-group-equipment");
        assertEquals(List.of(false, false, false), shown("nav-F05", "nav-F04", "nav-capability-report"));
        assertTrue(fx(() -> node("nav-group-equipment").getPseudoClassStates().contains(COLLAPSED)));
        assertEquals(t("nav.section.collapsed", t("nav.group.equipment")), fx(() -> node("nav-group-equipment").getAccessibleText()));
        key("nav-F11", KeyCode.DOWN); assertEquals("nav-F08", selectedEntry(), "hidden entries are skipped");
        fire("nav-group-equipment");
        assertEquals(List.of(true, true, true), shown("nav-F05", "nav-F04", "nav-capability-report"));
        assertEquals(t("nav.section.expanded", t("nav.group.equipment")), fx(() -> node("nav-group-equipment").getAccessibleText()));
        // Selecting a collapsed entry (a page's next-step request, or select) opens its section again.
        fire("nav-group-equipment");
        action(() -> Event.fireEvent(node("sidebar-session"), new NavigationRequest("F05"))); idle();
        assertEquals("nav-F05", selectedEntry());
        assertEquals(List.of(true, true, true), shown("nav-F05", "nav-F04", "nav-capability-report"));
        assertFalse(fx(() -> node("nav-group-equipment").getPseudoClassStates().contains(COLLAPSED)));
        // The entry of the page on screen stays visible in its collapsed section.
        fire("nav-group-equipment");
        assertEquals(List.of(true, false, false), shown("nav-F05", "nav-F04", "nav-capability-report"));
        fire("nav-group-equipment");
        fire("nav-group-tools");
        assertEquals(List.of(false, false), shown("nav-F08", "nav-shutter"));
        action(() -> controller.workspace().select(NavigationRequest.SHUTTER)); idle();
        assertEquals("nav-shutter", selectedEntry());
        assertEquals(List.of(true, true), shown("nav-F08", "nav-shutter"));

        // The capability report can still be opened separately from the session's gear requirements.
        fire("nav-capability-report");
        assertEquals("nav-capability-report", selectedEntry());
        assertEquals(t("nav.capabilityReport"), fx(this::featureTitle));
        assertFalse(fx(() -> showing(controller.capability().profile()) || showing(node("needs-session"))), "the needs part is hidden");
        assertTrue(fx(() -> showing(node("capability-refresh"))), "the report part is shown");
        fire("nav-F06");
        assertTrue(fx(() -> showing(controller.capability().profile()) && showing(node("capability-refresh"))), "F06 shows both parts again");
        // Camera Shutter Count and Gear Inventory each own their workspace contents.
        fire("nav-shutter");
        assertTrue(fx(() -> showing(node("shutter-count"))));
        assertNull(fx(() -> node("gear-table")));
        fire("nav-F05");
        assertNull(fx(() -> node("shutter-count")));
        for (String id : List.of("gear-table", "gear-search", "gear-filter", "gear-add", "gear-import", "gear-edit", "gear-remove"))
            assertTrue(fx(() -> showing(node(id))), id);
        fire("gear-import"); assertEquals("nav-F04", selectedEntry());
        fire("nav-home");
        assertTrue(fx(() -> controller.workspace().atHome()));
        assertEquals("nav-home", selectedEntry());
        assertTrue(fx(() -> showing(node("home-ai-prompt")) && showing(node("home-ai-generate")) && showing(node("home-chip-tonight"))));
        fire("home-chip-tonight");
        assertEquals(t("home.ai.chip.tonight.prompt"), fx(() -> ((TextArea) node("home-ai-prompt")).getText()));
        fire("nav-F02"); assertEquals("nav-F02", selectedEntry());
        assertEquals(t("feature.F02.title"), fx(this::featureTitle));
    }

    @Test void inventoryAndShutterKeepTheirOwnControlsAcrossLanguageAndThemeChanges() throws Exception {
        fire("nav-F05");
        action(() -> ((TextField) node("gear-search")).setText("Camera"));
        for (var mode : List.of(Theme.Mode.LIGHT, Theme.Mode.DARK)) {
            action(() -> ThemeManager.get().select(mode));
            for (String tag : List.of("en", "zh-CN")) {
                fire("nav-shutter");
                language(tag);
                assertEquals(t("nav.shutterCount"), text("nav-shutter"));
                assertEquals(t("shutter.import.choose"), text("shutter-choose"));
                assertTrue(fx(() -> showing(node("shutter-import")) && showing(node("shutter-models-link"))));
                assertNull(fx(() -> node("gear-table")));
                snapshot("nav-shutter-" + mode + "-" + tag);
                fire("nav-F05");
                assertEquals(t("feature.F05.title"), fx(this::featureTitle));
                assertEquals("Camera", fx(() -> ((TextField) node("gear-search")).getText()));
                assertTrue(fx(() -> showing(node("gear-import")) && showing(node("gear-table"))));
                assertNull(fx(() -> node("shutter-count")));
                snapshot("nav-inventory-" + mode + "-" + tag);
            }
        }
    }

    @Test void activeSessionFollowsSavesAtOnceAndResumeOpensTheRecommendedPage() throws Exception {
        // A page that does not reload sessions by itself: only the stores' announcements can update the sidebar.
        fire("nav-F06");
        long version = fx(() -> controller.workspace().selectionVersion());
        var lake = create("Lakeside dawn", 30);
        Thread.sleep(30);
        var harbour = create("Harbour lights", 40);
        AppearanceGuiTest.settle();
        var fresh = progress(harbour.id());
        assertEquals(SessionWorkflow.Phase.GETTING_STARTED, fresh.phase());
        assertEquals(t("workflow.progress", 0, 0, 7), fresh.progress().render(loc()));
        sidebarShows(fresh);   // the latest unfinished session is current
        assertEquals(version, (long) fx(() -> controller.workspace().selectionVersion()), "no navigation happened");
        assertEquals("nav-F06", selectedEntry());
        assertEquals(Set.of("nav-F03"), recommended(), "the recommended row is not the current page's row");
        assertFalse(fx(() -> node("nav-F06").getPseudoClassStates().contains(RECOMMENDED)));
        assertEquals("milestone-badge-not-started", fx(() -> badgeOf(Milestone.REQUIREMENTS)));
        assertEquals("milestone-badge-blocked", fx(() -> badgeOf(Milestone.EQUIPMENT_NEEDS)));
        assertEquals("milestone-badge-not-started", fx(() -> badgeOf(Milestone.ENVIRONMENT)), "optional, the shoot is ahead");
        assertEquals("milestone-badge-blocked", fx(() -> badgeOf(Milestone.SHOOT)));
        assertTrue(fx(() -> node("nav-F06").getPseudoClassStates().contains(MUTED) && !node("nav-F03").getPseudoClassStates().contains(MUTED)), "blocked rows are muted");

        // A requirement confirmed elsewhere (as F03 saves it) moves the count and the recommendation at once.
        confirm(harbour.id());
        AppearanceGuiTest.settle();
        var planning = progress(harbour.id());
        assertEquals(t("workflow.progress", 14, 1, 7), planning.progress().render(loc()));
        assertEquals(SessionWorkflow.Phase.PLANNING, planning.phase());
        sidebarShows(planning);
        assertEquals(Set.of("nav-F06"), recommended());
        assertEquals("milestone-badge-completed", fx(() -> badgeOf(Milestone.REQUIREMENTS)));
        assertEquals(version, (long) fx(() -> controller.workspace().selectionVersion()), "still no navigation");

        // Photography Sessions lists each session's next milestone; Resume opens it with the page's chooser on that session.
        fire("nav-F01");
        assertEquals(t("feature.F06.title"), nextCell(harbour.id()));
        assertEquals(t("feature.F03.title"), nextCell(lake.id()));
        pick(lake.id());
        assertEquals(t("session.resume"), text("session-resume"));
        fire("session-resume");
        assertEquals("nav-F03", selectedEntry());
        assertEquals(lake.id(), chosen("requirement-session"));
        var lakeProgress = progress(lake.id());
        sidebarShows(lakeProgress);   // an explicit choice wins over the latest session
        assertTrue(fx(() -> showing(node("requirement-session-context"))));
        assertEquals(context(lakeProgress), text("requirement-session-context"));

        // The current Draft cannot be shown on the review page: nothing is selected (although a reviewed session is
        // listed there) and the context line names the session instead of silently showing another.
        var reviewed = new JsonSessionRepository(manager.activeContext().storageScope().sessionsDirectory())
            .insert(SessionFixtures.linked(new ReviewedState(), List.of(), true)).value().orElseThrow();
        fire("nav-F11");
        assertNull(chosen("review-session"));
        assertEquals(1, (int) fx(() -> ((ComboBox<?>) node("review-session")).getItems().size()));
        assertTrue(fx(() -> showing(node("review-session-context"))));
        assertEquals(t("workflow.chooser.unavailable", "Lakeside dawn", t("session.state.DRAFT")), text("review-session-context"));

        // Back in the list (which re-reads the store): progress per row, and a reviewed session's action is View review.
        fire("nav-F01");
        var done = progress(reviewed.id());
        assertTrue(done.complete()); assertEquals(100, done.percent());
        var cells = fx(() -> table().lookupAll(".session-progress-text").stream().map(n -> ((Labeled) n).getText()).collect(Collectors.toSet()));
        for (var w : List.of(planning, lakeProgress, done))
            assertTrue(cells.contains(t("session.progressCell", w.percent(), w.completed(), w.applicable())), w.title() + " in " + cells);
        assertEquals(t("workflow.complete"), nextCell(reviewed.id()));
        pick(reviewed.id());
        snapshot("sessions-table");
        assertEquals(t("session.viewReview"), text("session-resume"));
        pick(lake.id()); assertEquals(t("session.resume"), text("session-resume"));
        pick(reviewed.id());
        fire("session-resume");
        assertEquals("nav-F11", selectedEntry());
        assertEquals(reviewed.id(), chosen("review-session"));
        assertEquals(context(done), text("review-session-context"));
        sidebarShows(done);
        assertEquals(Set.of(), recommended(), "a complete session recommends nothing");

        // Resuming the planning session opens Gear Requirements on it.
        fire("nav-F01"); pick(harbour.id()); fire("session-resume");
        assertEquals("nav-F06", selectedEntry());
        assertEquals(harbour.id(), chosen("needs-session"));
        assertEquals(context(planning), text("needs-session-context"));

        // Shooting Completion: the sessions page under the milestone's name, at the current session's lifecycle action.
        fire("nav-shoot");
        assertEquals("nav-shoot", selectedEntry());
        assertEquals(t("workflow.milestone.SHOOT"), fx(this::featureTitle));
        assertEquals(t("workflow.eyebrow", "06", "07"), fx(this::eyebrow));
        assertTrue(fx(() -> showing(node("session-lifecycle"))));
        assertEquals("Harbour lights", fx(() -> ((TextField) node("session-field-title")).getText()));
        snapshot("shoot-completion");

        // The plan page selects the current session and shows its context line.
        fire("nav-F10");
        assertEquals(harbour.id(), chosen("plan-session"));
        assertTrue(fx(() -> showing(node("plan-session-context"))));
        assertEquals(context(planning), text("plan-session-context"));
        snapshot("f10-context");

        // Another user's window has no current session.
        UUID other = manager.createProfile("Other").value().orElseThrow().id();
        action(() -> controller.requestSwitch(other)); idle();
        assertTrue(fx(() -> controller.workspace().atHome()));
        assertTrue(fx(() -> showing(node("sidebar-session-empty"))));
        assertNull(fx(() -> node("sidebar-session-title")));
        assertEquals(Set.of(), recommended());
        for (var milestone : Milestone.values()) assertEquals("milestone-badge-none", fx(() -> badgeOf(milestone)));
    }

    @Test void aContinuationOpensTheSessionItWasComputedForAndEditedEnvironmentInputsKeepTheirSession() throws Exception {
        var lake = create("Lakeside dawn", 30);
        Thread.sleep(30);
        var harbour = create("Harbour lights", 40);
        confirm(harbour.id());   // Harbour continues with Gear Requirements, Lake with Shooting Requirements
        AppearanceGuiTest.settle();

        // Lake's editor stays open while Resume makes Harbour the current session.
        fire("nav-F01");
        pick(lake.id()); fire("session-open");
        assertEquals("Lakeside dawn", text("sidebar-session-title"), "opening a session chooses it");
        pick(harbour.id()); fire("session-resume");
        assertEquals("nav-F06", selectedEntry());
        assertEquals("Harbour lights", text("sidebar-session-title"));
        fire("nav-F01");
        assertEquals("Lakeside dawn", fx(() -> ((TextField) node("session-field-title")).getText()), "the editor is still on Lake");
        // Its Continue names Lake's next milestone and opens it with Lake, not with the current Harbour.
        assertEquals(t("progress.continue", t("feature.F03.title")), text("session-next-open"));
        fire("session-next-open");
        assertEquals("nav-F03", selectedEntry());
        assertEquals(lake.id(), chosen("requirement-session"));
        sidebarShows(progress(lake.id()));
        // A request without a session ("Open ...") keeps the current session.
        action(() -> Event.fireEvent(node("sidebar-session"), new NavigationRequest("F10"))); idle();
        assertEquals("nav-F10", selectedEntry());
        assertEquals(lake.id(), chosen("plan-session"));
        assertEquals("Lakeside dawn", text("sidebar-session-title"));

        // Environment Assessment: edited inputs keep their session when another one becomes current ...
        fire("nav-F09");
        assertEquals(lake.id(), chosen("env-session"));
        action(() -> ((TextField) node("env-location")).setText("Toronto Islands"));
        fire("nav-F01"); pick(harbour.id()); fire("session-resume");
        assertEquals("Harbour lights", text("sidebar-session-title"));
        fire("nav-F09");
        assertEquals(lake.id(), chosen("env-session"), "edited inputs keep their session");
        assertEquals("Toronto Islands", fx(() -> ((TextField) node("env-location")).getText()), "and are not prefilled again");
        assertEquals(context(progress(lake.id())), text("env-session-context"), "the context line names the shown session");
        // ... while a page without a draft follows the current session.
        fire("nav-F03");
        assertEquals(harbour.id(), chosen("requirement-session"));
    }

    @Test void overdueShootAsksForActionAndShootingCompletionFocusesTheLifecycleAction() throws Exception {
        // A Planned session whose shooting date (fixture: 2026-11-01) has passed: recording the shoot is due now.
        var planned = new JsonSessionRepository(manager.activeContext().storageScope().sessionsDirectory())
            .insert(SessionFixtures.linked(new PlannedState(), List.of(), false)).value().orElseThrow();
        fire("nav-home");   // Home re-reads the store (this insert bypassed the observed one)
        var due = progress(planned.id());
        assertEquals(MilestoneStatus.ACTION_REQUIRED, due.status(Milestone.SHOOT));
        assertEquals(t("workflow.phaseAttention", t("workflow.phase.READY")), phase(due));
        sidebarShows(due);
        assertEquals(Set.of("nav-shoot"), recommended());
        assertEquals("milestone-badge-action-required", fx(() -> badgeOf(Milestone.SHOOT)));
        assertEquals("milestone-badge-not-applicable", fx(() -> badgeOf(Milestone.ENVIRONMENT)), "optional, skipped once planned");
        assertTrue(fx(() -> node("nav-F09").getPseudoClassStates().contains(MUTED)), "not-applicable rows are muted");
        snapshot("sidebar-action-required");
        // Shooting Completion opens that session's editor; since the shoot day has come, the lifecycle action has the focus.
        fire("nav-shoot");
        assertTrue(fx(() -> showing(node("session-lifecycle"))));
        assertEquals(planned.title(), fx(() -> ((TextField) node("session-field-title")).getText()));
        assertEquals("session-transition", focused());
    }

    @Test void sidebarKeepsItsGeometryInBothLanguagesAndShortensALongTitleToOneLine() throws Exception {
        var lake = create("Lakeside dawn", 30); confirm(lake.id());
        fire("nav-home");
        size(1120, 760);
        sidebarShows(progress(lake.id()));
        snapshot("sidebar-planning-en");
        language("zh-CN");
        sidebarShows(progress(lake.id()));   // the same workflow, in Chinese
        snapshot("sidebar-planning-zh");

        // Every check of LanguageLayoutGuiTest, with a session, on Home and on a session page; rows keep one geometry.
        for (double[] window : new double[][] { { 1120, 760 }, { 880, 600 } }) for (String entry : List.of("nav-home", "nav-F10")) {
            language("en"); size(window[0], window[1]); fire(entry);
            Map<String, Map<String, Bounds>> geometry = new LinkedHashMap<>();
            for (String tag : List.of("en", "zh-CN")) {
                language(tag);
                String where = entry + " " + (int) window[0] + "x" + (int) window[1] + " " + tag;
                assertEquals(entry, selectedEntry(), where);
                fx(() -> { LanguageLayoutGuiTest.check(controller.workspace(), where); return true; });
                geometry.put(tag, fx(() -> {
                    var found = LanguageLayoutGuiTest.measure(controller.workspace());
                    found.put("sidebar-session", scene(node("sidebar-session")));
                    return found;
                }));
            }
            var en = geometry.get("en"); var zh = geometry.get("zh-CN");
            assertEquals(en.keySet(), zh.keySet());
            assertTrue(en.keySet().containsAll(ENTRIES), "measured: " + en.keySet());
            for (String id : en.keySet()) {
                Bounds a = en.get(id), b = zh.get(id);
                String where = entry + " " + (int) window[0] + " " + id;
                assertEquals(a.getMinX(), b.getMinX(), .5, where + " x"); assertEquals(a.getWidth(), b.getWidth(), .5, where + " width");
                if (id.startsWith("nav-") || id.startsWith("sidebar-") || id.equals("navigation-scroll")) {
                    assertEquals(a.getMinY(), b.getMinY(), .5, where + " y"); assertEquals(a.getHeight(), b.getHeight(), .5, where + " height");
                }
            }
        }

        language("en"); size(1120, 760); fire("nav-home");
        try { action(() -> ThemeManager.get().select(Theme.Mode.DARK)); idle(); snapshot("sidebar-dark"); }
        finally { action(() -> ThemeManager.get().select(Theme.Mode.LIGHT)); idle(); }

        // A 150-character title stays one shortened line inside the sidebar (readable as tooltip and accessible name).
        size(880, 600);
        double rows = fx(() -> scene(node("nav-F03")).getMinY());
        String title = "Harbour lights and long exposures ".repeat(5).substring(0, 150);
        create(title, 50);
        AppearanceGuiTest.settle();
        assertEquals(title, text("sidebar-session-title"), "the newest unfinished session is current");
        for (String tag : List.of("en", "zh-CN")) {
            language(tag);
            fx(() -> {
                var label = (Label) node("sidebar-session-title");
                var rendered = label.getChildrenUnmodifiable().stream().filter(Text.class::isInstance).map(Text.class::cast).findFirst().orElseThrow();
                assertNotEquals(title, rendered.getText(), "shortened");
                assertTrue(rendered.getText().endsWith(label.getEllipsisString()), rendered.getText());
                assertTrue(label.getHeight() < 2 * label.getFont().getSize(), tag + ": one line, height " + label.getHeight());
                Bounds line = scene(label), block = scene(node("sidebar-session")), side = scene(node("navigation-scroll"));
                assertTrue(line.getMinX() >= block.getMinX() - .5 && line.getMaxX() <= block.getMaxX() + .5, tag + ": inside the block " + line + " / " + block);
                assertTrue(block.getMaxX() <= side.getMaxX() + .5, tag + ": inside the sidebar");
                assertNotNull(label.getTooltip(), "the full title is readable");
                LanguageLayoutGuiTest.check(controller.workspace(), "long title " + tag);
                return true;
            });
            assertEquals(rows, fx(() -> scene(node("nav-F03")).getMinY()), .5, tag + ": the rows below do not move");
            snapshot("sidebar-long-title-" + tag);
        }
        language("en");
    }
}
