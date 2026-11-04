package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.PhotographySession;
import com.photopilot.service.PlanPreview;
import java.io.PrintStream;
import java.util.*;

/**
 * F10 command adapter. Generation, revision, validation and approval all run in the shared PlanningService through
 * the Facade, and output uses PlanPresentation, so the CLI and GUI enforce and show the same rules.
 */
final class PlanCommands {
    /** Process-local pending preview; never persisted and cleared when the user switches. */
    static final class State {
        private PlanPreview pending;
        void clear() { pending = null; }
    }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    PlanCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = line.strip().split("\\s+");
            String action = words.length < 2 ? "help" : words[1].toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> { output.println(localization.text("plan.cli.help")); return 0; }
                case "show" -> { return show(words); }
                case "generate" -> { return generate(words); }
                case "revise" -> { return revise(line, words); }
                case "preview" -> { if (!pending()) return 2; print(state.pending); return 0; }
                case "compare" -> {
                    if (!pending()) return 2;
                    if (state.pending.base().isEmpty()) { output.println(localization.text("plan.cli.noRevision")); return 2; }
                    PlanPresentation.comparison(state.pending, localization).forEach(output::println); return 0;
                }
                case "approve" -> { return approve(); }
                case "reject" -> {
                    if (!pending()) return 2;
                    app.planning().reject(state.pending.sessionId(), state.pending.plan()); state.pending = null;
                    output.println(localization.text("plan.rejected")); return 0;
                }
                case "list" -> { return list(words); }
                case "open" -> { return open(words); }
                default -> throw new LocalizedException("error.cli.option", action);
            }
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int show(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        var session = session(words[2]); if (session == null) return 2;
        var inputs = app.planning().planInputs(session.id());
        if (!report(inputs)) return 2;
        output.println(localization.text("plan.cli.session", session.title(), session.id(), localization.text("session.state." + session.status())));
        PlanPresentation.inputs(inputs.value().orElseThrow(), localization).forEach(output::println);
        inputs.value().orElseThrow().current().ifPresent(plan -> { output.println(); printPlan(plan); });
        return 0;
    }
    private int generate(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        var session = session(words[2]); if (session == null) return 2;
        output.println(localization.text("plan.generating"));
        var preview = app.planning().generatePreview(session.id());
        if (!report(preview)) return 2;
        replacePending(preview.value().orElseThrow());
        print(state.pending);
        return 0;
    }
    private int revise(String line, String[] words) {
        var tokens = InventoryCommands.tokenize(line);
        if (tokens.size() < 4) throw new LocalizedException("error.session.command");
        var session = session(tokens.get(2)); if (session == null) return 2;
        String request = String.join(" ", tokens.subList(3, tokens.size()));
        output.println(localization.text("plan.revising"));
        var preview = app.planning().revisePreview(session.id(), request);
        if (!report(preview)) return 2;
        replacePending(preview.value().orElseThrow());
        PlanPresentation.comparison(state.pending, localization).forEach(output::println);
        output.println();
        print(state.pending);
        return 0;
    }
    private int approve() {
        if (!pending()) return 2;
        var preview = state.pending;
        if (!preview.approvable()) { output.println(localization.text("plan.cli.notApprovable")); return 2; }
        var saved = app.planning().approve(preview.sessionId(), preview.plan());
        if (!report(saved)) { output.println(localization.text("plan.saveFailed")); return 2; }
        state.pending = null;
        var session = app.sessions().open(preview.sessionId());
        output.println(localization.text("plan.saved", saved.value().orElseThrow().revision(), session.value().map(PhotographySession::title).orElse(""),
            session.value().map(s -> localization.text("session.state." + s.status())).orElse("")));
        output.println(localization.text("plan.cli.savedHint", preview.sessionId()));
        return 0;
    }
    private int list(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        var session = session(words[2]); if (session == null) return 2;
        var plans = app.planning().plans(session.id());
        if (!report(plans)) return 2;
        output.println(localization.text("plan.cli.session", session.title(), session.id(), localization.text("session.state." + session.status())));
        PlanPresentation.history(plans.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }
    private int open(String[] words) {
        if (words.length < 3 || words.length > 4) throw new LocalizedException("error.session.command");
        var session = session(words[2]); if (session == null) return 2;
        var plans = app.planning().plans(session.id());
        if (!report(plans)) return 2;
        Optional<ShootPlan> chosen;
        if (words.length == 4) {
            int revision;
            try { revision = Integer.parseInt(words[3]); } catch (NumberFormatException invalid) { throw new LocalizedException("error.cli.option", words[3]); }
            chosen = plans.value().orElseThrow().stream().filter(p -> p.revision() == revision).findFirst();
        } else chosen = plans.value().orElseThrow().stream().reduce((first, second) -> second);
        if (chosen.isEmpty()) { output.println(localization.text("plan.cli.noPlan")); return 2; }
        printPlan(chosen.get());
        return 0;
    }

    private void print(PlanPreview preview) {
        printPlan(preview.plan());
        output.println(localization.text("plan.card.validation"));
        PlanPresentation.validation(preview, localization).forEach(line -> output.println("  " + line));
        output.println(localization.text("plan.card.evidence"));
        PlanPresentation.evidence(preview, localization).forEach(line -> output.println("  " + line));
        output.println(localization.text(preview.approvable() ? "plan.cli.next" : "plan.cli.nextBlocked"));
    }
    private void printPlan(ShootPlan plan) {
        output.println(PlanPresentation.heading(plan, localization));
        for (String section : PlanPresentation.SECTIONS) {
            output.println(localization.text("plan.card." + section));
            PlanPresentation.section(plan, section, localization).forEach(line -> output.println("  " + line));
        }
    }
    private void replacePending(PlanPreview preview) {
        if (state.pending != null) app.planning().reject(state.pending.sessionId(), state.pending.plan());
        state.pending = preview;
    }
    private boolean pending() {
        if (state.pending != null) return true;
        output.println(localization.text("plan.cli.noPending")); return false;
    }
    private PhotographySession session(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
