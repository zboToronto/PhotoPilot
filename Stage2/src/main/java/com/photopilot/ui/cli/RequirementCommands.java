package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import java.io.PrintStream;
import java.time.ZoneId;
import java.util.*;

/** F03 command adapter. Shares PlanningService, RequirementForm and domain validation with the GUI. */
final class RequirementCommands {
    /** The CLI process's open draft and the session version it was made for; never persisted. */
    static final class State {
        private ShootRequirement draft;
        private PhotographySession session;
        private String lastRequest = "";
        void clear() { draft = null; session = null; lastRequest = ""; }
        boolean hasDraft() { return draft != null; }
        /** F02 handoff: an unconfirmed draft for review here; refused while another draft is open. */
        boolean open(ShootRequirement unconfirmed, PhotographySession target) {
            if (draft != null || unconfirmed == null || unconfirmed.confirmed() || target == null) return false;
            draft = unconfirmed; session = target; return true;
        }
    }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    RequirementCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = line.strip().split("\\s+");
            String action = words.length < 2 ? "help" : words[1].toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> { output.println(localization.text("requirement.cli.help")); if (state.draft != null) showDraft(); return 0; }
                case "show" -> { return show(words); }
                case "parse" -> { return parse(words, line); }
                case "manual" -> { return manual(words, line); }
                case "draft" -> { if (!requireDraft()) return 2; showDraft(); return 0; }
                case "set" -> { return set(line); }
                case "clarify" -> { return clarify(line); }
                case "confirm" -> { return confirm(); }
                case "cancel" -> {
                    if (!requireDraft()) return 2;
                    state.clear(); output.println(localization.text("requirement.cancelled")); return 0;
                }
                default -> throw new LocalizedException("error.cli.option", action);
            }
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int show(String[] words) {
        if (words.length != 3) throw new LocalizedException("error.session.command");
        UUID id = SessionForm.id(words[2]);
        var found = app.planning().requirement(id);
        if (found.hasError("requirement.error.none")) { output.println(localization.text("requirement.noneSaved")); return 0; }
        if (!report(found)) return 2;
        output.println(localization.text("requirement.cli.saved", id));
        RequirementForm.describe(found.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }
    private int parse(String[] words, String line) {
        if (words.length < 4) throw new LocalizedException("requirement.error.request");
        if (state.draft != null) { output.println(localization.text("requirement.cli.sessionChanged")); return 2; }
        var session = open(words[2]); if (session == null) return 2;
        String text = remainder(line, 3);
        state.lastRequest = text;
        output.println(localization.text("requirement.analyzing"));
        var parsed = app.parse(session.id(), text);
        if (!parsed.isSuccess()) {
            // The request is retained so manual entry can start from it without retyping.
            output.println(localization.text("requirement.aiFailed", String.join("; ", parsed.errors(localization))));
            output.println(localization.text("requirement.cli.keptRequest", session.id()));
            return 2;
        }
        state.draft = parsed.value().orElseThrow(); state.session = session;
        output.println(localization.text("requirement.parsed")); showDraft(); return 0;
    }
    private int manual(String[] words, String line) {
        if (words.length < 3) throw new LocalizedException("error.session.command");
        if (state.draft != null) { output.println(localization.text("requirement.cli.sessionChanged")); return 2; }
        var session = open(words[2]); if (session == null) return 2;
        if (!session.state().canEditPlan()) { report(Result.failureKey("requirement.error.historical")); return 2; }
        String text = words.length > 3 ? remainder(line, 3) : state.lastRequest;
        state.draft = RequirementForm.blank(text); state.session = session;
        output.println(localization.text("requirement.manualStarted")); showDraft(); return 0;
    }
    private int set(String line) {
        if (!requireDraft()) return 2;
        var tokens = InventoryCommands.tokenize(line);
        var edits = new LinkedHashMap<String, String>();
        for (String token : tokens.subList(2, tokens.size())) {
            int split = token.indexOf('=');
            if (split <= 0) throw new LocalizedException("error.cli.option", token);
            if (edits.putIfAbsent(token.substring(0, split), token.substring(split + 1)) != null)
                throw new LocalizedException("error.cli.repeated", token.substring(0, split));
        }
        var applied = RequirementForm.apply(state.draft, edits, ZoneId.systemDefault());
        if (!report(applied)) return 2;
        state.draft = applied.value().orElseThrow(); showDraft(); return 0;
    }
    private int clarify(String line) {
        if (!requireDraft()) return 2;
        String answers = line.strip().split("\\s+").length < 3 ? "" : remainder(line, 2);
        output.println(localization.text("requirement.clarifying"));
        var updated = app.planning().clarify(state.session.id(), state.draft, answers);
        if (!updated.isSuccess()) {
            output.println(localization.text("requirement.aiFailed", String.join("; ", updated.errors(localization))));
            return 2;
        }
        state.draft = updated.value().orElseThrow(); output.println(localization.text("requirement.parsed")); showDraft(); return 0;
    }
    private int confirm() {
        if (!requireDraft()) return 2;
        var saved = app.planning().confirm(state.session, state.draft);
        if (!report(saved)) return 2;
        UUID id = state.session.id(); String title = state.session.title(); state.clear();
        output.println(localization.text("requirement.saved", title));
        output.println(localization.text("requirement.cli.saved", id));
        RequirementForm.describe(saved.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }

    private PhotographySession open(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean requireDraft() {
        if (state.draft != null) return true;
        output.println(localization.text("requirement.cli.noDraft")); return false;
    }
    private void showDraft() {
        var draft = state.draft;
        output.println(localization.text("requirement.cli.draft", state.session.id()));
        output.println(localization.text("requirement.original", draft.originalText().isBlank() ? localization.text("requirement.unspecified") : draft.originalText()));
        RequirementForm.describe(draft, localization).forEach(output::println);
        if (!draft.reviewFields().isEmpty()) output.println(localization.text("requirement.flaggedList", names(draft.reviewFields())));
        if (!draft.unspecifiedFields().isEmpty()) output.println(localization.text("requirement.unspecifiedList", names(draft.unspecifiedFields())));
        if (!draft.clarificationQuestions().isEmpty()) {
            output.println(localization.text("requirement.cli.questions"));
            for (int i = 0; i < draft.clarificationQuestions().size(); i++)
                output.println(localization.text("requirement.cli.question", i + 1, draft.clarificationQuestions().get(i)));
        }
        var problems = draft.problems();
        output.println(problems.isEmpty() ? localization.text("requirement.valid") : localization.text("requirement.problems",
            String.join(" ", problems.stream().map(problem -> problem.message().render(localization)).toList())));
        if (state.session.requirements().isPresent()) output.println(localization.text("requirement.cli.replaces"));
        output.println(localization.text("requirement.cli.next"));
    }
    private String names(Collection<String> fields) {
        return String.join(", ", fields.stream().map(field -> localization.text("requirement.field." + field)).toList());
    }
    /** Free text after the first {@code skip} words, taken verbatim so apostrophes need no quoting. */
    static String remainder(String line, int skip) {
        String rest = line.strip();
        for (int i = 0; i < skip; i++) rest = rest.replaceFirst("^\\S+\\s*", "");
        rest = rest.strip();
        if (rest.length() >= 2 && (rest.startsWith("\"") && rest.endsWith("\"") || rest.startsWith("'") && rest.endsWith("'")))
            rest = rest.substring(1, rest.length() - 1).strip();
        return rest;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
