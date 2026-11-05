package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.service.ReviewDraft;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/**
 * F11 command adapter. Extraction, statistics, comparison, AI interpretation and saving all run in the shared
 * ReviewService through the Facade, and output uses ReviewPresentation, so CLI and GUI produce the same numbers and
 * enforce the same rules. A generated review stays pending in this process until {@code review save}.
 */
final class ReviewCommands {
    /** Process-local pending draft; never persisted and cleared when the user switches. */
    static final class State {
        private ReviewDraft pending;
        void clear() { pending = null; }
    }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    ReviewCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line.strip());
            String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> { output.println(localization.text("review.cli.help")); return 0; }
                case "show" -> { return show(words); }
                case "analyze" -> { return analyze(words); }
                case "stats" -> { return stats(words); }
                case "compare" -> { return compare(words); }
                case "generate" -> { return generate(words); }
                case "draft" -> { if (!pending()) return 2; print(state.pending); return 0; }
                case "save" -> { return save(); }
                case "discard" -> {
                    if (!pending()) return 2;
                    app.reviews().discard(state.pending.sessionId(), state.pending.review()); state.pending = null;
                    output.println(localization.text("review.discarded")); return 0;
                }
                case "list" -> { return list(words); }
                case "open" -> { return open(words); }
                default -> throw new LocalizedException("error.cli.option", action);
            }
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int show(List<String> words) {
        var session = session(words, 3); if (session == null) return 2;
        var inputs = app.reviews().inputs(session.id());
        if (!report(inputs)) return 2;
        ReviewPresentation.inputs(inputs.value().orElseThrow(), localization).forEach(output::println);
        inputs.value().orElseThrow().statistics().ifPresent(this::printStatistics);
        if (!inputs.value().orElseThrow().differences().isEmpty()) printComparison(inputs.value().orElseThrow().differences());
        return 0;
    }
    /** review analyze <session-id> [--replace] <file|folder>... */
    private int analyze(List<String> words) {
        if (words.size() < 4) throw new LocalizedException("review.cli.analyzeUsage");
        var session = session(words.get(2)); if (session == null) return 2;
        boolean replace = false; var files = new ArrayList<Path>();
        for (String word : words.subList(3, words.size())) {
            if (word.equals("--replace")) { replace = true; continue; }
            try { files.add(Path.of(word)); } catch (InvalidPathException invalid) { throw new LocalizedException("review.cli.badPath", word); }
        }
        if (files.isEmpty()) throw new LocalizedException("review.cli.analyzeUsage");
        output.println(localization.text("review.analyzing", files.size()));
        var result = app.reviews().analysis(session.id(), files, replace);
        if (!report(result)) return 2;
        var analysis = result.value().orElseThrow();
        output.println(localization.text("review.card.files"));
        ReviewPresentation.files(analysis.scan(), localization).forEach(l -> output.println("  " + l));
        ReviewPresentation.analysis(analysis, localization).forEach(output::println);
        analysis.statistics().ifPresent(this::printStatistics);
        if (analysis.status() == ExifAnalysis.Status.REPLACE_REQUIRED) output.println(localization.text("review.cli.replaceHint"));
        if (analysis.status().persisted()) output.println(localization.text("review.cli.analyzedHint", session.id()));
        return analysis.status().persisted() ? 0 : 2;
    }
    private int stats(List<String> words) {
        var session = session(words, 3); if (session == null) return 2;
        var inputs = app.reviews().inputs(session.id());
        if (!report(inputs)) return 2;
        var stats = inputs.value().orElseThrow().statistics();
        if (stats.isEmpty()) { output.println(localization.text("review.inputs.noStatistics")); return 2; }
        printStatistics(stats.get());
        output.println(localization.text("review.card.limits"));
        ReviewPresentation.lines(inputs.value().orElseThrow().limitations(), localization).forEach(l -> output.println("  " + l));
        return 0;
    }
    private int compare(List<String> words) {
        var session = session(words, 3); if (session == null) return 2;
        var inputs = app.reviews().inputs(session.id());
        if (!report(inputs)) return 2;
        var in = inputs.value().orElseThrow();
        if (in.differences().isEmpty()) {
            output.println(localization.text("review.cli.noComparison"));
            ReviewPresentation.lines(in.reviewBlockers(), localization).forEach(output::println);
            return 2;
        }
        printComparison(in.differences());
        return 0;
    }
    private int generate(List<String> words) {
        var session = session(words, 3); if (session == null) return 2;
        output.println(localization.text("review.generating"));
        var draft = app.reviews().draft(session.id());
        if (!report(draft)) { output.println(localization.text("review.cli.statisticsKept")); return 2; }
        if (state.pending != null) app.reviews().discard(state.pending.sessionId(), state.pending.review());
        state.pending = draft.value().orElseThrow();
        print(state.pending);
        return 0;
    }
    private int save() {
        if (!pending()) return 2;
        var draft = state.pending;
        var saved = app.reviews().save(draft.sessionId(), draft.review());
        if (!report(saved)) { output.println(localization.text("review.saveFailed")); return 2; }
        state.pending = null;
        var session = app.sessions().open(draft.sessionId());
        output.println(localization.text("review.saved", session.value().map(PhotographySession::title).orElse(""),
            session.value().map(s -> localization.text("session.state." + s.status())).orElse("")));
        output.println(localization.text("review.cli.savedHint", draft.sessionId()));
        return 0;
    }
    private int list(List<String> words) {
        var session = session(words, 3); if (session == null) return 2;
        var reviews = app.reviews().reviews(session.id());
        if (!report(reviews)) return 2;
        output.println(localization.text("review.cli.session", session.title(), session.id(), localization.text("session.state." + session.status())));
        if (reviews.value().orElseThrow().isEmpty()) { output.println(localization.text("review.cli.noSaved")); return 0; }
        int index = 1;
        for (var review : reviews.value().orElseThrow()) output.println("  " + index++ + ". " + ReviewPresentation.heading(review, localization));
        return 0;
    }
    private int open(List<String> words) {
        if (words.size() < 3 || words.size() > 4) throw new LocalizedException("error.session.command");
        var session = session(words.get(2)); if (session == null) return 2;
        var reviews = app.reviews().reviews(session.id());
        if (!report(reviews)) return 2;
        var all = reviews.value().orElseThrow();
        int index = all.size();
        if (words.size() == 4) {
            try { index = Integer.parseInt(words.get(3)); } catch (NumberFormatException invalid) { throw new LocalizedException("error.cli.option", words.get(3)); }
        }
        if (index < 1 || index > all.size()) { output.println(localization.text("review.cli.noSaved")); return 2; }
        printReview(all.get(index - 1));
        return 0;
    }

    private void print(ReviewDraft draft) {
        printReview(draft.review());
        output.println(localization.text("review.card.comparison"));
        ReviewPresentation.lines(draft.differences(), localization).stream().map(ReviewCommands::indent).forEach(output::println);
        output.println(localization.text("review.card.notes"));
        ReviewPresentation.lines(draft.notes(), localization).stream().map(ReviewCommands::indent).forEach(output::println);
        output.println(localization.text("review.cli.next"));
    }
    private void printReview(PostShootReview review) {
        output.println(ReviewPresentation.heading(review, localization));
        for (String section : ReviewPresentation.SECTIONS) {
            output.println(localization.text("review.section." + section));
            ReviewPresentation.section(review, section, localization).forEach(line -> output.println("  " + line));
        }
    }
    private void printStatistics(ExifStatistics stats) {
        for (String section : ReviewPresentation.STATISTICS) {
            output.println(localization.text("review.stats." + section));
            ReviewPresentation.statistics(stats, section, localization).forEach(line -> output.println("  " + line));
        }
    }
    private void printComparison(List<LocalizedMessage> differences) {
        output.println(localization.text("review.card.comparison"));
        ReviewPresentation.lines(differences, localization).stream().map(ReviewCommands::indent).forEach(output::println);
    }
    private static String indent(String line) { return "  " + line; }
    private boolean pending() {
        if (state.pending != null) return true;
        output.println(localization.text("review.cli.noPending")); return false;
    }
    private PhotographySession session(List<String> words, int size) {
        if (words.size() != size) throw new LocalizedException("error.session.command");
        return session(words.get(2));
    }
    private PhotographySession session(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.messages().forEach(m -> output.println(localization.text("cli.error", ReviewPresentation.render(m, localization)))); return false;
    }
}
