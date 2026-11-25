package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.i18n.LocalizationService;
import java.io.PrintStream;
import java.util.*;

/**
 * {@code capability} commands: the read-only inventory capability report in the terminal. Every command reads the
 * current inventory through the facade once and formats the shared GearCapabilityService result
 * ({@link CapabilityTerminalReport}); nothing is written. Numbers refer to the rows of {@code capability cameras} and
 * {@code capability lenses}. Earlier subcommands (all, overview, coverage, aperture, compatibility, summary) keep their
 * names; without a subcommand the concise summary is shown.
 */
final class CapabilityCommands {
    private static final Set<String> SIMPLE = Set.of("summary", "overview", "cameras", "lenses", "aperture", "quality", "all", "technical");
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final int width;
    CapabilityCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization) {
        this(app, output, localization, CapabilityTerminalReport.width(System.getenv()));
    }
    CapabilityCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, int width) {
        this.app = app; this.output = output; this.localization = localization; this.width = width;
    }
    int execute(String line) {
        String[] words = line.strip().toLowerCase(Locale.ROOT).split("\\s+");
        String action = words.length == 1 ? "summary" : words[1];
        boolean valid = SIMPLE.contains(action) && words.length <= 2
            || Set.of("coverage", "compatibility", "matrix").contains(action) && words.length <= 3
            || action.equals("pair") && words.length == 4
            || action.equals("help") && words.length == 2;
        if (!valid) { output.println(localization.text("capability.cli.help")); return 2; }
        if (action.equals("help")) { output.println(localization.text("capability.cli.help")); return 0; }
        var result = app.capabilities().analyze();
        if (!result.isSuccess()) { result.errors(localization).forEach(output::println); return 2; }
        var report = new CapabilityTerminalReport(result.value().orElseThrow(), localization, width);
        List<String> lines;
        switch (action) {
            case "summary" -> lines = report.summary();
            case "overview" -> lines = report.overview();
            case "cameras" -> lines = report.cameras();
            case "lenses" -> lines = report.lenses();
            case "aperture" -> lines = report.aperture();
            case "quality" -> lines = report.quality();
            case "all", "technical" -> lines = report.technical();
            case "coverage" -> {
                if (words.length == 2) lines = report.coverage(Optional.empty());
                else {
                    var camera = index(words[2], report.cameraCount(), "capability.cli.badCamera");
                    if (camera.isEmpty()) return 2;
                    lines = report.coverage(Optional.of(result.value().orElseThrow().cameras().get(camera.get())));
                }
            }
            case "compatibility", "matrix" -> {
                Optional<Integer> camera = Optional.empty();
                if (words.length == 3) { camera = index(words[2], report.cameraCount(), "capability.cli.badCamera"); if (camera.isEmpty()) return 2; }
                lines = report.matrix(camera);
            }
            case "pair" -> {
                var lens = index(words[2], report.lensCount(), "capability.cli.badLens");
                if (lens.isEmpty()) return 2;
                var camera = index(words[3], report.cameraCount(), "capability.cli.badCamera");
                if (camera.isEmpty()) return 2;
                lines = report.pair(lens.get(), camera.get());
            }
            default -> throw new IllegalStateException(action);
        }
        lines.forEach(output::println);
        output.println();
        return 0;
    }

    /** Zero-based index of a 1-based row number, or a localized message naming the valid range. */
    private Optional<Integer> index(String word, int count, String key) {
        try {
            int number = Integer.parseInt(word);
            if (number >= 1 && number <= count) return Optional.of(number - 1);
        } catch (NumberFormatException ignored) { }
        output.println(localization.text(key, word, count));
        return Optional.empty();
    }
}
