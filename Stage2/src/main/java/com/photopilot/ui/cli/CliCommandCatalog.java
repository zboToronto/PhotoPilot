package com.photopilot.ui.cli;

import com.photopilot.i18n.LocalizationService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The CLI's F01-F11 feature aliases in one place (Stage2OptRe1 PP-02). {@link PhotoPilotCLI} expands an alias through
 * {@link #alias} before routing and its help prints {@link #help}, so the help cannot describe an alias differently from
 * what it runs. Each alias runs real commands of its feature: a list, an overview or the feature's help.
 */
final class CliCommandCatalog {
    private CliCommandCatalog() { }

    /** A feature alias and the command lines it runs, in order. */
    record Alias(String feature, List<String> commands) {
        Alias { commands = List.copyOf(commands); }
    }

    static final List<Alias> ALIASES = List.of(
        new Alias("F01", List.of("session list")),
        new Alias("F02", List.of("discover help")),
        new Alias("F03", List.of("requirement help")),
        new Alias("F04", List.of("import help")),
        new Alias("F05", List.of("gear list")),
        new Alias("F06", List.of("needs help", "capability")),
        new Alias("F07", List.of("match help")),
        new Alias("F08", List.of("calc help")),
        new Alias("F09", List.of("env help")),
        new Alias("F10", List.of("plan help")),
        new Alias("F11", List.of("review help")));

    /** The alias a whole command line names ("F01", "f01"), if any; arguments after an alias are not an alias. */
    static Optional<Alias> alias(String line) {
        String command = line == null ? "" : line.strip().toUpperCase(Locale.ROOT);
        return ALIASES.stream().filter(alias -> alias.feature().equals(command)).findFirst();
    }

    /** Help lines: a title, then "F01 runs session list (Photography Session and History Management)" per alias. */
    static List<String> help(LocalizationService localization) {
        var lines = new java.util.ArrayList<String>();
        lines.add(localization.text("cli.aliases.title"));
        // Indented in code: properties files drop leading spaces of a value.
        for (var alias : ALIASES) lines.add("  " + localization.text("cli.alias", alias.feature(), String.join(" + ", alias.commands()),
            localization.text("catalog." + alias.feature())));
        return lines;
    }
}
