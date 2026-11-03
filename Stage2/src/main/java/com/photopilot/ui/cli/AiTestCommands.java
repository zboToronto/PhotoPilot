package com.photopilot.ui.cli;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.i18n.LocalizationService;
import java.io.PrintStream;

/** Diagnostic `ai-test`: one real Gemini request through the shared model port. Not a PhotoPilot AI feature. */
final class AiTestCommands {
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    AiTestCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization) {
        this.app = app; this.output = output; this.localization = localization;
    }
    int execute() {
        var diagnostics = app.aiDiagnostics();
        output.println(localization.text("ai.test.checking"));
        var configured = diagnostics.checkConfiguration();
        if (!configured.isSuccess()) return fail(configured.errors(localization));
        output.println(localization.text("ai.test.sending", diagnostics.modelName()));
        var reply = diagnostics.sendTestPrompt();
        if (!reply.isSuccess()) return fail(reply.errors(localization));
        output.println();
        output.println(localization.text("ai.test.response"));
        output.println(reply.value().orElseThrow());
        output.println();
        output.println(localization.text("ai.test.success"));
        return 0;
    }
    private int fail(java.util.List<String> errors) {
        output.println();
        errors.forEach(error -> output.println(localization.text("ai.test.failed", error)));
        return 2;
    }
}
