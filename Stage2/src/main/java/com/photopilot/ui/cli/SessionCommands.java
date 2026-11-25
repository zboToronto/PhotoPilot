package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.PhotographySession;
import com.photopilot.state.SessionEvent;
import java.io.PrintStream;
import java.util.*;

/** F01 command adapter. GUI and CLI share SessionForm, SessionService and storage. */
final class SessionCommands {
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    SessionCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization) {
        this.app = app; this.output = output; this.localization = localization;
    }
    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line);
            String action = words.size() < 2 ? "list" : words.get(1).toLowerCase(Locale.ROOT);
            if (action.equals("help")) { output.println(localization.text("session.cli.help")); return 0; }
            if (action.equals("list")) {
                var options = options(words, 2, Set.of("query", "status", "date"));
                var found = app.sessions().search(options.getOrDefault("query", ""), options.getOrDefault("status", "").toUpperCase(Locale.ROOT),
                    options.containsKey("date") ? SessionForm.date(options.get("date")) : null);
                if (!report(found)) return 2;
                var sessions = found.value().orElseThrow(); output.println(localization.text("session.count", sessions.size()));
                sessions.forEach(this::show); return 0;
            }
            if (action.equals("create")) {
                var parsed = SessionForm.parse(options(words, 2, Set.of("title", "date", "location", "notes")), null);
                if (!report(parsed)) return 2;
                var d = parsed.value().orElseThrow();
                var saved = app.sessions().create(d.title(), d.date(), d.location(), d.notes());
                if (!report(saved)) return 2;
                output.println(localization.text("session.saved")); show(saved.value().orElseThrow()); workflow(saved.value().orElseThrow(), false); return 0;
            }
            if (!Set.of("open", "view", "restore", "edit", "transition", "delete", "progress").contains(action) || words.size() < 3)
                throw new LocalizedException("error.session.command");
            var loaded = app.sessions().open(SessionForm.id(words.get(2)));
            if (!report(loaded)) return 2;
            var original = loaded.value().orElseThrow();
            if (Set.of("open", "view", "restore").contains(action)) {
                options(words, 3, Set.of()); show(original); workflow(original, false); return 0;
            }
            if (action.equals("progress")) { options(words, 3, Set.of()); workflow(original, true); return 0; }
            if (action.equals("delete")) {
                var options = options(words, 3, Set.of("--confirm"));
                if (!report(app.sessions().delete(original, options.containsKey("--confirm")))) return 2;
                output.println(localization.text("session.deleted")); return 0;
            }
            Result<PhotographySession> saved;
            if (action.equals("transition")) {
                var options = options(words, 3, Set.of("event"));
                SessionEvent event;
                try { event = SessionEvent.valueOf(options.getOrDefault("event", "").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException error) { throw new LocalizedException("error.session.state"); }
                saved = app.sessions().recordEvent(original, event);
            } else {
                var parsed = SessionForm.parse(options(words, 3, Set.of("title", "date", "location", "notes")), original);
                if (!report(parsed)) return 2;
                var d = parsed.value().orElseThrow();
                saved = app.sessions().update(original, d.title(), d.date(), d.location(), d.notes());
            }
            if (!report(saved)) return 2;
            output.println(localization.text("session.saved")); show(saved.value().orElseThrow()); workflow(saved.value().orElseThrow(), false); return 0;
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    /**
     * The session's workflow progress from the shared SessionWorkflowService (the GUI sidebar's source): a summary
     * line and the recommended next milestone with its CLI command, plus one line per milestone when {@code detailed}.
     * Printed after the existing lines, so earlier output is unchanged.
     */
    private void workflow(PhotographySession session, boolean detailed) {
        var progress = app.workflow().evaluate(session);
        String phase = localization.text(progress.phase().labelKey());
        output.println(localization.text("session.cli.workflow", progress.percent(), progress.completed(), progress.applicable(),
            progress.attention() ? localization.text("workflow.phaseAttention", phase) : phase));
        if (detailed) for (var state : progress.milestones())
            // Indented in code: properties files drop leading spaces of a value.
            output.println("  " + localization.text("session.cli.milestone", String.format("%02d", state.milestone().number()),
                localization.text(state.milestone().titleKey()), localization.text(state.status().labelKey()), state.detail().render(localization)));
        output.println(progress.next().map(next -> localization.text("session.cli.next", localization.text(next.milestone().titleKey()),
                localization.text(command(next), session.id())))
            .orElseGet(() -> localization.text("session.cli.complete")));
    }
    /**
     * The CLI command that acts on the recommended milestone: analysed photos continue with the review draft, and a plan
     * whose date, place or gear changed is revised (F10 regenerates only when its constraints changed).
     */
    private static String command(com.photopilot.workflow.MilestoneState next) {
        String key = "session.cli.command." + next.milestone().name();
        if (next.milestone() == com.photopilot.workflow.Milestone.REVIEW && next.status() == com.photopilot.workflow.MilestoneStatus.IN_PROGRESS) return key + ".continue";
        if (next.milestone() == com.photopilot.workflow.Milestone.PLAN && next.detail().key().equals("workflow.detail.planShootChanged")) return key + ".revise";
        return key;
    }
    private static Map<String, String> options(List<String> words, int start, Set<String> allowed) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = start; i < words.size(); i++) {
            String word = words.get(i); int split = word.indexOf('=');
            String key = split < 0 ? word : word.substring(0, split);
            if (!allowed.contains(key) || (split < 0 && !key.equals("--confirm")) || (split >= 0 && key.startsWith("--")))
                throw new LocalizedException("error.cli.option", key);
            if (options.putIfAbsent(key, split < 0 ? "true" : word.substring(split + 1)) != null)
                throw new LocalizedException("error.cli.repeated", key);
        }
        return options;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
    private void show(PhotographySession session) {
        output.println(localization.text("session.summary", session.id(), session.title(), session.date(), localization.text("session.state." + session.status())));
        // PP-01: with saved photo timestamps, the scheduled date and the photos' camera-clock dates are told apart.
        SessionDates.detail(session, localization).ifPresent(output::println);
        output.println(localization.text("session.metadata", session.location().map(l -> l.label()).orElse(""), session.notes(), session.createdAt(), session.updatedAt()));
        output.println(localization.text("session.links", session.requirements().isPresent() ? 1 : 0, session.plans().size(),
            session.reviews().size(), session.statistics().isPresent() ? 1 : 0, session.savedIdeas().size()));
        session.plans().forEach(plan -> {
            output.println(localization.text("session.plan", plan.id(), plan.revision()));
            plan.loadout().ifPresent(loadout -> loadout.gear().forEach(gear -> output.println(
                localization.text("session.gearSnapshot", gear.sourceGearId(), gear.brand(), gear.model(), gear.selectedQuantity()))));
        });
    }
}
