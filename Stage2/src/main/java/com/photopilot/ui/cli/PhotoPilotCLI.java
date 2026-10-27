package com.photopilot.ui.cli;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.config.FeatureCatalog;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.i18n.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;

/** Stage 1 UML element 4. Shared application CLI including the deterministic F05 workflow. */
public class PhotoPilotCLI {
    private PhotoPilotFacade app;
    private ImagePrivacyBoundary privacy;
    private final Scanner input;
    private final PrintStream output;
    private final LocalizationService localization;

    public PhotoPilotCLI(PhotoPilotFacade app, ImagePrivacyBoundary privacy) {
        this(app, privacy, new Scanner(System.in), System.out);
    }

    public PhotoPilotCLI(PhotoPilotFacade app, ImagePrivacyBoundary privacy, Scanner input, PrintStream output) {
        this(app, privacy, input, output, LocalizationService.english());
    }

    public PhotoPilotCLI(PhotoPilotFacade app, ImagePrivacyBoundary privacy, Scanner input, PrintStream output, LocalizationService localization) {
        this.app = Objects.requireNonNull(app);
        this.privacy = Objects.requireNonNull(privacy);
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
        this.localization = Objects.requireNonNull(localization);
    }

    public static void main(String[] args) {
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(System.getenv())) {
            new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(System.in, StandardCharsets.UTF_8),
                new PrintStream(System.out, true, StandardCharsets.UTF_8), bootstrap.localization()).run(args);
        } catch (IllegalArgumentException error) {
            System.err.println(EnglishMessages.text("cli.configuration", error.getMessage()));
        }
    }

    public void run(String[] args) {
        var commands = new ArrayList<String>();
        boolean invalid = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--lang") || arg.startsWith("--lang=")) {
                String tag = arg.startsWith("--lang=") ? arg.substring(7) : i + 1 < args.length ? args[++i] : "";
                invalid |= !Language.supports(tag);
                localization.useLanguage(tag);
            } else commands.add(arg);
        }
        if (invalid) output.println(localization.text("cli.invalidLanguage"));
        if (localization.preferenceFailure()) output.println(localization.text(localization.preferenceFailureKey()));
        output.println(localization.text("cli.banner"));
        output.println(localization.text("cli.ready"));
        if (!commands.isEmpty()) {
            for (String arg : commands) if (execute(arg) == 1) break;
            return;
        }
        while (input.hasNextLine()) if (execute(input.nextLine()) == 1) break;
    }

    /** 0: handled; 1: exit requested; 2: unavailable or unknown command. */
    public int execute(String line) {
        String command = Objects.requireNonNull(line).strip().toLowerCase(Locale.ROOT);
        if (command.equals("lang") || command.startsWith("lang ")) {
            String[] words = line.strip().split("\\s+", -1);
            if (words.length == 1) { output.println(localization.text("cli.language", localization.language().tag())); return 0; }
            if (words.length != 2 || !Language.supports(words[1])) { output.println(localization.text("cli.invalidLanguage")); return 2; }
            boolean saved = localization.selectLanguage(words[1]);
            output.println(localization.text(saved ? "settings.saved" : "settings.saveFailed"));
            return saved ? 0 : 2;
        }
        if (command.equals("f05") || command.equals("gear") || command.startsWith("gear ")) {
            return new InventoryCommands(app, output, localization).execute(command.equals("f05") ? "gear list" : line.strip());
        }
        if (command.equals("f08") || command.equals("calc") || command.startsWith("calc ")) {
            return new CalculationCommands(app, output, localization).execute(command.equals("f08") ? "calc help" : line);
        }
        switch (command) {
            case "help" -> { output.println(localization.text("cli.help")); output.println(localization.text("cli.languageHelp")); }
            case "status" -> FeatureCatalog.lines(localization).forEach(output::println);
            case "exit", "quit" -> { output.println(localization.text("cli.closed")); return 1; }
            case "" -> { return 0; }
            default -> {
                output.println(command.matches("f(0[1-9]|1[01])")
                    ? localization.text("feature.unavailable", command.toUpperCase(Locale.ROOT))
                    : localization.text("cli.unknown"));
                return 2;
            }
        }
        return 0;
    }
}
