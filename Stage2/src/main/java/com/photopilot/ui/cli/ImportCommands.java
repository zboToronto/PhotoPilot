package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * F04 command adapter: load -> crop/redact -> preview (sanitized copy written for review) -> approve --confirm
 * (only that copy goes to Gemini) -> review/select/set/verify -> confirm or cancel. Uses the same
 * ImagePrivacyBoundary, Facade and InventoryService as the GUI; no recognition, verification or persistence rules here.
 */
final class ImportCommands {
    /** The CLI process's open import; never persisted. Cleared on cancel, completion and user switch. */
    static final class State {
        private ImageReview review;
        private Path copy;
        private GearImportDraft draft;
        void clear(ImagePrivacyBoundary privacy) {
            if (review != null) privacy.discard(review);
            if (copy != null) privacy.discard(copy);
            review = null; copy = null; draft = null;
        }
        boolean active() { return review != null || draft != null; }
    }
    private final PhotoPilotFacade app;
    private final ImagePrivacyBoundary privacy;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;

    ImportCommands(PhotoPilotFacade app, ImagePrivacyBoundary privacy, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.privacy = privacy; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line);
            String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
            return switch (action) {
                case "help" -> { help(); yield 0; }
                case "load" -> load(words);
                case "crop", "redact" -> region(action, words);
                case "undo" -> { if (!image()) yield 2; output.println(localization.text(state.review.undo() ? "import.cli.undone" : "import.cli.noRegion")); regions(); yield 0; }
                case "clear-regions" -> { if (!image()) yield 2; state.review.clearRegions(); regions(); yield 0; }
                case "regions" -> { if (!image()) yield 2; regions(); yield 0; }
                case "preview" -> preview();
                case "approve" -> approve(words);
                case "show" -> { if (!draft()) yield 2; show(); yield 0; }
                case "select" -> select(words);
                case "set" -> set(words);
                case "verify" -> verify();
                case "confirm" -> confirm(words);
                case "cancel" -> { state.clear(privacy); output.println(localization.text("import.cancelled")); yield 0; }
                default -> { output.println(localization.text("import.cli.usage")); yield 2; }
            };
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private void help() {
        for (String key : List.of("import.cli.help1", "import.cli.help2", "import.cli.help3", "import.cli.help4", "import.cli.help5", "import.cli.help6"))
            output.println(localization.text(key));
    }
    private int load(List<String> words) {
        if (state.active()) { output.println(localization.text("import.cli.busy")); return 2; }
        if (words.size() < 3) { output.println(localization.text("import.cli.noImage")); return 2; }
        var kind = ImageReview.Kind.PHOTO;
        for (String option : words.subList(3, words.size())) {
            if (!option.startsWith("--kind=")) throw new LocalizedException("error.cli.option", option);
            kind = switch (option.substring(7).toLowerCase(Locale.ROOT)) {
                case "photo" -> ImageReview.Kind.PHOTO;
                case "label" -> ImageReview.Kind.LABEL;
                case "screenshot" -> ImageReview.Kind.SCREENSHOT;
                default -> throw new LocalizedException("error.cli.option", option);
            };
        }
        output.println(localization.text("import.loading"));
        var opened = privacy.open(Path.of(words.get(2)), kind);
        if (!report(opened)) return 2;
        state.review = opened.value().orElseThrow();
        var review = state.review;
        output.println(localization.text("import.loaded", review.fileName(), review.width(), review.height(), review.format()));
        output.println(localization.text("import.cli.coordinates", review.width(), review.height()));
        if (kind == ImageReview.Kind.SCREENSHOT) output.println(localization.text("import.screenshotWarning"));
        output.println(localization.text("import.privacyHint"));
        return 0;
    }
    private int region(String action, List<String> words) {
        if (!image()) return 2;
        if (words.size() != 6) throw new LocalizedException("error.cli.option", String.join(" ", words.subList(1, words.size())));
        int[] values = new int[4];
        try { for (int i = 0; i < 4; i++) values[i] = Integer.parseInt(words.get(i + 2)); }
        catch (NumberFormatException invalid) { throw new LocalizedException("error.cli.option", String.join(" ", words.subList(2, words.size()))); }
        boolean added = action.equals("crop") ? state.review.crop(values[0], values[1], values[2], values[3])
            : state.review.redact(values[0], values[1], values[2], values[3]);
        if (!added) { output.println(localization.text("import.cli.regionInvalid", ImageReview.MIN_REGION)); return 2; }
        discardCopy();
        regions(); return 0;
    }
    private void regions() {
        output.println(localization.text("import.regions", state.review.redactions().size(),
            state.review.cropRegion().map(r -> r.x + " " + r.y + " " + r.width + " " + r.height).orElse(localization.text("import.cropNo"))));
    }
    private int preview() {
        if (!image()) return 2;
        output.println(localization.text("import.sanitizing"));
        var copy = privacy.sanitize(state.review);
        if (!report(copy)) return 2;
        state.copy = copy.value().orElseThrow();
        try {
            var image = javax.imageio.ImageIO.read(state.copy.toFile());
            output.println(localization.text("import.sanitizedInfo", image.getWidth(), image.getHeight(), Math.max(1, java.nio.file.Files.size(state.copy) / 1024)));
        } catch (java.io.IOException | RuntimeException unreadable) { /* the path below still lets the user inspect it */ }
        output.println(localization.text("import.cli.copy", state.copy));
        output.println(localization.text("import.cli.reviewCopy"));
        return 0;
    }
    private int approve(List<String> words) {
        if (!image()) return 2;
        if (state.copy == null) { output.println(localization.text("import.cli.noCopy")); return 2; }
        boolean confirmed = words.subList(2, words.size()).equals(List.of("--confirm"));
        if (!confirmed) { output.println(localization.text("import.error.approvalRequired")); output.println(localization.text("import.cli.reviewCopy")); return 2; }
        var approved = privacy.approveRedacted(state.copy, true);
        if (!report(approved)) { state.copy = null; return 2; }
        var image = approved.value().orElseThrow();
        output.println(localization.text("import.recognizing"));
        Result<GearImportDraft> recognized;
        try { recognized = app.recognize(image); }
        finally { privacy.discard(image); state.copy = null; }
        if (!recognized.isSuccess()) {
            output.println(localization.text("import.aiFailed", String.join(" ", recognized.errors(localization))));
            return 2;
        }
        // The decoded original is no longer needed once recognition succeeded.
        privacy.discard(state.review); state.review = null;
        state.draft = recognized.value().orElseThrow();
        output.println(localization.text("import.released"));
        show(); return 0;
    }
    private int select(List<String> words) {
        if (!draft()) return 2;
        int count = state.draft.recognizedCandidates().size();
        int index;
        try { index = words.size() == 3 ? Integer.parseInt(words.get(2)) - 1 : -1; }
        catch (NumberFormatException invalid) { index = -1; }
        if (index < 0 || index >= count) { output.println(localization.text("import.cli.candidateRange", count)); return 2; }
        state.draft = state.draft.select(index);
        return verify();
    }
    private int set(List<String> words) {
        if (!draft()) return 2;
        var edits = new LinkedHashMap<String, String>();
        for (String token : words.subList(2, words.size())) {
            int split = token.indexOf('=');
            if (split <= 0) throw new LocalizedException("error.cli.option", token);
            if (edits.putIfAbsent(token.substring(0, split), token.substring(split + 1)) != null)
                throw new LocalizedException("error.cli.repeated", token.substring(0, split));
        }
        var edited = state.draft.withEdits(edits);
        if (!report(edited)) return 2;
        state.draft = edited.value().orElseThrow();
        show(); return 0;
    }
    private int verify() {
        if (!draft()) return 2;
        output.println(localization.text("import.verifying"));
        var verified = app.inventory().verify(state.draft);
        if (!report(verified)) return 2;
        state.draft = verified.value().orElseThrow();
        show(); return 0;
    }
    private int confirm(List<String> words) {
        if (!draft()) return 2;
        boolean accept = false, separate = false; Optional<Equipment> increase = Optional.empty();
        var duplicates = duplicates();
        if (duplicates == null) return 2;
        for (String option : words.subList(2, words.size())) {
            if (option.equals("--accept-unverified")) accept = true;
            else if (option.equals("--add-separate")) separate = true;
            else if (option.startsWith("--increase=")) {
                UUID id = UUID.fromString(option.substring(11));
                increase = duplicates.stream().filter(item -> item.id().equals(id)).findFirst();
                if (increase.isEmpty()) { report(Result.failureKey("import.error.notDuplicate")); return 2; }
            } else throw new LocalizedException("error.cli.option", option);
        }
        if (separate && increase.isPresent()) throw new LocalizedException("error.cli.option", "--add-separate --increase");
        var problems = state.draft.problems();
        if (!problems.isEmpty()) { output.println(GearImportPresentation.problems(state.draft, localization)); return 2; }
        output.println(localization.text("import.saving"));
        var saved = app.inventory().confirm(state.draft, new GearImportDraft.Decision(accept, separate, increase));
        if (!report(saved)) {
            if (!duplicates.isEmpty() && increase.isEmpty() && !separate) {
                output.println(localization.text("import.duplicatesTitle"));
                duplicates.forEach(item -> output.println("  " + GearImportPresentation.duplicate(item, localization)));
                output.println(localization.text("import.cli.duplicatesHint"));
            }
            return 2;
        }
        var item = saved.value().orElseThrow();
        output.println(increase.isPresent() ? localization.text("import.savedQuantity", item.brand(), item.model(), item.quantity(), item.id())
            : localization.text("import.saved", item.brand(), item.model(), item.quantity(), item.id()));
        output.println("  " + EquipmentForm.values(item));
        output.println(localization.text("cli.inventory.verified", item.verifiedFields()));
        state.clear(privacy);
        return 0;
    }

