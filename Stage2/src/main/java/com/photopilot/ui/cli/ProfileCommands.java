package com.photopilot.ui.cli;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.LocalProfile;
import java.io.PrintStream;
import java.util.*;

/** Command presentation only; identity, storage and lifecycle remain in the shared manager. */
final class ProfileCommands {
    private final ProfileManager profiles;
    private final PrintStream output;
    private final LocalizationService language;
    private final Runnable contextChanged;
    ProfileCommands(ProfileManager profiles, PrintStream output, LocalizationService language, Runnable contextChanged) {
        this.profiles = profiles; this.output = output; this.language = language; this.contextChanged = contextChanged;
    }
    static UUID id(String text) {
        try { UUID id = UUID.fromString(text); if (id.toString().equalsIgnoreCase(text)) return id; }
        catch (IllegalArgumentException ignored) { }
        throw new LocalizedException("error.profile.id");
    }
    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line);
            String action = words.size() == 1 ? "list" : words.get(1).toLowerCase(Locale.ROOT);
            if (Set.of("help", "list", "current").contains(action)) {
                if (words.size() > 2) throw new LocalizedException("error.profile.command");
                if (action.equals("help")) output.println(language.text("profile.cli.help"));
                else if (action.equals("current")) { var result = profiles.activeProfile(); if (!report(result)) return 2; show(result.value().orElseThrow(), true); }
                else {
                    var result = profiles.listProfiles(); if (!report(result)) return 2;
                    UUID current = profiles.activeContext().storageScope().profileId().orElseThrow();
                    output.println(language.text("profile.count", result.value().orElseThrow().size()));
                    result.value().orElseThrow().forEach(profile -> show(profile, profile.id().equals(current)));
                }
                return 0;
            }
            if (action.equals("create") && words.size() == 3) {
                var result = profiles.createProfile(words.get(2)); if (!report(result)) return 2;
                output.println(language.text("profile.created")); show(result.value().orElseThrow(), false); return 0;
            }
            if (action.equals("rename") && words.size() == 4) {
                var result = profiles.renameProfile(id(words.get(2)), words.get(3)); if (!report(result)) return 2;
                output.println(language.text("profile.renamed")); show(result.value().orElseThrow(), result.value().orElseThrow().id().equals(profiles.activeContext().storageScope().profileId().orElseThrow())); return 0;
            }
            if (action.equals("select") && words.size() == 3) {
                var result = profiles.selectProfile(id(words.get(2)), true); if (!report(result)) return 2;
                contextChanged.run(); output.println(language.text("profile.switched")); show(result.value().orElseThrow(), true); return 0;
            }
            if (action.equals("delete") && (words.size() == 3 || words.size() == 4)) {
                UUID target = id(words.get(2));
                if (words.size() == 3) { output.println(language.text("profile.cli.confirm")); return 2; }
                if (!words.get(3).equals("--confirm")) throw new LocalizedException("error.profile.command");
                if (!report(profiles.deleteProfile(target, true))) return 2;
                output.println(language.text("profile.deleted")); return 0;
            }
            throw new LocalizedException("error.profile.command");
        } catch (LocalizedException error) {
            output.println(error.localizedMessage().render(language)); return 2;
        }
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(language).forEach(output::println); return false;
    }
    private void show(LocalProfile profile, boolean active) {
        output.println(language.text(active ? "profile.currentLine" : "profile.listLine", profile.displayName(), profile.id()));
    }
}
