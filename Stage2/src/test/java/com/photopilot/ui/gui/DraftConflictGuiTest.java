package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JavaFX F03/F06/F07 drafts while the same session is saved elsewhere (here through the facade, as another pane,
 * window or the CLI would): showing the page again must not move the open draft onto the newer version, so the first
 * Confirm is rejected (error.session.changed) instead of silently overwriting what was saved meanwhile. The pane then
 * adopts the stored session and re-evaluates the replace notice, so a second Confirm replaces knowingly. A session
 * that did not change still saves at the first Confirm, also after the page was shown again.
 */
class DraftConflictGuiTest {
    static final String CHANGED = "changed in another view or process";
    @TempDir Path root;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    PhotoPilotWorkspace workspace; Stage stage; Parent pane; Callable<Boolean> busy; Callable<String> feedback;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("GUI operation timed out"); } while (fx(busy));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextInputControl) pane.lookup("#" + id)).setText(value)); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    String feedback() throws Exception { return fx(feedback); }
    String why() { try { return "feedback: " + feedback(); } catch (Exception error) { return error.toString(); } }
    static boolean shown(Node node) { for (; node != null; node = node.getParent()) if (!node.isVisible()) return false; return true; }
    boolean shown(String id) throws Exception { return fx(() -> { var node = pane.lookup("#" + id); return node != null && shown(node); }); }
    boolean shownText(String text) throws Exception {
        return fx(() -> pane.lookupAll(".label").stream().anyMatch(node -> node instanceof Label label && label.getText().equals(text) && shown(label)));
    }
    void nav(String feature) { ((ToggleButton) workspace.lookup("#nav-" + feature)).fire(); }
    /** Leaves the page and shows it again, so its activate-on-show refresh runs. */
    void reshow(String feature) throws Exception { action(() -> nav("F08")); action(() -> nav(feature)); ready(); }
    void launch(ApplicationBootstrap app, Function<PhotoPilotWorkspace, Parent> connect, String feature) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            workspace = new PhotoPilotWorkspace(app.localization()); pane = connect.apply(workspace);
            stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            nav(feature); workspace.applyCss(); workspace.layout();
        }); ready();
    }
    void close() throws Exception { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }

    @Test void requirementDraftIsNotSilentlyMovedOntoARequirementSavedElsewhere() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = ok(app.facade().sessions().create("Night", LocalDate.of(2026, 11, 20))).id();
            RequirementPane[] requirements = new RequirementPane[1];
            busy = () -> requirements[0].busy(); feedback = () -> requirements[0].feedbackText();
            launch(app, view -> { requirements[0] = new RequirementPane(app.facade(), executor, app.localization()); view.connectRequirements(requirements[0]); return requirements[0]; }, "F03");
            String notice = app.localization().text("requirement.replaceNotice");
            text("requirement-request", "Milky Way over the lake"); click("requirement-manual");
            text("requirement-field-subject", "Milky Way"); text("requirement-field-duration", "2h");
            assertFalse(shownText(notice), "nothing to replace when the draft was opened");

            gemini.reply(FakeGemini.AIRSHOW);
            ok(app.facade().planning().confirm(id, ok(app.facade().parse(id, FakeGemini.AIRSHOW_REQUEST))));
            reshow("F03");
            click("requirement-confirm");
            assertEquals("Aircraft at an airshow", ok(app.facade().planning().requirement(id)).subject(), "the requirement saved elsewhere is kept");
            assertTrue(feedback().contains(CHANGED), this::why);
            assertNotNull(fx(() -> requirements[0].draft()), "the draft is retained");
            assertEquals("Milky Way", fx(() -> ((TextField) pane.lookup("#requirement-field-subject")).getText()));
            assertTrue(shownText(notice), "the replace notice now reflects the stored session");
            click("requirement-confirm");
            assertEquals("Milky Way", ok(app.facade().planning().requirement(id)).subject(), "second Confirm replaces knowingly");
            assertTrue(feedback().contains("confirmed and saved"), this::why);

            // Nothing changed meanwhile: the first Confirm saves, also after the page was shown again.
            click("requirement-edit"); text("requirement-field-subject", "Milky Way core");
            reshow("F03");
            click("requirement-confirm");
            assertNull(fx(() -> requirements[0].draft()), this::why);
            assertEquals("Milky Way core", ok(app.facade().planning().requirement(id)).subject());
        } finally { close(); }
    }

    @Test void capabilityDraftIsNotSilentlyMovedOntoAProfileSavedElsewhere() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = confirmedSession(app, gemini);
            CapabilityPane[] capability = new CapabilityPane[1];
            busy = () -> capability[0].busy(); feedback = () -> capability[0].profile().feedbackText();
            launch(app, view -> { capability[0] = new CapabilityPane(app.facade(), executor, app.localization()); view.connectCapabilities(capability[0]); return capability[0]; }, "F06");
            gemini.reply(PROPOSAL);
            click("needs-analyze");
            assertNotNull(fx(() -> capability[0].profile().proposal()), this::why);
            assertFalse(shown("needs-replace-notice"), "nothing to replace when the draft was opened");

            gemini.reply(reply("[" + need("focalLength", 200, 600, 1, true, "Saved elsewhere.") + "]"));
            ok(app.facade().planning().confirmProfile(id, ok(app.facade().proposeNeeds(id)).profile()));
            reshow("F06");
            click("needs-confirm");
            assertEquals(Optional.of(200.0), ok(app.facade().planning().profile(id)).need("focalLength").orElseThrow().minimum(), "the profile saved elsewhere is kept");
            assertTrue(feedback().contains(CHANGED), this::why);
            assertNotNull(fx(() -> capability[0].profile().proposal()), "the draft is retained");
            assertTrue(shown("needs-replace-notice"), "the replace notice now reflects the stored session");
            assertEquals("Replace saved profile", fx(() -> ((Button) pane.lookup("#needs-confirm")).getText()));
            click("needs-confirm");
            var replaced = ok(app.facade().planning().profile(id));
            assertEquals(Optional.of(300.0), replaced.need("focalLength").orElseThrow().minimum(), "second Confirm replaces knowingly");
            assertEquals(10, replaced.needs().size());

            // Nothing changed meanwhile: the first Confirm saves, also after the page was shown again.
            click("needs-manual");
            assertFalse(shown("needs-replace-notice"), "editing the saved profile is the explicit change");
            click("needs-need-remove-lensCount");
            reshow("F06");
            click("needs-confirm");
            assertNull(fx(() -> capability[0].profile().proposal()), this::why);
            assertTrue(ok(app.facade().planning().profile(id)).need("lensCount").isEmpty());
        } finally { close(); }
    }

    @SuppressWarnings("unchecked")
    @Test void matchProfileEditIsNotSilentlyMovedOntoAProfileSavedElsewhere() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = ok(app.facade().sessions().create("Lakeshore airshow", LocalDate.of(2026, 11, 14))).id();
            ok(app.facade().planning().confirm(id, GearFixtures.airshowRequirement().asDraft()));
            GearMatchPane[] matching = new GearMatchPane[1];
            busy = () -> matching[0].busy(); feedback = () -> matching[0].feedbackText();
            launch(app, view -> { matching[0] = new GearMatchPane(app.facade(), executor, app.localization()); view.connectMatching(matching[0]); return matching[0]; }, "F07");
            click("match-profile-edit");
            action(() -> ((ComboBox<String>) pane.lookup("#match-need-capability")).setValue("focalLength"));
            text("match-need-minimum", "100"); text("match-need-maximum", "600"); text("match-need-priority", "1");
            click("match-need-add");

            ok(app.facade().planning().confirmProfile(id, new EquipmentRequirementProfile(List.of(
                CapabilityNeed.of("focalLength", Optional.of(200.0), Optional.of(600.0), 1, true, "Saved elsewhere.")), false)));
            reshow("F07");
            click("match-profile-confirm");
            assertEquals(Optional.of(200.0), ok(app.facade().planning().profile(id)).need("focalLength").orElseThrow().minimum(), "the profile saved elsewhere is kept");
            assertTrue(feedback().contains(CHANGED), this::why);
            assertTrue(fx(() -> matching[0].dirty()), "the edit is retained");
            assertTrue(label("match-profile").contains("200-600 mm"), "the stored profile it would replace is shown");
            click("match-profile-confirm");
            assertEquals(Optional.of(100.0), ok(app.facade().planning().profile(id)).need("focalLength").orElseThrow().minimum(), "second Confirm replaces knowingly");

            // Nothing changed meanwhile: the first Confirm saves, also after the page was shown again.
            click("match-profile-edit");
            assertTrue(fx(() -> matching[0].dirty()));
            reshow("F07");
            click("match-profile-confirm");
            assertFalse(fx(() -> matching[0].dirty()), this::why);
            assertTrue(feedback().contains("Capability profile confirmed and saved"), this::why);
        } finally { close(); }
    }
}
