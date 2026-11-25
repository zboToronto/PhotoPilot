package com.photopilot.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.PhotographySession;

/**
 * Shared GUI/CLI wording of a session's dates (Stage2OptRe1 PP-01). The session date is the date the shoot was
 * scheduled for; marking a session completed early, or rescheduling it, never makes it a shooting date. The only
 * evidence of when photos were taken is the capture span of the saved EXIF statistics (camera clock), shown as "photos
 * dated" next to the scheduled date when the two differ. PhotoPilot records no completion time; a review's own date is
 * its saved time (ReviewPresentation.heading).
 */
public final class SessionDates {
    private SessionDates() { }

    /** The scheduled date and, when saved statistics have timestamps, the camera-clock dates of the first and last photo. */
    public record Facts(LocalDate scheduled, Optional<LocalDate> firstPhoto, Optional<LocalDate> lastPhoto, int photosWithTime, int photos) {
        public boolean hasPhotoDates() { return firstPhoto.isPresent(); }
        /** True when the photos' dates do not include the scheduled date (shot earlier or later, or a wrong camera clock). */
        public boolean differs() {
            return firstPhoto.isPresent() && (scheduled.isBefore(firstPhoto.get()) || scheduled.isAfter(lastPhoto.orElseThrow()));
        }
    }

    public static Facts of(PhotographySession session) {
        var stats = session.statistics().filter(s -> s.timedCount() > 0 && s.firstCapture().isPresent() && s.lastCapture().isPresent());
        return new Facts(session.date(), stats.flatMap(ExifStatistics::firstCapture).map(SessionDates::day),
            stats.flatMap(ExifStatistics::lastCapture).map(SessionDates::day), stats.map(ExifStatistics::timedCount).orElse(0),
            stats.map(ExifStatistics::importedCount).orElse(0));
    }

    /** One short label: "Scheduled 2026-12-12", "Photos dated 2026-11-23" or "Photos dated 2026-11-23 · scheduled 2026-12-12". */
    public static String label(PhotographySession session, LocalizationService l) {
        var facts = of(session);
        if (!facts.hasPhotoDates()) return l.text("session.dates.scheduled", facts.scheduled().toString());
        String photos = photoDates(facts, l);
        return facts.differs() ? l.text("session.dates.photosScheduled", photos, facts.scheduled().toString()) : l.text("session.dates.photos", photos);
    }

    /** CLI detail line for a session with photo dates; empty when there is nothing beyond the scheduled date to say. */
    public static Optional<String> detail(PhotographySession session, LocalizationService l) {
        var facts = of(session);
        if (!facts.hasPhotoDates()) return Optional.empty();
        return Optional.of(l.text("session.dates.cli", facts.scheduled().toString(), photoDates(facts, l), facts.photosWithTime(), facts.photos()));
    }

    private static String photoDates(Facts facts, LocalizationService l) {
        var first = facts.firstPhoto().orElseThrow(); var last = facts.lastPhoto().orElseThrow();
        return first.equals(last) ? first.toString() : l.text("session.dates.range", first.toString(), last.toString());
    }
    private static LocalDate day(String isoLocalDateTime) { return LocalDateTime.parse(isoLocalDateTime).toLocalDate(); }
}
