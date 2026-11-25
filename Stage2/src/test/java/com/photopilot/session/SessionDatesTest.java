package com.photopilot.session;

import com.photopilot.application.SessionDates;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.StatisticsCalculator;
import com.photopilot.state.*;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 PP-01: Home said "Shot on 2026-12-12" for a session scheduled for 12 December and marked completed on
 * 23 November, before any photo was imported. The session date is a scheduled date; only saved EXIF capture times say
 * when photos were taken. Shared SessionDates wording, the CLI session output and the stored bytes are checked here;
 * SessionDatesGuiTest checks the Home card and the session editor.
 */
class SessionDatesTest {
    @TempDir Path root;
    static final LocalizationService EN = LocalizationService.english();
    static final LocalDate SCHEDULED = LocalDate.of(2026, 12, 12);

    static ExifRecord photo(String time) {
        return new ExifRecord("p.jpg", Optional.of(50.0), Optional.of(1.0 / 250), Optional.of(4.0), Optional.of(200), Optional.of("SONY ILCE-7M4"),
            Optional.empty(), Optional.empty(), Optional.ofNullable(time).map(LocalDateTime::parse), Optional.empty(), Set.of());
    }
    static ExifStatistics statistics(String... times) {
        return new StatisticsCalculator().summarize(Arrays.stream(times).map(SessionDatesTest::photo).toList()).value().orElseThrow();
    }
    /** A completed (or reviewed) linked session scheduled for {@code date}, with the given statistics. */
    static PhotographySession completed(LocalDate date, Optional<ExifStatistics> stats) {
        var base = SessionFixtures.linked(new CompletedState(), List.of(), false);
        return new PhotographySession(UUID.randomUUID(), "Portrait", date, base.location(), "", base.createdAt(), base.updatedAt(), base.state(),
            base.requirements(), base.plans(), base.reviews(), base.profile(), stats, List.of(), base.selectedLoadout());
    }

    @Test void aFutureSessionCompletedEarlyShowsItsScheduledDateNotAShootingDate() {
        var early = completed(SCHEDULED, Optional.empty());
        assertEquals("Scheduled 2026-12-12", SessionDates.label(early, EN));
        assertFalse(SessionDates.label(early, EN).contains("Shot"));
        assertTrue(SessionDates.detail(early, EN).isEmpty(), "nothing beyond the scheduled date is known");
        // Statistics without capture times (no EXIF timestamps) say nothing about the date either.
        var untimed = completed(SCHEDULED, Optional.of(statistics((String) null)));
        assertEquals("Scheduled 2026-12-12", SessionDates.label(untimed, EN));
    }

    @Test void photoTimestampsAreShownAsPhotoDatesNextToADifferentScheduledDate() {
        var early = completed(SCHEDULED, Optional.of(statistics("2026-11-23T10:00:00", "2026-11-23T10:05:00")));
        assertEquals("Photos dated 2026-11-23 · scheduled 2026-12-12", SessionDates.label(early, EN));
        assertEquals("Dates: scheduled 2026-12-12; photos dated 2026-11-23 (EXIF camera clock, 2 of 2 photos with a capture time)",
            SessionDates.detail(early, EN).orElseThrow());
        var onTheDay = completed(SCHEDULED, Optional.of(statistics("2026-12-12T16:00:00")));
        assertEquals("Photos dated 2026-12-12", SessionDates.label(onTheDay, EN), "no conflict, no second date");
        var overnight = completed(SCHEDULED, Optional.of(statistics("2026-12-12T23:30:00", "2026-12-13T00:20:00")));
        assertEquals("Photos dated 2026-12-12 to 2026-12-13", SessionDates.label(overnight, EN), "the scheduled day is inside the span");
        var partly = completed(SCHEDULED, Optional.of(statistics("2026-12-10T09:00:00", null, null)));
        assertEquals("Photos dated 2026-12-10 · scheduled 2026-12-12", SessionDates.label(partly, EN));
        assertTrue(SessionDates.detail(partly, EN).orElseThrow().contains("1 of 3 photos with a capture time"), "incomplete evidence is stated");
    }

    @Test void chineseWordingKeepsTheSameDistinction() {
        var zh = LocalizationService.english(); zh.useLanguage("zh-CN");
        assertEquals("计划日期 2026-12-12", SessionDates.label(completed(SCHEDULED, Optional.empty()), zh));
        assertEquals("照片日期 2026-11-23 · 计划日期 2026-12-12", SessionDates.label(completed(SCHEDULED, Optional.of(statistics("2026-11-23T10:00:00"))), zh));
        assertEquals("照片日期 2026-12-12 至 2026-12-13", SessionDates.label(completed(SCHEDULED, Optional.of(statistics("2026-12-12T23:30:00", "2026-12-13T00:20:00"))), zh));
    }

    String cli(ApplicationBootstrap app, String... commands) {
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        for (String command : commands) assertEquals(0, cli.execute(command), command);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void theCliStatesTheSameDatesAndAReschedulingMovesOnlyTheScheduledDate() throws Exception {
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()))) {
            var repository = new JsonSessionRepository(app.storageScope().sessionsDirectory());
            var early = repository.insert(completed(SCHEDULED, Optional.of(statistics("2026-11-23T10:00:00")))).value().orElseThrow();
            var noPhotos = repository.insert(completed(SCHEDULED, Optional.empty())).value().orElseThrow();
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            String output = cli(app, "session open " + early.id(), "session open " + noPhotos.id(), "session list");
            assertTrue(output.contains("Dates: scheduled 2026-12-12; photos dated 2026-11-23 (EXIF camera clock, 1 of 1 photos with a capture time)"), output);
            assertEquals(2, output.split("Dates: scheduled", -1).length - 1, "only the session with photo dates gets the line, in open and in list");
            assertFalse(output.contains("Shot on"), output);
            assertArrayEquals(before, Files.readAllBytes(store), "dates are derived for display; stored sessions keep their bytes");

            // A planned session rescheduled after planning: the new date is the scheduled date shown.
            var planned = repository.insert(SessionFixtures.linked(new PlannedState(), List.of(), false)).value().orElseThrow();
            String moved = cli(app, "session edit " + planned.id() + " date=2026-12-20");
            assertTrue(moved.contains(" | 2026-12-20 | "), moved);
            var reloaded = app.facade().sessions().open(planned.id()).value().orElseThrow();
            assertEquals("Scheduled 2026-12-20", SessionDates.label(reloaded, app.localization()));
        }
    }

    @Test void sessionsStoredBeforeThisChangeReadAndRenderUnchanged() throws Exception {
        // The patch adds no stored field: a store written without it reads back with the same bytes and wording.
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()))) {
            var repository = new JsonSessionRepository(app.storageScope().sessionsDirectory());
            var reviewed = repository.insert(SessionFixtures.linked(new ReviewedState(), List.of(), true)).value().orElseThrow();
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            String json = Files.readString(store);
            assertFalse(json.contains("completedAt") || json.contains("shotOn") || json.contains("actualDate"), "no new persisted date field");
            var reopened = new JsonSessionRepository(app.storageScope().sessionsDirectory()).find(reviewed.id()).value().orElseThrow();
            assertEquals("Scheduled 2026-11-01", SessionDates.label(reopened, app.localization()), "the fixture statistics carry no capture time");
            assertEquals(json, Files.readString(store));
        }
    }
}
