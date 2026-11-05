package com.photopilot.application;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.ExifValues;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.PostShootReview;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.service.ReviewInputs;

/**
 * Shared localized F11 text for GUI cards and CLI output, so identical files show identical statistics on both.
 * Every line comes from the scan, the deterministic statistics/comparison or the review; nothing is computed or
 * decided here beyond formatting counts, units and shares of their stated denominators.
 */
public final class ReviewPresentation {
    private ReviewPresentation() { }
    /** Statistic sections in display order; each maps to one GUI card and one CLI block. */
    public static final List<String> STATISTICS = List.of("overview", "cameras", "lenses", "focalLength", "shutter", "aperture", "iso", "exposureCompensation", "capture");
    /** Review sections in display order. */
    public static final List<String> SECTIONS = List.of("summary", "technical", "equipment", "exposure", "differences", "lessons", "improvements", "limitations", "evidence");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_VALUES = 30;

    /** A message whose arguments may be messages themselves (field names, relations). */
    public static String render(LocalizedMessage message, LocalizationService l) {
        return l.text(message.key(), message.arguments().stream().map(a -> a instanceof LocalizedMessage nested ? render(nested, l) : a).toArray());
    }
    public static List<String> lines(List<LocalizedMessage> messages, LocalizationService l) {
        return messages.stream().map(m -> "- " + render(m, l)).toList();
    }

    public static List<String> inputs(ReviewInputs in, LocalizationService l) {
        var lines = new ArrayList<String>();
        var session = in.session();
        lines.add(l.text("review.inputs.session", session.title(), session.date().toString(), l.text("session.state." + session.status())));
        lines.add(in.statistics().map(s -> l.text("review.inputs.statistics", s.importedCount(), s.usableCount(),
            s.analyzedAt().map(ReviewPresentation::time).orElse("-"))).orElse(l.text("review.inputs.noStatistics")));
        lines.add(in.plan().map(p -> l.text("review.inputs.plan", p.revision())).orElse(l.text("review.missing.plan")));
        lines.add(in.requirement().map(r -> l.text("review.inputs.goals", r.subject(), r.goals().isEmpty() ? "-" : String.join("; ", r.goals())))
            .orElse(l.text("review.missing.requirement")));
        lines.add(l.text("review.inputs.saved", in.saved().size()));
        lines.addAll(lines(in.notes(), l));
        if (!in.canAnalyze()) { lines.add(l.text("review.inputs.analysisBlocked")); lines.addAll(lines(in.analysisBlockers(), l)); }
        if (!in.canReview()) { lines.add(l.text("review.inputs.reviewBlocked")); lines.addAll(lines(in.reviewBlockers(), l)); }
        return lines;
    }

    /** One line per selected file with its outcome and notes, then a total. */
    public static List<String> files(MetadataScan scan, LocalizationService l) {
        var lines = new ArrayList<String>();
        for (var file : scan.files()) {
            lines.add(l.text("review.fileLine", file.name(), file.kind().isEmpty() ? "-" : file.kind(), status(file.status(), l), file.records()));
            file.notes().forEach(note -> lines.add("    " + render(note, l)));
        }
        lines.add(l.text("review.files.summary", scan.files().size(), scan.failedFiles(), scan.records().size()));
        return lines;
    }
    public static String status(MetadataScan.Status status, LocalizationService l) {
        return l.text(switch (status) {
            case READ -> "review.status.READ"; case PARTIAL -> "review.status.PARTIAL"; case NO_METADATA -> "review.status.NO_METADATA";
            case UNSUPPORTED -> "review.status.UNSUPPORTED"; case MISSING -> "review.status.MISSING"; case UNREADABLE -> "review.status.UNREADABLE";
            case TOO_LARGE -> "review.status.TOO_LARGE"; case CORRUPT -> "review.status.CORRUPT"; case INVALID_STRUCTURE -> "review.status.INVALID_STRUCTURE";
            case DUPLICATE -> "review.status.DUPLICATE";
        });
    }

