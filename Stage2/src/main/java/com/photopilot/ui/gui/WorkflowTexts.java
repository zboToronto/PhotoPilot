package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.workflow.SessionWorkflow;

/** The session workflow phrases every view shares, so the sidebar, Home, pages and the session list read the same. */
final class WorkflowTexts {
    private WorkflowTexts() { }

    /** "Ready to shoot", or "Ready to shoot · needs attention" while a milestone needs review or action. */
    static String phase(SessionWorkflow workflow, LocalizationService localization) {
        String phase = localization.text(workflow.phase().labelKey());
        return workflow.attention() ? localization.text("workflow.phaseAttention", phase) : phase;
    }

    /** "Next: Photography Plan", or that every milestone is done. */
    static String next(SessionWorkflow workflow, LocalizationService localization) {
        return workflow.next().map(state -> localization.text("progress.next", localization.text(state.milestone().titleKey())))
            .orElseGet(() -> localization.text("workflow.complete"));
    }

    /** One line: phase · "57% · 4 of 7 milestones" · next step. */
    static String context(SessionWorkflow workflow, LocalizationService localization) {
        return localization.text("workflow.context", phase(workflow, localization), workflow.progress().render(localization), next(workflow, localization));
    }
}
