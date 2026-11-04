package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import java.io.PrintStream;
import java.util.*;

/**
 * F07 command adapter (with the minimal F06 capability-profile entry it depends on). Shares PlanningService,
 * CapabilityNeedForm and LoadoutPresentation with the GUI, so both interfaces produce the same findings.
 */
final class MatchCommands {
    /** Process-local drafts and the last match; never persisted and cleared when the user switches. */
    static final class State {
        private EquipmentRequirementProfile profileDraft;
        private PhotographySession profileSession;
        private GearMatchReport report;
        private PhotographySession reportSession;
        private Candidate pending;
        void clear() { profileDraft = null; profileSession = null; report = null; reportSession = null; pending = null; }
    }
    private static final int DETAILED = 3;
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    MatchCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = line.strip().split("\\s+");
            String action = words.length < 2 ? "help" : words[1].toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> { output.println(localization.text("match.cli.help")); return 0; }
                case "capabilities" -> { CapabilityNeed.SUPPORTED.keySet().forEach(c -> output.println("  " + CapabilityNeedForm.capabilityHelp(c, localization))); return 0; }
                case "show" -> { return show(words); }
                case "profile" -> { return profile(words); }
                case "need" -> { return need(line, words); }
                case "run" -> { return run(words); }
                case "details" -> { return details(words); }
                case "select" -> { return select(words); }
                case "confirm" -> { return confirm(words); }
                case "cancel" -> {
                    if (state.pending == null) { output.println(localization.text("match.cli.noPending")); return 2; }
                    state.pending = null; output.println(localization.text("match.cancelled")); return 0;
                }
                default -> throw new LocalizedException("error.cli.option", action);
            }
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int show(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        var session = open(words[2]); if (session == null) return 2;
        output.println(localization.text("match.cli.session", session.title(), session.id()));
        showProfile(session);
        var saved = app.planning().selection(session.id());
        if (saved.hasError("match.error.noSavedSelection")) { output.println(localization.text("match.saved.none")); return 0; }
        if (!report(saved)) return 2;
        LoadoutPresentation.selection(saved.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }
    private void showProfile(PhotographySession session) {
        session.requirements().filter(ShootRequirement::confirmed).ifPresentOrElse(
            r -> output.println(localization.text("match.requirement", r.subject())), () -> output.println(localization.text("match.noRequirement")));
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) { output.println(localization.text("needs.noneSaved")); return; }
        output.println(localization.text("needs.savedTitle"));
        CapabilityNeedForm.describe(profile.get(), localization).forEach(text -> output.println("  " + text));
        output.println(localization.text("needs.manualNotice"));
    }

    private int profile(String[] words) {
        String sub = words.length < 3 ? "show" : words[2].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "show" -> { if (!requireDraft()) return 2; showDraft(); return 0; }
            case "cancel" -> { if (!requireDraft()) return 2; state.profileDraft = null; state.profileSession = null; output.println(localization.text("needs.cancelled")); return 0; }
            case "confirm" -> {
                if (!requireDraft()) return 2;
                var saved = app.planning().confirmProfile(state.profileSession, state.profileDraft);
                if (!report(saved)) return 2;
                String title = state.profileSession.title(); state.profileDraft = null; state.profileSession = null; state.report = null; state.pending = null;
                output.println(localization.text("needs.saved", title));
                CapabilityNeedForm.describe(saved.value().orElseThrow(), localization).forEach(text -> output.println("  " + text));
                return 0;
            }
            default -> {
                if (state.profileDraft != null) { output.println(localization.text("needs.cli.open")); return 2; }
                var session = open(words[2]); if (session == null) return 2;
                if (!session.state().canEditPlan()) { report(Result.failureKey("needs.error.historical")); return 2; }
                if (session.requirements().filter(ShootRequirement::confirmed).isEmpty()) { report(Result.failureKey("needs.error.requirement")); return 2; }
                state.profileSession = session;
                state.profileDraft = session.profile().map(EquipmentRequirementProfile::asDraft).orElse(new EquipmentRequirementProfile(List.of(), false));
                output.println(localization.text("needs.editing")); showDraft(); return 0;
            }
        }
    }
    private int need(String line, String[] words) {
        if (!requireDraft()) return 2;
        if (words.length >= 4 && words[2].equalsIgnoreCase("remove")) {
            state.profileDraft = CapabilityNeedForm.remove(state.profileDraft, words[3]); showDraft(); return 0;
        }
        if (words.length < 3) throw new LocalizedException("error.session.command");
        var tokens = InventoryCommands.tokenize(line);
        var values = new LinkedHashMap<String, String>(); values.put("capability", tokens.get(2));
        for (String token : tokens.subList(3, tokens.size())) {
            int split = token.indexOf('=');
            if (split <= 0) throw new LocalizedException("error.cli.option", token);
            if (values.putIfAbsent(token.substring(0, split), token.substring(split + 1)) != null) throw new LocalizedException("error.cli.repeated", token.substring(0, split));
        }
        var parsed = CapabilityNeedForm.parse(values);
        if (!report(parsed)) return 2;
        state.profileDraft = CapabilityNeedForm.put(state.profileDraft, parsed.value().orElseThrow()); showDraft(); return 0;
    }
    private void showDraft() {
        output.println(localization.text("needs.cli.draft", state.profileSession.title(), state.profileSession.id()));
        output.println(localization.text("needs.manualNotice"));
        if (state.profileDraft.needs().isEmpty()) output.println("  " + localization.text("needs.noNeeds"));
        CapabilityNeedForm.describe(state.profileDraft, localization).forEach(text -> output.println("  " + text));
        var problems = state.profileSession.requirements().filter(ShootRequirement::confirmed)
            .map(r -> state.profileDraft.validate(r)).orElse(Result.failureKey("needs.error.requirement"));
        output.println(problems.isSuccess() ? localization.text("needs.valid") : localization.text("needs.problems", String.join(" ", problems.errors(localization))));
        output.println(localization.text("needs.cli.next"));
    }

    private int run(String[] words) {
        if (words.length < 3 || words.length > 4) throw new LocalizedException("error.session.command");
        var session = open(words[2]); if (session == null) return 2;
        var strategies = app.planning().rankingStrategies();
        String strategy = words.length == 4 ? words[3].toLowerCase(Locale.ROOT) : strategies.getFirst();
        if (!strategies.contains(strategy)) { report(Result.failureKey("match.error.strategy", strategy)); return 2; }
        output.println(localization.text("match.running", LoadoutPresentation.strategyName(strategy, localization)));
        var result = app.planning().matchGear(session.id(), strategy);
        if (!report(result)) return 2;
        state.report = result.value().orElseThrow(); state.reportSession = session; state.pending = null;
        LoadoutPresentation.summary(state.report, localization).forEach(output::println);
        var shown = state.report.recommendations();
        for (int i = 0; i < Math.min(LoadoutPresentation.SHOWN, shown.size()); i++) {
            output.println();
            output.println(LoadoutPresentation.heading(shown.get(i), i + 1, localization));
            if (i < DETAILED) LoadoutPresentation.candidate(shown.get(i), localization).forEach(output::println);
            else shown.get(i).loadout().gear().forEach(g -> output.println("  " + LoadoutPresentation.gear(g, localization)));
        }
        output.println();
        output.println(localization.text("match.rejected.title", state.report.rejectedCombinations()));
        LoadoutPresentation.rejections(state.report, localization).forEach(output::println);
        output.println(localization.text(shown.isEmpty() ? "match.cli.nothingToSelect" : "match.cli.next"));
        return 0;
    }
    private int details(String[] words) {
        var candidate = candidate(words); if (candidate == null) return 2;
        output.println(LoadoutPresentation.heading(candidate, index(words) + 1, localization));
        LoadoutPresentation.candidate(candidate, localization).forEach(output::println);
        return 0;
    }
    private int select(String[] words) {
        var candidate = candidate(words); if (candidate == null) return 2;
        var fresh = app.sessions().open(state.reportSession.id());
        if (!report(fresh)) return 2;
        state.reportSession = fresh.value().orElseThrow(); state.pending = candidate;
        LoadoutPresentation.confirmation(candidate, state.reportSession, localization).forEach(output::println);
        output.println(localization.text(state.reportSession.selectedLoadout().isPresent() ? "match.cli.confirmReplace" : "match.cli.confirm"));
        return 0;
    }
    private int confirm(String[] words) {
        if (state.pending == null) { output.println(localization.text("match.cli.noPending")); return 2; }
        boolean replace = words.length == 3 && words[2].equalsIgnoreCase("replace");
        if (words.length > 3 || words.length == 3 && !replace) throw new LocalizedException("error.cli.option", words[words.length - 1]);
        if (state.reportSession.selectedLoadout().isPresent() && !replace) { output.println(localization.text("match.cli.confirmReplace")); return 2; }
        var saved = app.planning().selectLoadout(state.reportSession, state.pending.loadout(), replace);
        if (!report(saved)) return 2;
        String title = state.reportSession.title(); UUID id = state.reportSession.id();
        state.pending = null; state.report = null; state.reportSession = null;
        output.println(localization.text("match.saved", title));
        output.println(localization.text("match.cli.savedHint", id));
        return 0;
    }

    private Candidate candidate(String[] words) {
        if (state.report == null) { output.println(localization.text("match.cli.noReport")); return null; }
        int index = index(words);
        if (index < 0 || index >= state.report.recommendations().size()) { report(Result.failureKey("match.error.candidateNumber", state.report.recommendations().size())); return null; }
        return state.report.recommendations().get(index);
    }
    private static int index(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        try { return Integer.parseInt(words[2]) - 1; } catch (NumberFormatException error) { return -1; }
    }
    private PhotographySession open(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean requireDraft() {
        if (state.profileDraft != null) return true;
        output.println(localization.text("needs.cli.noDraft")); return false;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
