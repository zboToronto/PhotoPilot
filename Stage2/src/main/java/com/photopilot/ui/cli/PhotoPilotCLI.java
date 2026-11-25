package com.photopilot.ui.cli;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.application.ProfileManager;
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
    private PrintStream diagnostics = System.err;
    /** Execution feedback lines (stages, retries, persistence, next step); standard error in main, off when embedded. */
    private PrintStream progress;
    private LocalizationService localization;
    private ProfileManager profiles;
    private int exitCode;
    // Embedded callers historically supply an interactive Scanner; main detects redirected stdin explicitly.
    private boolean interactiveInput = true;
    private boolean readingCommands;
    private boolean failedProfileCommand;
    // True once --lang chose this invocation's language; otherwise each profile shows its own saved language.
    private boolean languageOverride;
    // Guided Discovery wizard presentation: plain unless main detects a colour terminal; width from COLUMNS unless set.
    private TerminalStyle terminalStyle = TerminalStyle.plain();
    private int terminalWidth;
    private boolean echoInput;
    private final RequirementCommands.State requirementDraft = new RequirementCommands.State();
    private final MatchCommands.State matchState = new MatchCommands.State();
    private final NeedsCommands.State needsState = new NeedsCommands.State();
    private final EnvironmentCommands.State environmentState = new EnvironmentCommands.State();
    private final PlanCommands.State planState = new PlanCommands.State();
    private final DiscoveryCommands.State discoveryState = new DiscoveryCommands.State();
    private final ImportCommands.State importState = new ImportCommands.State();
    private final ReviewCommands.State reviewState = new ReviewCommands.State();

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

    public PhotoPilotCLI(ProfileManager profiles, Scanner input, PrintStream output) {
        this(profiles.activeContext().facade(), ImagePrivacyBoundary.fromEnvironment(System.getenv()), input, output, profiles.activeContext().localization());
        this.profiles = profiles;
    }
    private void refreshContext() {
        String language = localization.language().tag();
        app = profiles.activeContext().facade(); localization = profiles.activeContext().localization();
        requirementDraft.clear(); matchState.clear(); needsState.clear(); environmentState.clear(); planState.clear(); discoveryState.clear(); reviewState.clear(); // Drafts belong to the previous user's session data.
        importState.clear(privacy); // An open F04 import releases its temporary image data.
        if (languageOverride) localization.useLanguage(language); // Invocation-only language choice also survives profile switching.
    }
    public int exitCode() { return exitCode; }
    /** Where unexpected command failures write their stack trace (stderr by default). */
    public void diagnostics(PrintStream stream) { diagnostics = Objects.requireNonNull(stream); }
    /**
     * Where execution feedback goes (null: none). Never standard output, so its text and scripts reading it are unchanged.
     */
    public void progress(PrintStream stream) { progress = stream; }
    /** Colour for the interactive wizard only (main: detected from the environment and console; embedded: plain). */
    void terminalStyle(TerminalStyle style) { terminalStyle = Objects.requireNonNull(style); }
    /** Columns the wizard wraps to (0: COLUMNS or 80). */
    void terminalWidth(int columns) { terminalWidth = columns; }
    /** Repeats wizard answers after their prompts, for recorded transcripts. */
    void echoInput(boolean on) { echoInput = on; }
    /**
     * Whether standard input and output are an interactive terminal (prompts and colour are allowed). JDK 21 returns no
     * Console when a stream is redirected; JDK 22 and later return one anyway, so there its isTerminal() decides (looked
     * up reflectively, because the project compiles for release 21).
     */
    static boolean interactiveConsole() {
        var console = System.console();
        if (console == null) return false;
        if (Runtime.version().feature() < 22) return true;
        try { return Boolean.TRUE.equals(java.io.Console.class.getMethod("isTerminal").invoke(console)); }
        catch (ReflectiveOperationException | RuntimeException unknown) { return false; }
    }

    public static void main(String[] args) {
        int status;
        try (ProfileManager profiles = new ProfileManager(System.getenv(), startupProfile(args))) {
            var bootstrap = profiles.activeContext();
            bootstrap.storageNotice().ifPresent(note -> new PrintStream(System.err, true, StandardCharsets.UTF_8)
                .println(note.render(bootstrap.localization())));
            var cli = new PhotoPilotCLI(profiles, new Scanner(System.in, StandardCharsets.UTF_8), new PrintStream(System.out, true, StandardCharsets.UTF_8));
            cli.interactiveInput = interactiveConsole();
            cli.terminalStyle(TerminalStyle.detect(System.getenv(), cli.interactiveInput));
            // Progress on standard error (flushed per line); PHOTOPILOT_PROGRESS=0 turns it off for scripts that capture stderr.
            if (!"0".equals(System.getenv("PHOTOPILOT_PROGRESS"))) cli.progress(new PrintStream(System.err, true, StandardCharsets.UTF_8));
            cli.run(args); status = cli.exitCode();
        } catch (IllegalArgumentException error) {
            // Another window or command writing the profile store is temporary contention, not a configuration problem.
            String key = com.photopilot.repository.StorageBusy.causedBy(error) ? "cli.storageBusy" : "cli.configuration";
            new PrintStream(System.err, true, StandardCharsets.UTF_8).println(EnglishMessages.text(key, error.getMessage()));
            System.exit(2);
            return;
        }
        if (status != 0) System.exit(status);
    }

    private static java.util.UUID startupProfile(String[] args) {
        String selected = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--lang")) { i++; continue; }
            if (arg.equals("--profile") || arg.startsWith("--profile=")) {
                String value = arg.startsWith("--profile=") ? arg.substring(10) : i + 1 < args.length ? args[++i] : "";
                if (selected != null || value.isBlank()) throw new LocalizedException("error.profile.option");
                selected = value;
            }
        }
        return selected == null ? null : ProfileCommands.id(selected);
    }

    public void run(String[] args) {
        var commands = new ArrayList<String>();
        boolean invalid = false;
        String selectedProfile = null; boolean profileOptionInvalid = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--lang") || arg.startsWith("--lang=")) {
                String tag = arg.startsWith("--lang=") ? arg.substring(7) : i + 1 < args.length ? args[++i] : "";
                invalid |= !Language.supports(tag);
                localization.useLanguage(tag); languageOverride = true;
            } else if (arg.equals("--profile") || arg.startsWith("--profile=")) {
                String value = arg.startsWith("--profile=") ? arg.substring(10) : i + 1 < args.length ? args[++i] : "";
                if (selectedProfile != null || value.isBlank()) profileOptionInvalid = true;
                selectedProfile = value;
            } else commands.add(arg);
        }
        if (invalid) { output.println(localization.text("cli.invalidLanguage")); exitCode = 2; }
        if (selectedProfile != null) {
            try {
                if (profiles == null || profileOptionInvalid) throw new LocalizedException("error.profile.option");
                var selected = profiles.selectProfile(ProfileCommands.id(selectedProfile), true);
                if (!selected.isSuccess()) { selected.errors(localization).forEach(output::println); exitCode = 2; return; }
                refreshContext();
            } catch (LocalizedException error) { output.println(error.localizedMessage().render(localization)); exitCode = 2; return; }
        }
        if (localization.preferenceFailure()) output.println(localization.text(localization.preferenceFailureKey()));
        output.println(localization.text("cli.banner"));
        output.println(localization.text("cli.ready"));
        if (profiles != null) dispatch("profile current");
        if (!commands.isEmpty()) {
            for (String arg : commands) if (dispatch(arg) == 1 || failedProfileCommand) break;
            return;
        }
        readingCommands = true;
        while (input.hasNextLine()) {
            int result = dispatch(input.nextLine());
            if (result == 1 || (failedProfileCommand && !interactiveInput)) break;
        }
    }
    private int dispatch(String line) {
        int result = execute(line); if (result == 2) exitCode = 2;
        String command = line.strip().toLowerCase(Locale.ROOT);
        failedProfileCommand = result == 2 && (command.equals("profile") || command.startsWith("profile "));
        return result;
    }

    /**
     * 0: handled; 1: exit requested; 2: unavailable, unknown or failed command. One failed command never ends the
     * session: validation errors keep their localized text, and an unexpected runtime failure is reported by type (its
     * message may quote file content or provider text) with the stack trace on stderr. Drafts held by this CLI are kept.
     * Errors such as OutOfMemoryError are not caught.
     */
    public int execute(String line) {
        try {
            return CliProgress.within(progress, localization, session -> app.workflow().of(session).value().flatMap(com.photopilot.workflow.SessionWorkflow::nextStep),
                () -> route(line));
        } catch (LocalizedException expected) {
            output.println(expected.localizedMessage().render(localization));
            return 2;
        } catch (RuntimeException unexpected) {
            output.println(localization.text("cli.internalError", unexpected.getClass().getSimpleName()));
            unexpected.printStackTrace(diagnostics);
            return 2;
        }
    }

    private int route(String line) {
        String command = Objects.requireNonNull(line).strip().toLowerCase(Locale.ROOT);
        // F01-F11 run the commands the shared catalog (and the help) lists for them; the first failure decides the result.
        var alias = CliCommandCatalog.alias(command);
        if (alias.isPresent()) {
            int result = 0;
            for (String expanded : alias.get().commands()) result = Math.max(result, route(expanded));
            return result;
        }
        if (command.equals("profile") || command.startsWith("profile ")) {
            if (profiles == null) { output.println(localization.text("error.profile.unavailable")); return 2; }
            return new ProfileCommands(profiles, output, localization, this::refreshContext).execute(line);
        }
        if (command.equals("lang") || command.startsWith("lang ")) {
            String[] words = line.strip().split("\\s+", -1);
            if (words.length == 1) { output.println(localization.text("cli.language", localization.language().tag())); return 0; }
            if (words.length != 2 || !Language.supports(words[1])) { output.println(localization.text("cli.invalidLanguage")); return 2; }
            boolean saved = localization.selectLanguage(words[1]);
            output.println(localization.text(saved ? "settings.saved" : "settings.saveFailed"));
            return saved ? 0 : 2;
        }
        if (command.equals("gear") || command.startsWith("gear ")) {
            return new InventoryCommands(app, output, localization).execute(line.strip());
        }
        if (command.equals("shutter") || command.startsWith("shutter ")) {
            return new ShutterCommands(app, output, localization).execute(line.strip());
        }
        if (command.equals("import") || command.startsWith("import ")) {
            return new ImportCommands(app, privacy, output, localization, importState).execute(line.strip());
        }
        if (command.equals("session") || command.startsWith("session ")) {
            return new SessionCommands(app, output, localization).execute(line.strip());
        }
        if (command.equals("requirement") || command.startsWith("requirement ")) {
            return new RequirementCommands(app, output, localization, requirementDraft).execute(line);
        }
        if (command.equals("discover") || command.startsWith("discover ")) {
            // Only the stdin command loop of an interactive terminal may answer the wizard's questions (as for env menu).
            return new DiscoveryCommands(app, output, localization, discoveryState, requirementDraft, readingCommands && interactiveInput ? input : null,
                terminalStyle, terminalWidth > 0 ? terminalWidth : CapabilityTerminalReport.width(System.getenv())).echo(echoInput).execute(line);
        }
        if (command.equals("needs") || command.startsWith("needs ")) {
            return new NeedsCommands(app, output, localization, needsState).execute(line);
        }
        if (command.equals("capability") || command.startsWith("capability ")) {
            return new CapabilityCommands(app, output, localization).execute(line);
        }
        if (command.equals("match") || command.startsWith("match ")) {
            return new MatchCommands(app, output, localization, matchState, needsState).execute(line);
        }
        if (command.equals("env") || command.startsWith("env ")) {
            return new EnvironmentCommands(app, output, localization, environmentState, readingCommands && interactiveInput ? input : null).execute(line);
        }
        if (command.equals("plan") || command.startsWith("plan ")) {
            return new PlanCommands(app, output, localization, planState).execute(line);
        }
        if (command.equals("review") || command.startsWith("review ")) {
            return new ReviewCommands(app, output, localization, reviewState).execute(line);
        }
        if (command.equals("calc") || command.startsWith("calc ")) {
            return new CalculationCommands(app, output, localization).execute(line);
        }
        switch (command) {
            case "help" -> help();
            case "ai-test" -> { return new AiTestCommands(app, output, localization).execute(); }
            case "status" -> FeatureCatalog.lines(localization).forEach(output::println);
            case "exit", "quit" -> { importState.clear(privacy); output.println(localization.text("cli.closed")); return 1; }
            case "" -> { return 0; }
            default -> { output.println(localization.text("cli.unknown")); return 2; }
        }
        return 0;
    }

    /**
     * Short help (PP-02): the general commands, what each F01-F11 alias runs (from the same catalog route() expands),
     * one line per command group in feature order, then the invocation options and exit codes. Details stay behind
     * "<command> help".
     */
    private void help() {
        output.println(localization.text("cli.help"));
        CliCommandCatalog.help(localization).forEach(output::println);
        for (String key : java.util.List.of("session.cli.help", "discover.cli.summaryHelp", "discover.cli.summaryWizard", "requirement.cli.help", "import.cli.summaryHelp",
                "needs.cli.summaryHelp", "capability.cli.help", "match.cli.summaryHelp", "env.cli.summaryHelp", "plan.cli.summaryHelp",
                "review.cli.summaryHelp", "shutter.cli.summaryHelp")) output.println(localization.text(key));
        if (profiles != null) output.println(localization.text("profile.cli.help"));
        output.println(localization.text("cli.languageHelp"));
        output.println(localization.text("ai.cli.help"));
        output.println(localization.text("cli.options"));
        output.println(localization.text("cli.exitCodes"));
    }
}
