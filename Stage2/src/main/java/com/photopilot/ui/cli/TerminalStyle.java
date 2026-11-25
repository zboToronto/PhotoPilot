package com.photopilot.ui.cli;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Optional ANSI colour for the interactive Guided Discovery wizard; every other command and report stays plain. Colour
 * is decoration only: callers always print the bracketed ASCII mark and the status word, so plain output (the default
 * for tests, pipes, {@code NO_COLOR} and dumb terminals) carries exactly the same information. Each styled span ends
 * with a reset code, and text is styled only after it has been wrapped, so colour never changes line widths.
 */
final class TerminalStyle {
    private static final String RESET = "\u001b[0m";
    private static final String HEADING = "\u001b[1;36m", STRONG = "\u001b[1m", MUTED = "\u001b[2m",
        OK = "\u001b[32m", WARN = "\u001b[33m", BAD = "\u001b[31m";
    private static final TerminalStyle PLAIN = new TerminalStyle(false), COLOUR = new TerminalStyle(true);
    private final boolean enabled;

    private TerminalStyle(boolean enabled) { this.enabled = enabled; }
    static TerminalStyle plain() { return PLAIN; }
    static TerminalStyle forced(boolean enabled) { return enabled ? COLOUR : PLAIN; }
    /** The style for a real process: {@link #enabled(Map, boolean, boolean)} on this operating system. */
    static TerminalStyle detect(Map<String, String> environment, boolean console) {
        return forced(enabled(environment, console, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")));
    }

    /**
     * {@code PHOTOPILOT_COLOR=always|never|auto} (default auto). Auto: off when {@code NO_COLOR} is set (non-empty),
     * {@code TERM=dumb} or there is no console; on Windows only in terminals that announce ANSI support
     * ({@code WT_SESSION}, {@code TERM} or {@code ConEmuANSI=ON}).
     */
    static boolean enabled(Map<String, String> environment, boolean console, boolean windows) {
        Objects.requireNonNull(environment);
        String mode = environment.getOrDefault("PHOTOPILOT_COLOR", "auto").strip().toLowerCase(Locale.ROOT);
        if (mode.equals("always")) return true;
        if (mode.equals("never")) return false;
        if (!environment.getOrDefault("NO_COLOR", "").isEmpty()) return false;
        String term = environment.getOrDefault("TERM", "").strip();
        if (term.equalsIgnoreCase("dumb") || !console) return false;
        if (!windows) return true;
        return !environment.getOrDefault("WT_SESSION", "").isBlank() || !term.isEmpty()
            || "ON".equalsIgnoreCase(environment.getOrDefault("ConEmuANSI", "").strip());
    }

    boolean enabled() { return enabled; }
    String heading(String text) { return wrap(HEADING, text); }
    String strong(String text) { return wrap(STRONG, text); }
    String muted(String text) { return wrap(MUTED, text); }
    String ok(String text) { return wrap(OK, text); }
    String warn(String text) { return wrap(WARN, text); }
    String bad(String text) { return wrap(BAD, text); }
    private String wrap(String code, String text) { return !enabled || text.isEmpty() ? text : code + text + RESET; }
}
