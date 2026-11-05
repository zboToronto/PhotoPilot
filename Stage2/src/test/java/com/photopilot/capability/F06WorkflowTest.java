package com.photopilot.capability;

import com.photopilot.application.CapabilityNeedForm;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.CapabilityProposal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F06/UC06 through the real Facade -> PlanningService -> RequirementService -> agent -> GeminiAdapter chain against a
 * loopback Gemini stand-in, with real F01/F03/F05 JSON persistence and F07 consumption.
 */
class F06WorkflowTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    static Set<String> keys(List<LocalizedMessage> messages) { return messages.stream().map(LocalizedMessage::key).collect(java.util.stream.Collectors.toSet()); }

    @Test void acceptanceScenarioAnalyzesReviewsConfirmsPersistsAndFeedsF07() throws Exception {
        var gear = new GearFixtures.Airshow();
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = confirmedSession(app, gemini);
        var requirement = ok(app.facade().planning().requirement(id));
        assertEquals(Optional.of(2), requirement.cameraCount()); assertEquals(Optional.of(false), requirement.lensChangesAllowed());
        byte[] before = Files.readAllBytes(store());

        gemini.reply(PROPOSAL);
        var proposal = ok(app.facade().proposeNeeds(id));
        var draft = proposal.profile();
        assertFalse(draft.reviewed(), "AI output is a proposal, never a confirmed profile");
        assertEquals(EquipmentRequirementProfile.AI_ASSISTED, draft.origin());
        assertEquals(10, draft.needs().size());
        assertEquals(Optional.of(300.0), draft.need("focalLength").orElseThrow().minimum());
        var bodies = draft.need("cameraCount").orElseThrow();
        assertEquals(Optional.of(2.0), bodies.minimum()); assertEquals(Optional.of(2.0), bodies.maximum());
        assertEquals(Optional.of(2.0), draft.need("lensCount").orElseThrow().maximum(), "lens-change restriction preserved");
        assertTrue(proposal.adjustments().isEmpty(), () -> "a consistent proposal needs no corrections: " + proposal.adjustments());
        assertEquals(2, draft.tradeOffs().size()); assertEquals(1, proposal.unsupportedNeeds().size()); assertEquals(1, proposal.clarificationQuestions().size());
        assertTrue(draft.validate(requirement).isSuccess());
        for (var need : draft.needs()) assertFalse(need.rationale().matches("(?i).*(sony|canon|nikon).*"), "capabilities only");
        assertArrayEquals(before, Files.readAllBytes(store()), "analysis never saves");
        assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"));
        assertTrue(app.facade().planning().matchGear(id, "portability").hasError("needs.error.none"), "unconfirmed drafts never reach F07");

        var body = gemini.requests.getLast();
        var evidence = PlanFixtures.evidence(body);
        assertEquals(2, evidence.getAsJsonObject("confirmedRequirement").get("cameraCount").getAsInt());
        assertFalse(evidence.getAsJsonObject("confirmedRequirement").get("lensChangesAllowed").getAsBoolean());
        assertEquals(CapabilityNeed.SUPPORTED.size(), evidence.getAsJsonArray("supportedCapabilities").size());
        assertFalse(evidence.has("environment"), "no F09 assessment was made in this run");
        assertTrue(CapabilityNeedForm.review(proposal, com.photopilot.i18n.LocalizationService.english()).stream()
            .anyMatch(line -> line.contains("kept only while PhotoPilot is open")), "AUD-005: the missing F09 context is stated, not hidden");
        assertTrue(body.contains("responseJsonSchema") && body.contains("\"enum\"") && body.contains("Never name a brand"));
        assertFalse(body.contains(FakeGemini.KEY)); assertFalse(body.contains(id.toString()));
        for (var item : gear.items()) {
            assertFalse(body.contains(item.id().toString()), "inventory stays local");
            if (item.model().length() > 4) assertFalse(body.contains(item.model()), "F06 never sends owned equipment");
        }

        // Review: the user changes a priority and removes a need; the shared form keeps provenance and explanations.
        var weight = CapabilityNeed.of("totalWeight", Optional.empty(), Optional.of(5500.0), 2, true, "Keep it light.");
        var edited = CapabilityNeedForm.remove(CapabilityNeedForm.put(draft, weight), "storageCapacity");
        assertEquals(EquipmentRequirementProfile.AI_ASSISTED, edited.origin()); assertEquals(2, edited.tradeOffs().size());
        var saved = ok(app.facade().planning().confirmProfile(id, edited));
        assertTrue(saved.reviewed()); assertEquals(9, saved.needs().size());
        assertEquals(Optional.of(5500.0), saved.need("totalWeight").orElseThrow().maximum());

        // A restart restores exactly the confirmed profile from storage.
        app.close(); app = boot();
        var restored = ok(app.facade().planning().profile(id));
        assertTrue(restored.reviewed()); assertEquals(EquipmentRequirementProfile.AI_ASSISTED, restored.origin());
        assertEquals(saved.needs().size(), restored.needs().size()); assertEquals(saved.tradeOffs(), restored.tradeOffs());
        assertTrue(restored.need("storageCapacity").isEmpty());

        // F07 consumes the confirmed profile: every need is evaluated for every recommendation.
        gemini.status(503, "");
        var report = ok(app.facade().planning().matchGear(id, "coverage"));
        assertFalse(report.recommendations().isEmpty());
        var best = report.recommendations().getFirst();
        assertEquals(restored.needs().stream().map(CapabilityNeed::capability).toList(), best.needs().stream().map(n -> n.need().capability()).toList());
        ok(app.facade().planning().selectLoadout(id, best.loadout()));
    }

    @Test void untrustedOutputIsCheckedConstraintsArePreservedAndProductNamesRemoved() {
        var id = confirmedSession(app, gemini);
        String needs = "[" + String.join(",",
            need("focalLength", 300, 500, 1, true, "Use a Sony FE 200-600mm G lens."),
            need("cameraCount", 1, 1, 2, true, "One body is enough."),
            need("warpDrive", 1, null, 3, true, ""),
            need("burstRate", null, 20, 2, true, "Fast."),
            need("autofocus", null, null, 2, true, "Tracking."),
            need("autofocus", null, null, 5, false, "Tracking."),
            need("lensCount", 3, 4, 3, false, "Several lenses."),
            need("focalLength", -50, null, 1, true, "duplicate with an invalid value")) + "]";
        gemini.reply("{\"needs\":" + needs + ",\"tradeOffs\":[\"Bring the Canon EOS R5 for reach.\",\"Reach versus weight.\"],"
            + "\"unsupportedNeeds\":[],\"clarificationQuestions\":[]}");
        var proposal = ok(app.facade().proposeNeeds(id));
        var draft = proposal.profile();
        var found = keys(proposal.adjustments());
        assertEquals("", draft.need("focalLength").orElseThrow().rationale(), "product text removed, capability kept");
        assertTrue(found.contains("needs.ai.productRemoved"));
        assertEquals(Optional.of(2.0), draft.need("cameraCount").orElseThrow().minimum(), "confirmed two-body requirement wins");
        assertTrue(found.contains("needs.ai.preserved.cameraCount"));
        assertTrue(found.contains("needs.ai.unsupported"));
        assertTrue(draft.need("burstRate").isEmpty()); assertTrue(found.contains("needs.ai.dropped") && found.contains("needs.error.bound"));
        assertTrue(draft.need("autofocus").isEmpty(), "contradictory priorities for one capability are not guessed");
        assertTrue(found.contains("needs.ai.conflict"));
        assertTrue(found.contains("needs.error.value"), "invalid numerical value reported");
        var lenses = draft.need("lensCount").orElseThrow();
        assertEquals(Optional.of(2.0), lenses.maximum()); assertTrue(lenses.minimum().isEmpty()); assertTrue(found.contains("needs.ai.preserved.lensChanges"));
        assertEquals(List.of("Reach versus weight."), draft.tradeOffs());
        assertTrue(draft.validate(ok(app.facade().planning().requirement(id))).isSuccess(), "corrected draft is consistent");
    }

    @Test void geminiFailureMalformedOrEmptyOutputChangesNothingAndManualEntryRemains() throws Exception {
        var id = confirmedSession(app, gemini);
        byte[] before = Files.readAllBytes(store());
        gemini.status(500, "{\"error\":{\"message\":\"boom\"}}");
        assertFalse(app.facade().proposeNeeds(id).isSuccess(), "provider failure is reported");
        gemini.reply("this is not JSON");
        assertTrue(app.facade().analyzeNeeds(id).hasError("ai.error.malformedOutput"));
        gemini.reply("{\"needs\":[{\"capability\":\"focalLength\"}]}");
        assertFalse(app.facade().proposeNeeds(id).isSuccess(), "missing required sections");
        gemini.reply(reply("[" + need("warpDrive", 1, null, 1, true, "") + "]"));
        assertTrue(app.facade().proposeNeeds(id).hasError("needs.ai.noUsable"), "nothing usable is never presented as AI success");
        assertArrayEquals(before, Files.readAllBytes(store()));

        int sent = gemini.requests.size();
        var manual = ok(app.facade().planning().manualNeeds(id));
        assertEquals(sent, gemini.requests.size(), "manual entry never calls Gemini");
        assertEquals(EquipmentRequirementProfile.MANUAL, manual.profile().origin()); assertTrue(manual.profile().needs().isEmpty());
        assertTrue(app.facade().planning().confirmProfile(id, manual.profile()).hasError("needs.error.empty"));
        var entered = CapabilityNeedForm.put(manual.profile(), ok(CapabilityNeedForm.parse(Map.of("capability", "focalLength", "minimum", "300", "maximum", "500"))));
        var saved = ok(app.facade().planning().confirmProfile(id, entered));
        assertEquals(EquipmentRequirementProfile.MANUAL, saved.origin());
    }

    @Test void missingRequirementMissingOptionalValuesAndInvalidManualValues() {
        var bare = ok(app.facade().sessions().create("No requirement", LocalDate.of(2026, 11, 20))).id();
        assertTrue(app.facade().proposeNeeds(bare).hasError("needs.error.requirement"));
        assertTrue(app.facade().planning().manualNeeds(bare).hasError("needs.error.requirement"));
        assertTrue(gemini.requests.isEmpty(), "nothing is sent without a confirmed requirement");
        assertTrue(app.facade().planning().analyzeNeeds(null).hasError("error.session.id"));

        var id = ok(app.facade().sessions().create("Street", LocalDate.of(2026, 11, 21))).id();
        ok(app.facade().planning().confirm(id, new ShootRequirement("Street photography downtown", "Street photography", List.of(), Optional.empty(),
            Optional.empty(), "", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), false)));
        gemini.reply(reply("[" + need("focalLength", 24, 50, 1, true, "Wide to normal views.") + "]"));
        var proposal = ok(app.facade().proposeNeeds(id));
        assertEquals(List.of("duration", "cameraCount", "lensChanges"), proposal.missingFields(), "unspecified sizing inputs are reported, never filled in");
        assertTrue(proposal.adjustments().isEmpty(), "no constraint was stated, so none is added");
        assertEquals(1, proposal.profile().needs().size());

        assertTrue(CapabilityNeedForm.parse(Map.of("capability", "focalLength", "minimum", "abc")).hasError("needs.error.number"));
        assertTrue(CapabilityNeedForm.parse(Map.of("capability", "focalLength", "minimum", "500", "maximum", "300")).hasError("needs.error.range"));
        assertTrue(CapabilityNeedForm.parse(Map.of("capability", "cameraCount", "minimum", "1.5")).hasError("needs.error.integer"));
        assertTrue(CapabilityNeedForm.parse(Map.of("capability", "burstRate", "minimum", "10", "priority", "11")).hasError("needs.error.priority"));
    }

    @Test void contradictoryNeedsAreRejectedBeforeAnyWrite() throws Exception {
        var id = confirmedSession(app, gemini);
        byte[] before = Files.readAllBytes(store());
        var tooFewLenses = new EquipmentRequirementProfile(List.of(GearFixtures.need("cameraCount", 2.0, 2.0, 1, true),
            GearFixtures.need("lensCount", null, 1.0, 2, true)), false);
        assertTrue(app.facade().planning().confirmProfile(id, tooFewLenses).hasError("needs.error.lensesForBodies"));
        var lensChanges = new EquipmentRequirementProfile(List.of(GearFixtures.need("lensCount", 3.0, null, 2, true)), false);
        assertTrue(app.facade().planning().confirmProfile(id, lensChanges).hasError("needs.error.lensChangeConflict"));
        var threeBodies = new EquipmentRequirementProfile(List.of(GearFixtures.need("cameraCount", 3.0, null, 1, true)), false);
        assertTrue(app.facade().planning().confirmProfile(id, threeBodies).hasError("needs.error.cameraConflict"));
        var duplicate = new EquipmentRequirementProfile(List.of(GearFixtures.need("burstRate", 10.0, null, 1, true), GearFixtures.need("burstRate", 20.0, null, 9, false)), false);
        assertTrue(app.facade().planning().confirmProfile(id, duplicate).hasError("needs.error.duplicate"));
        assertArrayEquals(before, Files.readAllBytes(store()), "rejected confirmations write nothing");
    }

    @Test void clarificationKeepsManualEditsAndExplicitReplacementGuardsTheSavedProfile() throws Exception {
        var id = confirmedSession(app, gemini);
        gemini.reply(PROPOSAL);
        var proposal = ok(app.facade().proposeNeeds(id));
        var edited = CapabilityNeedForm.put(proposal.profile(), CapabilityNeed.of("batteryCount", Optional.of(6.0), Optional.empty(), 3, true, "My own spare count."));
        assertTrue(app.facade().planning().clarifyNeeds(id, proposal.withProfile(edited), "  ").hasError("needs.error.answers"));
        int sent = gemini.requests.size();
        gemini.reply(reply("[" + need("focalLength", 400, 600, 1, true, "About 400 m from the flight line.") + ","
            + need("batteryCount", 6, null, 3, true, "My own spare count.") + "]"));
        var clarified = ok(app.facade().planning().clarifyNeeds(id, proposal.withProfile(edited), "About 400 metres from the flight line."));
        assertEquals(sent + 1, gemini.requests.size());
        var evidence = PlanFixtures.evidence(gemini.requests.getLast());
        assertTrue(evidence.getAsJsonObject("currentDraft").toString().contains("My own spare count."), "manual edits are resubmitted");
        assertTrue(gemini.requests.getLast().contains("About 400 metres") && gemini.requests.getLast().contains("How far from the flight line"));
        assertEquals(Optional.of(400.0), clarified.profile().need("focalLength").orElseThrow().minimum());
        assertTrue(keys(clarified.adjustments()).contains("needs.ai.preserved.cameraCount"), "constraints are re-applied after clarification");
        assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"), "clarification never saves");

        var first = ok(app.facade().planning().confirmProfile(id, clarified.profile()));
        var session = ok(app.facade().sessions().open(id));
        byte[] saved = Files.readAllBytes(store());
        gemini.reply(PROPOSAL);
        var again = ok(app.facade().proposeNeeds(id));
        assertTrue(app.facade().planning().confirmProfile(session, again.profile()).hasError("needs.error.replaceRequired"));
        assertArrayEquals(saved, Files.readAllBytes(store()), "no silent overwrite");
        var replaced = ok(app.facade().planning().confirmProfile(session, again.profile(), true));
        assertNotEquals(first.needs().size(), replaced.needs().size());
        // A stale session version is reported as a failed save, never as success.
        assertTrue(app.facade().planning().confirmProfile(session, again.profile(), true).hasError("error.session.changed"));
    }

    @Test void requirementChangesKeepAnInconsistentProfileOutOfF07() {
        var gear = new GearFixtures.Airshow();
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = confirmedSession(app, gemini);
        gemini.reply(PROPOSAL);
        ok(app.facade().planning().confirmProfile(id, ok(app.facade().proposeNeeds(id)).profile()));
        var current = ok(app.facade().planning().requirement(id));
        var oneBody = new ShootRequirement(current.originalText(), current.subject(), current.goals(), current.time(), current.date(), current.timeWindow(),
            current.location(), current.duration(), Optional.of(1), current.lensChangesAllowed(), current.maxWeightGrams(), current.portability(),
            current.gearConstraints(), current.priorities(), current.fixedConstraints(), List.of(), false);
        ok(app.facade().planning().confirm(id, oneBody));
        var match = app.facade().planning().matchGear(id, "portability");
        assertTrue(match.hasError("needs.error.stale") && match.hasError("needs.error.cameraConflict"), () -> match.errors().toString());
        assertTrue(ok(app.facade().planning().profile(id)).reviewed(), "the saved profile itself is kept until the user updates it");
    }

    @Test void profilesSavedBeforeProvenanceLoadAsManualWithoutRewriting() throws Exception {
        Path file = root.resolve("legacy/sessions.json"); Files.createDirectories(file.getParent());
        try (var input = getClass().getResourceAsStream("/persistence/legacy-v1-sessions.json")) { Files.copy(Objects.requireNonNull(input), file); }
        byte[] before = Files.readAllBytes(file);
        var profile = ok(new com.photopilot.repository.JsonSessionRepository(file.getParent()).search("")).getFirst().profile().orElseThrow();
        assertEquals(EquipmentRequirementProfile.MANUAL, profile.origin()); assertTrue(profile.tradeOffs().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(file), "loading does not rewrite the file");
    }

    @Test void environmentContextFromF09IsSummarizedWithoutLocation() {
        var day = PlanFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, PlanFixtures.requirement(day).asDraft()));
        PlanFixtures.assess(app, gemini, id);
        gemini.reply(PROPOSAL);
        CapabilityProposal proposal = ok(app.facade().proposeNeeds(id));
        assertTrue(proposal.environmentUsed());
        var body = gemini.requests.getLast();
        var environment = PlanFixtures.evidence(body).getAsJsonObject("environment");
        assertNotNull(environment); assertTrue(environment.has("lightPhases") || environment.has("shootingWindows"), environment::toString);
        assertFalse(body.contains(String.valueOf(com.photopilot.environment.EnvironmentFixtures.LAT)), "no coordinates");
        assertFalse(body.contains(com.photopilot.environment.EnvironmentFixtures.PLACE), "no place label");
    }
}
