package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The real workspace and controller over temporary storage: Home's four session states are
 * driven only by stored sessions (controlled fixtures, never demonstration data), the cards open the existing F01/F11
 * workflows, the layout holds at the reference and minimum window sizes in both languages, and Mini AI sends nothing.
 */
class HomeDashboardGuiTest {
    private static final Instant CREATED = Instant.parse("2026-10-31T10:00:00Z");
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

    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    void home() throws Exception { action(() -> ((ToggleButton) controller.workspace().lookup("#nav-home")).fire()); idle(); }
    void size(double width, double height) throws Exception { action(() -> { stage.setWidth(width); stage.setHeight(height); }); idle(); }
    /** Direct lookup; call it on the FX thread (inside fx/action). */
    Node node(String id) { return controller.workspace().lookup("#" + id); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) controller.workspace().lookup("#" + id)).getText()); }
    void fire(String id) throws Exception { action(() -> ((Button) controller.workspace().lookup("#" + id)).fire()); idle(); }
    String t(String key, Object... args) { return manager.activeContext().localization().text(key, args); }
    JsonSessionRepository sessions() { return new JsonSessionRepository(manager.activeContext().storageScope().sessionsDirectory()); }
    static Bounds scene(Node node) { return node.localToScene(node.getLayoutBounds()); }
    /** Which session cards Home shows, in order; an empty row must be collapsed, not kept as invisible space. */
    List<String> cards() throws Exception {
        return fx(() -> {
            var row = (HBox) controller.workspace().lookup("#home-sessions");
            var shown = row.getChildren().stream().filter(Node::isVisible).map(Node::getId).toList();
            assertEquals(!shown.isEmpty(), row.isVisible() && row.isManaged(), "row is collapsed exactly when it has no card");
            assertEquals(shown.size(), row.getChildren().size(), "no hidden placeholder card");
            return shown;
        });
    }

    static ShootRequirement requirement(String subject) {
        return new ShootRequirement("Brief", subject, List.of("Light"), Optional.empty(), Optional.empty(), Duration.ofHours(1), 1, true,
            Optional.empty(), List.of(), Map.of(), Map.of(), List.of(), true);
    }
    /**
     * A Draft with a confirmed requirement and a confirmed capability profile (one real need, as confirmation requires)
     * but no gear selection or plan yet: its next milestone is Gear Selection.
     */
    static PhotographySession draft(String title, String subject, Instant updated) {
        var profile = new EquipmentRequirementProfile(List.of(CapabilityNeed.of("focalLength", Optional.of(16.0), Optional.of(35.0), 1, true, "")), true);
        return new PhotographySession(UUID.randomUUID(), title, LocalDate.of(2026, 11, 14), Optional.empty(), "", CREATED, updated, new DraftState(),
            Optional.of(requirement(subject)), List.of(), List.of(), Optional.of(profile), Optional.empty(), List.of());
    }
    /** A linked fixture (approved plan, statistics, optional saved review) renamed for this test. */
    static PhotographySession shot(String title, String subject, SessionState state, boolean review, Instant updated) {
        var base = SessionFixtures.linked(state, List.of(), review);
        return new PhotographySession(UUID.randomUUID(), title, LocalDate.of(2026, 11, 1), base.location(), "", CREATED, updated, state,
            Optional.of(requirement(subject)), base.plans(), base.reviews(), base.profile(), base.statistics(), List.of(), base.selectedLoadout());
    }

    @Test void fourStatesFollowStoredSessionsAndCardsOpenTheExistingWorkflows() throws Exception {
        action(() -> manager.activeContext().localization().selectLanguage("zh-CN"));
        size(1194, 843);
        // State 4: a brand-new user sees the welcome and Mini AI cards only, with no empty row or placeholder.
        assertEquals(List.of(), cards());
        assertTrue(fx(() -> node("home-welcome").isVisible() && node("home-inspiration").isVisible()));
        assertEquals(List.of("home-welcome", "home-inspiration"), fx(() -> ((VBox) node("home-dashboard")).getChildren().stream().filter(Node::isManaged).map(Node::getId).toList()));
        snapshot("home-state4-new-user");
        // Removed elements take no space: the welcome card holds only the avatar and greeting, and the Mini AI card starts
        // with the question itself at the card's top padding, with no header, icon box or badge anywhere in the card.
        assertNull(fx(() -> node("home-new-session")));
        assertEquals(2, (int) fx(() -> ((HBox) node("home-welcome")).getChildren().size()));
        assertEquals(List.of("home-ai-question", "home-ai-prompt", "home-ai-status", "home-ai-response"), fx(() -> ((VBox) node("home-inspiration")).getChildren().stream()
            .map(Node::getId).filter(id -> id != null).toList()), "question first; the description and actions row carry no id");
        assertEquals(6, (int) fx(() -> ((VBox) node("home-inspiration")).getChildren().size()));
        // Before any question the status line and the answer area take no space: no empty conversation panel.
        assertEquals(4L, (long) fx(() -> ((VBox) node("home-inspiration")).getChildren().stream().filter(Node::isManaged).count()));
        assertFalse(fx(() -> node("home-ai-status").isVisible() || node("home-ai-response").isVisible()));
        assertEquals(0, (int) fx(() -> node("home-inspiration").lookupAll(".home-icon-box").size() + node("home-inspiration").lookupAll(".home-pill").size()));
        assertTrue(fx(() -> node("home-inspiration").lookupAll(".label").stream().noneMatch(label -> "Mini AI".equals(((Labeled) label).getText()))), "no Mini AI badge");
        assertEquals(fx(() -> ((Region) node("home-inspiration")).getInsets().getTop()), fx(() -> node("home-ai-question").getBoundsInParent().getMinY()), .5);
        // The empty input is 64 px (about two lines) and the layout cannot squeeze it; the chips and Generate stay 16 px
        // below it and inside the card's padding.
        assertEquals(64.0, fx(() -> ((TextArea) node("home-ai-prompt")).getHeight()), .5);
        assertEquals(64.0, fx(() -> ((TextArea) node("home-ai-prompt")).minHeight(-1)), .5, "its minimum is its default height");
        fx(() -> {
            var card = (VBox) node("home-inspiration"); var prompt = card.getChildren().get(2); var actions = card.getChildren().get(3);
            assertEquals(16.0, actions.getLayoutY() - (prompt.getLayoutY() + prompt.getLayoutBounds().getHeight()), .5, "gap to chips and Generate");
            assertTrue(actions.getLayoutY() + actions.getLayoutBounds().getHeight() <= card.getHeight() - card.getInsets().getBottom() + .5, "actions not clipped");
            return true;
        });
        // It keeps multiline text: three lines grow it, and beyond 120 px it scrolls.
        action(() -> ((TextArea) node("home-ai-prompt")).setText("第一行\n第二行\n第三行"));
        assertTrue(fx(() -> ((TextArea) node("home-ai-prompt")).isWrapText() && ((TextArea) node("home-ai-prompt")).getText().lines().count() == 3));
        AppearanceGuiTest.settle();
        double grown = fx(() -> ((TextArea) node("home-ai-prompt")).getHeight());
        assertTrue(grown > 64.5 && grown < 119.5, "three lines grow the input: " + grown);
        action(() -> { var prompt = (TextArea) node("home-ai-prompt"); prompt.setText(String.join("\n", Collections.nCopies(20, "城市夜景"))); prompt.end(); });
        AppearanceGuiTest.settle();
        assertEquals(120.0, fx(() -> ((TextArea) node("home-ai-prompt")).getHeight()), .5);
        assertTrue(fx(() -> node("home-ai-prompt").lookupAll(".scroll-bar").stream()
            .anyMatch(bar -> ((ScrollBar) bar).getOrientation() == Orientation.VERTICAL && bar.isVisible())), "vertical scroll bar for long text");
        assertTrue(fx(() -> { var scroll = (ScrollPane) node("home-ai-prompt").lookup(".scroll-pane");
            return scroll.getContent().getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight() + 50; }), "text taller than the visible area");
        action(() -> ((TextArea) node("home-ai-prompt")).clear());
        AppearanceGuiTest.settle();
        assertEquals(64.0, fx(() -> ((TextArea) node("home-ai-prompt")).getHeight()), .5, "clearing returns to the default height");

        // Generate is available and says what it sends; a chip only fills the input (HomeMiniAiGuiTest covers requests).
        assertFalse(fx(() -> node("home-ai-generate").isDisabled()));
        assertEquals(t("home.ai.privacy"), fx(() -> node("home-ai-generate").getAccessibleHelp()));
        fire("home-chip-tonight");
        assertEquals(t("home.ai.chip.tonight.prompt"), fx(() -> ((TextArea) node("home-ai-prompt")).getText()));
        assertFalse(fx(() -> controller.busy()), "no request was started");
        action(() -> ((TextArea) node("home-ai-prompt")).clear());

        // State 2: only an unfinished session -> "Continue" spans the whole row.
        var lake = sessions().insert(draft("湖畔蓝调时刻", "风光摄影", Instant.parse("2026-11-05T09:00:00Z"))).value().orElseThrow();
        home();
        assertEquals(List.of("home-continue"), cards());
        assertEquals("湖畔蓝调时刻", text("home-continue-title"));
        // Phase, progress and next step come from the shared session workflow (the sidebar shows the same).
        var lakeProgress = manager.activeContext().facade().workflow().of(lake.id()).value().orElseThrow();
        assertEquals(com.photopilot.workflow.Milestone.GEAR_SELECTION, lakeProgress.next().orElseThrow().milestone(), "requirement and profile confirmed");
        assertEquals(t("workflow.phase.PLANNING"), text("home-continue-status"));
        assertEquals(t("workflow.next.GEAR_SELECTION"), text("home-continue-description"));
        assertEquals("风光摄影 · " + t("feature.F07.title"), text("home-continue-meta"));
        assertEquals(lakeProgress.progress().render(manager.activeContext().localization()), text("home-continue-progress-text"));
        assertEquals(t("workflow.progress", lakeProgress.percent(), 2, lakeProgress.applicable()), text("home-continue-progress-text"));
        assertEquals(lakeProgress.title(), text("sidebar-session-title"), "the sidebar shows the same current session");
        assertEquals(text("home-continue-progress-text"), text("sidebar-session-count"));
        assertEquals(fx(() -> scene(node("home-sessions")).getWidth()), fx(() -> scene(node("home-continue")).getWidth()), .5, "single card spans the row");
        assertEquals(fx(() -> scene(node("home-inspiration")).getWidth()), fx(() -> scene(node("home-continue")).getWidth()), .5, "same width as Mini AI");
        snapshot("home-state2-continue-only");

        // State 1: both -> Continue left, After the shoot right, equal widths and heights.
        var street = sessions().insert(shot("周末城市街拍", "纪实摄影", new CompletedState(), false, Instant.parse("2026-11-03T18:00:00Z"))).value().orElseThrow();
        home();
        assertEquals(List.of("home-continue", "home-after"), cards());
        assertEquals("周末城市街拍", text("home-after-title"));
        assertEquals(t("home.status.toReview"), text("home-after-status"));
        assertEquals(t("home.after.review"), text("home-after-description"), "saved statistics: the next step is the review");
        // PP-01: the fixture's statistics have no capture time, so the card names the scheduled date, not a shooting date.
        assertEquals("纪实摄影 · " + t("session.dates.scheduled", "2026-11-01"), text("home-after-meta"));
        assertEquals(t("home.after.start"), text("home-review-action"));
        Bounds left = fx(() -> scene(node("home-continue"))), right = fx(() -> scene(node("home-after")));
        assertEquals(left.getWidth(), right.getWidth(), 1); assertEquals(left.getHeight(), right.getHeight(), .5);
        assertEquals(left.getMinY(), right.getMinY(), .5); assertTrue(left.getMaxX() < right.getMinX());
        snapshot("home-state1-both");
        // 1430 x 1055 is the user's reference window; the empty input keeps its default height at every size.
        for (String tag : List.of("en", "zh-CN")) for (double[] window : new double[][] { { 1430, 1055 }, { 1194, 843 }, { 880, 600 } }) {
            action(() -> manager.activeContext().localization().selectLanguage(tag)); size(window[0], window[1]);
            assertEquals(window[0], fx(stage::getWidth), .5); assertEquals(window[1], fx(stage::getHeight), .5);
            fx(() -> { LanguageLayoutGuiTest.check(controller.workspace(), "home both " + tag + " " + (int) window[0]); return true; });
            assertEquals(fx(() -> scene(node("home-continue")).getWidth()), fx(() -> scene(node("home-after")).getWidth()), 1);
            assertEquals(64.0, fx(() -> ((TextArea) node("home-ai-prompt")).getHeight()), .5, "input height at " + (int) window[0]);
            fx(() -> {
                // Generate stays vertically centred on the chips at every size (narrower windows may wrap the chips, as
                // before); at the reference size the four chips share one row, centred with Generate.
                var chips = node("home-ai-chips").lookupAll(".home-chip").stream().map(HomeDashboardGuiTest::scene).toList();
                var generate = scene(node("home-ai-generate"));
                assertEquals(4, chips.size());
                assertEquals(scene(node("home-ai-chips")).getCenterY(), generate.getCenterY(), 1, "Generate centred on the chips");
                if (window[0] == 1430) for (var chip : chips) {
                    assertEquals(chips.get(0).getMinY(), chip.getMinY(), .5, "one row of chips");
                    assertEquals(generate.getCenterY(), chip.getCenterY(), 1, "chips centred with Generate");
                }
                return true;
            });
            snapshot("home-state1-both-" + tag + "-" + (int) window[0]);
            if (window[0] < 1000) {
                action(() -> ((ScrollPane) stage.getScene().getRoot().lookup(".content-scroll")).setVvalue(1));
                snapshot("home-state1-both-" + tag + "-" + (int) window[0] + "-scrolled");
            }
        }
        action(() -> { ThemeManager.get().select(Theme.Mode.DARK); manager.activeContext().localization().selectLanguage("zh-CN"); });
        size(1194, 843); snapshot("home-state1-both-dark");
        action(() -> ThemeManager.get().select(Theme.Mode.LIGHT)); idle();

        // The cards open the existing workflows for exactly that session: Continue resumes at its recommended page.
        fire("home-continue-action");
        assertFalse(fx(() -> controller.workspace().atHome()));
        assertTrue(fx(() -> ((ToggleButton) node("nav-F07")).isSelected()), "resumed at Gear Selection");
        assertEquals(lake.id(), fx(() -> ((PhotographySession) ((ComboBox<?>) node("match-session")).getValue()).id()));
        home(); fire("home-review-action");
        assertTrue(fx(() -> ((ToggleButton) node("nav-F11")).isSelected()));
        assertEquals(street.id(), fx(() -> ((PhotographySession) ((ComboBox<?>) node("review-session")).getValue()).id()));

        // State 3: only a previous shoot -> "After the shoot" spans the whole row.
        assertTrue(manager.activeContext().facade().sessions().delete(manager.activeContext().facade().sessions().open(lake.id()).value().orElseThrow(), true).isSuccess());
        home();
        assertEquals(List.of("home-after"), cards());
        assertEquals(fx(() -> scene(node("home-sessions")).getWidth()), fx(() -> scene(node("home-after")).getWidth()), .5);
        snapshot("home-state3-after-only");

        // Another user's Home reads only that user's sessions; switching rebuilds it with none.
        UUID other = manager.createProfile("Other").value().orElseThrow().id();
        action(() -> controller.requestSwitch(other)); idle();
        assertTrue(fx(() -> controller.workspace().atHome()));
        assertEquals(List.of(), cards());
    }

    @Test void cardsPreferTheLatestSessionOfEachKindAndNeverTreatPlanningAsAShoot() {
        var older = draft("Older", "A", Instant.parse("2026-11-01T09:00:00Z"));
        var newer = draft("Newer", "B", Instant.parse("2026-11-02T09:00:00Z"));
        var planned = shot("Planned", "C", new PlannedState(), false, Instant.parse("2026-11-04T09:00:00Z"));
        var completed = shot("Completed", "D", new CompletedState(), false, Instant.parse("2026-11-03T09:00:00Z"));
        var reviewed = shot("Reviewed", "E", new CompletedState(), true, Instant.parse("2026-11-06T09:00:00Z"));
        reviewed = new PhotographySession(reviewed.id(), reviewed.title(), reviewed.date(), reviewed.location(), "", CREATED, reviewed.updatedAt(), new ReviewedState(),
            reviewed.requirements(), reviewed.plans(), reviewed.reviews(), reviewed.profile(), reviewed.statistics(), List.of(), reviewed.selectedLoadout());
        assertTrue(reviewed.validate().isSuccess());

        assertEquals(HomeDashboard.Highlights.NONE, HomeDashboard.Highlights.of(List.of()));
        var drafts = HomeDashboard.Highlights.of(List.of(older, newer));
        assertEquals("Newer", drafts.unfinished().orElseThrow().title()); assertTrue(drafts.afterShoot().isEmpty(), "planning is not a completed shoot");
        assertEquals("Planned", HomeDashboard.Highlights.of(List.of(older, newer, planned)).unfinished().orElseThrow().title());
        // A shoot waiting for its review is preferred over a newer, already reviewed one; reviewed is the fallback.
        assertEquals("Completed", HomeDashboard.Highlights.of(List.of(reviewed, completed, newer)).afterShoot().orElseThrow().title());
        var onlyReviewed = HomeDashboard.Highlights.of(List.of(reviewed));
        assertEquals("Reviewed", onlyReviewed.afterShoot().orElseThrow().title()); assertTrue(onlyReviewed.unfinished().isEmpty());

        // The card's next step is the shared workflow's recommendation (Home no longer derives steps of its own).
        var bare = new PhotographySession(UUID.randomUUID(), "Bare", LocalDate.of(2026, 11, 1), Optional.empty(), "", CREATED, CREATED, new DraftState(),
            Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        assertEquals(com.photopilot.workflow.Milestone.REQUIREMENTS, next(bare));
        var unreviewed = new PhotographySession(UUID.randomUUID(), "Needs", LocalDate.of(2026, 11, 1), Optional.empty(), "", CREATED, CREATED, new DraftState(),
            Optional.of(requirement("X")), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        assertEquals(com.photopilot.workflow.Milestone.EQUIPMENT_NEEDS, next(unreviewed));
        assertEquals(com.photopilot.workflow.Milestone.GEAR_SELECTION, next(newer));
        assertEquals(com.photopilot.workflow.Milestone.SHOOT, next(planned));
        // A chosen session is preferred by each card that can show it.
        assertEquals("Older", HomeDashboard.Highlights.of(List.of(older, newer), Optional.of(older.id())).unfinished().orElseThrow().title());
        assertEquals("Reviewed", HomeDashboard.Highlights.of(List.of(reviewed, completed), Optional.of(reviewed.id())).afterShoot().orElseThrow().title());
        assertEquals("Newer", HomeDashboard.Highlights.of(List.of(older, newer, completed), Optional.of(completed.id())).unfinished().orElseThrow().title(),
            "a shot session is never shown as the unfinished one");
    }
    private static com.photopilot.workflow.Milestone next(PhotographySession session) {
        return com.photopilot.service.SessionWorkflowService.evaluateStored(session).next().orElseThrow().milestone();
    }

    void snapshot(String name) throws Exception {
        AppearanceGuiTest.settle();
        fx(() -> {
            var image = stage.getScene().snapshot(null);
            var buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "home"));
            javax.imageio.ImageIO.write(buffered, "png", Path.of("target", "home", name + ".png").toFile());
            return true;
        });
    }
}
