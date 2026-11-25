package com.photopilot.ui.gui;

import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.progress.OperationContext;
import com.photopilot.progress.OperationStage;
import com.photopilot.progress.Progress;
import java.util.*;
import java.util.concurrent.Callable;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 D02 / G18: the F04 recognition and verification progress never shows a raw template ("Checking {0}") at
 * any point of its lifecycle: queued, active per source, completed, failed or cancelled, in English and Chinese. The
 * catalogue check covers every planned stage of every operation, so a new parameterized stage cannot slip through.
 */
class ProgressPlaceholderGuiTest {
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    @BeforeAll static void toolkit() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }

    /** Every visible text of the view, in layout order. */
    static List<String> visible(Node node) {
        var texts = new ArrayList<String>();
        if (!node.isVisible() || !node.isManaged()) return texts;
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) texts.add(labeled.getText());
        if (node instanceof Parent parent) for (var child : parent.getChildrenUnmodifiable()) texts.addAll(visible(child));
        return texts;
    }
    static void noTemplate(List<String> texts, String when) {
        for (String text : texts) assertFalse(text.matches("(?s).*\\{\\d+}.*"), when + ": unresolved placeholder in \"" + text + "\" of " + texts);
    }

    @Test void everyPlannedStageHasAQueuedLabelWithoutPlaceholdersInBothLanguages() {
        for (String language : List.of("en", "zh-CN")) {
            var localization = LocalizationService.english(); localization.useLanguage(language);
            for (var kind : OperationKind.values()) for (String key : kind.stages()) {
                String queued = OperationStage.queued(key).render(localization);
                assertFalse(queued.contains("{"), kind + " " + key + " (" + language + "): " + queued);
                assertFalse(queued.startsWith("progress."), "a missing bundle key shows the key: " + queued);
                // A planned stage whose active label needs an argument must be declared, so it gets its own queued label.
                boolean template = localization.text("progress.stage." + key).matches("(?s).*\\{\\d+}.*");
                assertEquals(template, OperationStage.PARAMETERIZED.contains(key), kind + " " + key);
            }
        }
        var en = LocalizationService.english();
        assertEquals("Checking Wikipedia", new LocalizedLabel("import.source", "Wikipedia").render(en));
        assertEquals("Verifying equipment specifications", OperationStage.queued("import.source").render(en));
        assertEquals("Retrieving sun times (2026-11-27)", new LocalizedLabel("env.sun", "2026-11-27").render(en));
        assertEquals("Ranking 3 candidates", new LocalizedLabel("match.rank", 3).render(en));
    }
    /** The active label as the running code publishes it. */
    record LocalizedLabel(String key, Object argument) {
        String render(LocalizationService localization) {
            return new OperationStage(key, List.of(argument), com.photopilot.progress.OperationStatus.RUNNING, OperationStage.State.ACTIVE, java.time.Duration.ZERO)
                .label().render(localization);
        }
    }

    @Test void importProgressNamesEachSourceAndNeverShowsATemplate() throws Exception {
        for (String language : List.of("en", "zh-CN")) {
            var localization = LocalizationService.english(); localization.useLanguage(language);
            boolean zh = language.startsWith("zh");
            fx(() -> {
                var view = new OperationProgressView(localization);
                // Queued, then each stage of a recognition with source verification, then completed.
                var operation = start(view, OperationKind.IMPORT_RECOGNIZE);
                noTemplate(visible(view), "queued");
                assertTrue(visible(view).contains(zh ? "核验器材规格" : "Verifying equipment specifications"), visible(view)::toString);
                Result<Integer> done = operation.run(() -> {
                    Progress.stage("import.approved"); Progress.waiting("ai.wait"); Progress.validating("ai.check"); Progress.stage("import.candidates");
                    noTemplate(visible(view), "recognized");
                    assertTrue(visible(view).stream().anyMatch(t -> t.contains(zh ? "核验器材规格" : "Verifying equipment specifications")), "the next stage line");
                    Progress.waiting("import.source", "Wikipedia");
                    assertTrue(visible(view).contains(zh ? "查询 Wikipedia" : "Checking Wikipedia"), visible(view)::toString);
                    Progress.waiting("import.source", "Lensfun");
                    assertTrue(visible(view).contains(zh ? "查询 Lensfun" : "Checking Lensfun"), visible(view)::toString);
                    noTemplate(visible(view), "verifying");
                    Progress.stage("import.evidence");
                    return Result.success(1);
                });
                operation.finish(done, Optional.empty());
                var completed = visible(view);
                noTemplate(completed, "completed");
                int wikipedia = completed.indexOf(zh ? "查询 Wikipedia" : "Checking Wikipedia"), lensfun = completed.indexOf(zh ? "查询 Lensfun" : "Checking Lensfun");
                assertTrue(wikipedia >= 0 && lensfun > wikipedia, "provenance kept in order: " + completed);
                // Verification alone (IMPORT_VERIFY) fails while the sources are still queued.
                var failing = start(view, OperationKind.IMPORT_VERIFY);
                noTemplate(visible(view), "verify queued");
                failing.finish(failing.run(() -> Result.failureKey("ai.error.serviceUnavailable", "503")), Optional.empty());
                noTemplate(visible(view), "failed");
                // Cancelled while queued.
                var cancelled = start(view, OperationKind.IMPORT_VERIFY);
                assertTrue(cancelled.cancel());
                cancelled.finish(Result.failureKey("progress.cancelled"), Optional.empty());
                noTemplate(visible(view), "cancelled");
                view.dispose();
                return true;
            });
        }
    }
    static OperationContext start(OperationProgressView view, OperationKind kind) {
        var operation = kind.start();
        operation.listen(view.listener(operation));
        view.track(kind, operation);
        operation.acknowledge();
        return operation;
    }
}
