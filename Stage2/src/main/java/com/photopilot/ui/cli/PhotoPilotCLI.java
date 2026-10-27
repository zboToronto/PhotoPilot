package com.photopilot.ui.cli;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.config.FeatureCatalog;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
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

    public PhotoPilotCLI(PhotoPilotFacade app, ImagePrivacyBoundary privacy) {
        this(app, privacy, new Scanner(System.in), System.out);
    }

    public PhotoPilotCLI(PhotoPilotFacade app, ImagePrivacyBoundary privacy, Scanner input, PrintStream output) {
        this.app = Objects.requireNonNull(app);
        this.privacy = Objects.requireNonNull(privacy);
        this.input = Objects.requireNonNull(input);
        this.output = Objects.requireNonNull(output);
    }

    public static void main(String[] args) {
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(System.getenv())) {
            new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary()).run(args);
        } catch (IllegalArgumentException error) {
            System.err.println("PhotoPilot configuration error: " + error.getMessage());
        }
    }

    public void run(String[] args) {
        output.println("PhotoPilot - Stage 2 scaffolding build");
        output.println("Shared application layer initialized. Type help, status, F01-F11, or exit.");
        if (args.length > 0) {
            for (String arg : args) if (execute(arg) == 1) break;
            return;
        }
        while (input.hasNextLine()) if (execute(input.nextLine()) == 1) break;
    }

    /** 0: handled; 1: exit requested; 2: unavailable or unknown command. */
    public int execute(String line) {
        String command = Objects.requireNonNull(line).strip().toLowerCase(Locale.ROOT);
        if (command.equals("f05") || command.equals("gear") || command.startsWith("gear ")) {
            return new InventoryCommands(app, output).execute(command.equals("f05") ? "gear list" : line.strip());
        }
        switch (command) {
            case "help" -> output.println("help: commands | status: feature status | gear help: inventory commands | F05: inventory | other F01-F11: availability | exit: close");
            case "status" -> FeatureCatalog.lines().forEach(output::println);
            case "exit", "quit" -> { output.println("PhotoPilot closed."); return 1; }
            case "" -> { return 0; }
            default -> {
                output.println(command.matches("f(0[1-9]|1[01])")
                    ? FeatureCatalog.unavailable(command.toUpperCase(Locale.ROOT))
                    : "Unknown command. Type help.");
                return 2;
            }
        }
        return 0;
    }
}