    private List<Equipment> duplicates() {
        var found = app.inventory().importDuplicates(state.draft.type(), state.draft.value("brand"), state.draft.value("model"));
        return report(found) ? found.value().orElseThrow() : null;
    }
    private void show() {
        output.println(localization.text("import.cli.draft", state.draft.itemId()));
        GearImportPresentation.describe(state.draft, localization).forEach(output::println);
        var unverified = GearImportPresentation.unverified(state.draft, localization);
        if (!unverified.isEmpty()) output.println(localization.text("import.acceptUnverified", unverified));
        var duplicates = duplicates();
        if (duplicates != null && !duplicates.isEmpty()) {
            output.println(localization.text("import.duplicatesTitle"));
            duplicates.forEach(item -> output.println("  " + GearImportPresentation.duplicate(item, localization)));
            output.println(localization.text("import.cli.duplicatesHint"));
        }
        output.println(localization.text("import.cli.next"));
    }
    private void discardCopy() { if (state.copy != null) { privacy.discard(state.copy); state.copy = null; } }
    private boolean image() {
        if (state.review != null) return true;
        output.println(localization.text(state.draft != null ? "import.cli.busy" : "import.cli.noImage")); return false;
    }
    private boolean draft() {
        if (state.draft != null) return true;
        output.println(localization.text("import.error.noDraft")); return false;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(error -> output.println(localization.text("cli.error", error))); return false;
    }
}
