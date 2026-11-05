package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.service.CapabilityProposal;
import com.photopilot.service.PlanningService;
import java.io.PrintStream;
import java.util.*;

/**
 * F06 command adapter: analyze (Gemini) or enter capability needs, review/edit/clarify the draft, then confirm.
 * Shares PlanningService, CapabilityNeedForm and the domain rules with the GUI; F07's {@code match profile/need}
 * commands delegate here, so there is one draft, one validation and one save path.
 */
final class NeedsCommands {
    /** The CLI process's open capability draft and the session version it was made for; never persisted. */
    static final class State {
        private CapabilityProposal proposal;
        private PhotographySession session;
        /** The draft started from the saved profile, so confirming it is the explicit replacement. */
        private boolean editing;
        void clear() { proposal = null; session = null; editing = false; }
    }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    NeedsCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = line.strip().split("\\s+");
            String action = words.length < 2 ? "help" : words[1].toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> { output.println(localization.text("needs.cli.help")); if (state.proposal != null) showDraft(); return 0; }
                case "capabilities" -> { CapabilityNeed.SUPPORTED.keySet().forEach(c -> output.println("  " + CapabilityNeedForm.capabilityHelp(c, localization))); return 0; }
                case "show" -> { return show(words); }
                case "analyze" -> { return analyze(words); }
                case "manual", "edit" -> { return manual(words); }
                case "draft" -> { if (!requireDraft()) return 2; showDraft(); return 0; }
                case "set" -> { return set(line, words); }
                case "remove" -> { return remove(words); }
                case "clarify" -> { return clarify(line); }
                case "confirm" -> { return confirm(words); }
                case "cancel" -> {
                    if (!requireDraft()) return 2;
                    state.clear(); output.println(localization.text("needs.cancelled")); return 0;
                }
                default -> throw new LocalizedException("error.cli.option", action);
            }
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int show(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        var session = open(words[2]); if (session == null) return 2;
        output.println(localization.text("needs.cli.session", session.title(), session.id()));
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) output.println(localization.text("match.noRequirement"));
        else { output.println(localization.text("needs.requirementTitle")); RequirementForm.describe(requirement.get(), localization).forEach(text -> output.println("  " + text)); }
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) { output.println(localization.text("needs.noneSaved")); return 0; }
        output.println(localization.text("needs.savedTitle"));
        CapabilityNeedForm.profile(profile.get(), localization).forEach(output::println);
        var usable = PlanningService.usableProfile(session);
        if (!usable.isSuccess()) output.println(localization.text("needs.inconsistent", String.join(" ", usable.errors(localization))));
        return 0;
    }
    private int analyze(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        if (state.proposal != null) { output.println(localization.text("needs.cli.open")); return 2; }
        var session = open(words[2]); if (session == null) return 2;
        output.println(localization.text("needs.analyzing"));
        var proposed = app.proposeNeeds(session.id());
        if (!proposed.isSuccess()) {
            output.println(localization.text("needs.aiFailed", String.join(" ", proposed.errors(localization))));
            output.println(localization.text("needs.cli.manualHint", session.id()));
            return 2;
        }
        state.proposal = proposed.value().orElseThrow(); state.session = session; state.editing = false;
        output.println(localization.text("needs.proposed")); showDraft(); return 0;
    }
    private int manual(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        if (state.proposal != null) { output.println(localization.text("needs.cli.open")); return 2; }
        var session = open(words[2]); if (session == null) return 2;
        var started = app.planning().manualNeeds(session.id());
        if (!report(started)) return 2;
        state.proposal = started.value().orElseThrow(); state.session = session;
        state.editing = session.profile().filter(EquipmentRequirementProfile::reviewed).isPresent();
        output.println(localization.text("needs.editing")); showDraft(); return 0;
    }
    private int set(String line, String[] words) {
        if (!requireDraft()) return 2;
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
        state.proposal = state.proposal.withProfile(CapabilityNeedForm.put(state.proposal.profile(), parsed.value().orElseThrow()));
        showDraft(); return 0;
    }
    private int remove(String[] words) {
        if (!requireDraft()) return 2;
        if (words.length != 3) throw new LocalizedException("error.session.command");
        state.proposal = state.proposal.withProfile(CapabilityNeedForm.remove(state.proposal.profile(), words[2]));
        showDraft(); return 0;
    }
    private int clarify(String line) {
        if (!requireDraft()) return 2;
        String answers = line.strip().split("\\s+").length < 3 ? "" : RequirementCommands.remainder(line, 2);
        output.println(localization.text("needs.clarifying"));
        var updated = app.planning().clarifyNeeds(state.session.id(), state.proposal, answers);
        if (!updated.isSuccess()) {
            // The open draft, including manual edits, is kept unchanged.
            output.println(localization.text("needs.aiFailed", String.join(" ", updated.errors(localization))));
            return 2;
        }
        state.proposal = updated.value().orElseThrow(); output.println(localization.text("needs.proposed")); showDraft(); return 0;
    }
    private int confirm(String[] words) {
        if (!requireDraft()) return 2;
        boolean replace = words.length == 3 && words[2].equalsIgnoreCase("replace");
        if (words.length > 3 || words.length == 3 && !replace) throw new LocalizedException("error.cli.option", words[words.length - 1]);
        boolean saved = state.session.profile().filter(EquipmentRequirementProfile::reviewed).isPresent();
        if (saved && !replace && !state.editing) { output.println(localization.text("needs.cli.confirmReplace")); return 2; }
        var stored = app.planning().confirmProfile(state.session, state.proposal.profile(), replace || state.editing);
        if (!report(stored)) return 2;
        String title = state.session.title(); UUID id = state.session.id(); state.clear();
        output.println(localization.text("needs.saved", title));
        CapabilityNeedForm.profile(stored.value().orElseThrow(), localization).forEach(output::println);
        output.println(localization.text("needs.cli.savedHint", id));
        return 0;
    }

    private void showDraft() {
        var draft = state.proposal.profile();
        output.println(localization.text("needs.cli.draft", state.session.title(), state.session.id()));
        CapabilityNeedForm.profile(draft, localization).forEach(output::println);
        if (draft.needs().isEmpty()) output.println("  " + localization.text("needs.noNeeds"));
        CapabilityNeedForm.review(state.proposal, localization).forEach(output::println);
        if (!state.proposal.clarificationQuestions().isEmpty()) {
            output.println(localization.text("requirement.questions"));
            CapabilityNeedForm.questions(state.proposal).forEach(text -> output.println("  " + text));
        }
        var checked = state.session.requirements().filter(ShootRequirement::confirmed)
            .map(draft::validate).orElse(Result.failureKey("needs.error.requirement"));
        output.println(checked.isSuccess() ? localization.text("needs.valid") : localization.text("needs.problems", String.join(" ", checked.errors(localization))));
        if (state.session.profile().filter(EquipmentRequirementProfile::reviewed).isPresent() && !state.editing) output.println(localization.text("needs.cli.replaces"));
        output.println(localization.text("needs.cli.next"));
    }
    private PhotographySession open(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean requireDraft() {
        if (state.proposal != null) return true;
        output.println(localization.text("needs.cli.noDraft")); return false;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