    /** What happened to the calculated statistics. */
    public static List<String> analysis(ExifAnalysis analysis, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text(switch (analysis.status()) {
            case SAVED -> "review.analysis.SAVED"; case REPLACED -> "review.analysis.REPLACED"; case UNCHANGED -> "review.analysis.UNCHANGED";
            case REPLACE_REQUIRED -> "review.analysis.REPLACE_REQUIRED"; case NO_USABLE_DATA -> "review.analysis.NO_USABLE_DATA";
            case SAVE_FAILED -> "review.analysis.SAVE_FAILED";
        }));
        lines.addAll(lines(analysis.notices(), l));
        return lines;
    }

    /** One statistics section; every share is of the photographs that recorded that field. */
    public static List<String> statistics(ExifStatistics s, String section, LocalizationService l) {
        var lines = new ArrayList<String>();
        switch (section) {
            case "overview" -> {
                lines.add(l.text("review.stats.overviewLine", s.importedCount(), s.usableCount(), s.failedFileCount()));
                s.analyzedAt().ifPresent(at -> lines.add(l.text("review.stats.analyzed", time(at))));
            }
            case "cameras" -> equipment(s, "camera", s.cameraCounts(), lines, l);
            case "lenses" -> equipment(s, "lens", s.lensCounts(), lines, l);
            case "capture" -> {
                lines.add(l.text("review.stats.captureLine", s.timedCount(), s.importedCount(), s.zonedCount()));
                if (s.firstCapture().isPresent()) lines.add(l.text("review.stats.span", local(s.firstCapture().get()), local(s.lastCapture().orElseThrow()),
                    s.captureOffset().map(o -> "UTC" + o).orElse(l.text("review.stats.offsetUnknown"))));
            }
            default -> numeric(s, section, lines, l);
        }
        return lines;
    }
    private static void equipment(ExifStatistics s, String field, Map<String, Integer> counts, List<String> lines, LocalizationService l) {
        int n = s.count(field);
        if (n == 0) { lines.add(l.text("review.stats.noData", s.importedCount())); return; }
        lines.add(l.text("review.stats.basis", n, s.importedCount()));
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> lines.add(l.text("review.stats.valueLine", e.getKey(), e.getValue(), ExifValues.percent(e.getValue(), n))));
        if (s.missing(field) > 0) lines.add(l.text("review.stats.unknown", s.missing(field), s.invalid(field)));
    }
    private static void numeric(ExifStatistics s, String field, List<String> lines, LocalizationService l) {
        var summary = s.summary(field);
        if (summary.isEmpty()) { lines.add(l.text("review.stats.noData", s.importedCount())); if (s.invalid(field) > 0) lines.add(l.text("review.stats.unknown", s.missing(field), s.invalid(field))); return; }
        var m = summary.get();
        lines.add(l.text("review.stats.basis", m.count(), s.importedCount()));
        if (field.equals("shutter")) lines.add(l.text("review.stats.shutterSummary", ExifValues.format(field, m.min()), ExifValues.format(field, m.max()), ExifValues.format(field, m.median())));
        else lines.add(l.text("review.stats.summary", ExifValues.format(field, m.min()), ExifValues.format(field, m.max()), ExifValues.format(field, m.median()), ExifValues.format(field, m.mean())));
        var histogram = s.histogram(field);
        int shown = 0;
        for (var entry : histogram.entrySet()) {
            if (shown++ == MAX_VALUES) { lines.add(l.text("review.stats.moreValues", histogram.size() - MAX_VALUES)); break; }
            lines.add(l.text("review.stats.valueLine", ExifValues.format(field, entry.getKey()), entry.getValue(), ExifValues.percent(entry.getValue(), m.count())));
        }
        if (s.missing(field) > 0) lines.add(l.text("review.stats.unknown", s.missing(field), s.invalid(field)));
    }

    public static String heading(PostShootReview review, LocalizationService l) {
        return review.status().equals(PostShootReview.DRAFT) ? l.text("review.heading.draft", review.comparedRevision())
            : l.text("review.heading.saved", time(review.createdAt()), review.comparedRevision());
    }
    /** One review section as display lines (empty sections say so instead of disappearing). */
    public static List<String> section(PostShootReview review, String section, LocalizationService l) {
        List<String> items = switch (section) {
            case "summary" -> review.summary().isBlank() ? List.of() : List.of(review.summary());
            case "technical" -> review.technicalObservations();
            case "equipment" -> review.equipmentObservations();
            case "exposure" -> review.exposureObservations();
            case "differences" -> review.planDifferences();
            case "lessons" -> review.lessons();
            case "improvements" -> review.improvements();
            case "limitations" -> review.limitations();
            default -> review.evidence();
        };
        if (items.isEmpty()) return List.of(l.text("review.section.empty"));
        return section.equals("summary") ? items : items.stream().map(item -> "- " + item).toList();
    }

    private static String time(Instant at) { return MINUTE.format(at.atZone(ZoneId.systemDefault())); }
    private static String local(String isoLocal) { return MINUTE.format(LocalDateTime.parse(isoLocal)); }
}
