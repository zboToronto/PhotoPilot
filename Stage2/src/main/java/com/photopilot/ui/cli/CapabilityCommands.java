package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.i18n.LocalizationService;
import java.io.PrintStream;
import java.util.Locale;

final class CapabilityCommands {
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    CapabilityCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization) {
        this.app = app; this.output = output; this.localization = localization;
    }
    int execute(String line) {
        String[] words = line.strip().toLowerCase(Locale.ROOT).split("\\s+");
        String action = words.length == 1 ? "all" : words[1];
        if (words.length > 2 || !java.util.Set.of("all", "overview", "coverage", "aperture", "compatibility", "summary", "help").contains(action)) {
            output.println(localization.text("capability.cli.help")); return 2;
        }
        if (action.equals("help")) { output.println(localization.text("capability.cli.help")); return 0; }
        var result = app.capabilities().analyze();
        if (!result.isSuccess()) { result.errors(localization).forEach(output::println); return 2; }
        var report = result.value().orElseThrow();
        output.println(action.equals("all") ? CapabilityPresentation.all(report, localization)
            : CapabilityPresentation.format(report, CapabilityPresentation.Section.valueOf(action.toUpperCase(Locale.ROOT)), localization));
        return 0;
    }
}
