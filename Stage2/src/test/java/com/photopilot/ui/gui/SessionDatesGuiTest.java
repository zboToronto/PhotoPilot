package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.StatisticsCalculator;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Stage2OptRe1 PP-01 in the real workspace: the audit marked a session scheduled for
 * 2026-12-12 completed on 2026-11-23 and Home's after-shoot card then said "Shot on 2026-12-12". The card now names the
 * scheduled date, or the photos' EXIF dates next to it, in both languages, and refreshes after a photo import.
 */
class SessionDatesGuiTest {
    @TempDir Path root;
    ProfileManager manager; GuiProfileController controller; Stage stage;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @BeforeEach void launch() throws Exception {
        manager = new ProfileManager(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()));
        var initial = manager.activeProfile().value().orElseThrow();
        action(() -> { stage = new Stage(); controller = new GuiProfileController(stage, manager, initial, context -> { }); });
        idle();
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
    }
    @AfterEach void close() throws Exception {
        action(() -> { if (controller != null) controller.close(); if (stage != null) stage.close(); });
        if (manager != null) manager.close();
    }
    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    void home() throws Exception { action(() -> ((ToggleButton) controller.workspace().lookup("#nav-home")).fire()); idle(); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) controller.workspace().lookup("#" + id)).getText()); }
    JsonSessionRepository sessions() { return new JsonSessionRepository(manager.activeContext().storageScope().sessionsDirectory()); }

    static ExifStatistics photos(String... times) {
        var records = Arrays.stream(times).map(time -> new ExifRecord("p.jpg", Optional.of(85.0), Optional.of(1.0 / 250), Optional.of(2.8), Optional.of(400),
            Optional.of("SONY ILCE-7M4"), Optional.empty(), Optional.empty(), Optional.of(LocalDateTime.parse(time)), Optional.empty(), Set.of())).toList();
        return new StatisticsCalculator().summarize(records).value().orElseThrow();
    }
    /** The audit's case: a planned portrait session for 12 December, marked completed early; no photos imported yet. */
    static PhotographySession portrait(Optional<ExifStatistics> stats) {
        var base = SessionFixtures.linked(new CompletedState(), List.of(), false);
        var requirement = new ShootRequirement("Brief", "Portrait", List.of("Light"), Optional.empty(), Optional.empty(), Duration.ofHours(1), 1, true,
            Optional.empty(), List.of(), Map.of(), Map.of(), List.of(), true);
        return new PhotographySession(UUID.randomUUID(), "Winter portrait", LocalDate.of(2026, 12, 12), base.location(), "", base.createdAt(),
            Instant.parse("2026-11-23T19:00:00Z"), base.state(), Optional.of(requirement), base.plans(), base.reviews(), base.profile(), stats, List.of(), base.selectedLoadout());
    }

    @Test void theAfterShootCardNamesTheScheduledDateOrThePhotoDatesNeverAShootingDate() throws Exception {
        var session = sessions().insert(portrait(Optional.empty())).value().orElseThrow();
        home();
        assertEquals("Winter portrait", text("home-after-title"));
        assertEquals("Portrait · Scheduled 2026-12-12", text("home-after-meta"));
        assertFalse(text("home-after-meta").contains("Shot"), "a scheduled date is never presented as the day it was shot");

        // Importing photos (here: their saved statistics, written by another process) gives real dates; Home refreshes.
        var withPhotos = sessions().find(session.id()).value().orElseThrow().attachStatistics(photos("2026-11-23T10:00:00", "2026-11-23T10:05:00")).value().orElseThrow();
        assertTrue(sessions().save(withPhotos).isSuccess());
        home();
        assertEquals("Portrait · Photos dated 2026-11-23 · scheduled 2026-12-12", text("home-after-meta"));
        fx(() -> { CalculatorGuiTest.snapshot(controller.workspace(), "home-after-dates-en"); return true; });

        action(() -> manager.activeContext().localization().selectLanguage("zh-CN")); idle();
        assertEquals("Portrait · 照片日期 2026-11-23 · 计划日期 2026-12-12", text("home-after-meta"));
        action(() -> manager.activeContext().localization().selectLanguage("en")); idle();

        // The session editor (Photography Sessions) states the same dates as the CLI's session open.
        action(() -> controller.showSession(session.id())); idle();
        assertTrue(fx(() -> controller.workspace().lookupAll(".secondary-text").stream().map(n -> ((Labeled) n).getText())
            .anyMatch(t -> t.contains("Dates: scheduled 2026-12-12; photos dated 2026-11-23 (EXIF camera clock, 2 of 2 photos with a capture time)"))),
            "the editor's metadata names both dates");
    }

    @Test void aSessionShotOnItsScheduledDayShowsOnlyThePhotoDate() throws Exception {
        sessions().insert(portrait(Optional.of(photos("2026-12-12T15:00:00", "2026-12-12T16:30:00"))));
        home();
        assertEquals("Portrait · Photos dated 2026-12-12", text("home-after-meta"));
    }
}
