package com.photopilot.ui.cli;

import java.util.Map;

/**
 * Test access to the CLI's package-private terminal settings for tests in other packages (the F02 wizard tests live
 * with the other discovery tests). Production code sets these only in {@link PhotoPilotCLI#main}.
 */
public final class CliTerminalAccess {
    private CliTerminalAccess() { }
    /** Wizard width and colour, and whether answers are repeated after their prompts (transcripts). */
    public static void configure(PhotoPilotCLI cli, int width, boolean colour, boolean echo) {
        cli.terminalWidth(width); cli.terminalStyle(TerminalStyle.forced(colour)); cli.echoInput(echo);
    }
    /** The colour decision {@code main} makes from the environment, the console and the operating system. */
    public static boolean colour(Map<String, String> environment, boolean console, boolean windows) {
        return TerminalStyle.enabled(environment, console, windows);
    }
    /** Terminal columns of a line (East Asian wide characters count as two), as the wizard measures them. */
    public static int columns(String line) { return TextTable.width(line); }
}
