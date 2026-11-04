package com.photopilot.gearimport;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.integration.EquipmentSpecificationProvider;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.*;
import com.photopilot.repository.*;
import com.photopilot.service.*;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import com.photopilot.ui.privacy.SanitizedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F04 UC04/SD02 through the shared services with an offline model and specification provider. */
class GearImportWorkflowTest {
    @TempDir Path directory;
    private final Deque<Result<ModelReply>> replies = new ArrayDeque<>();
    private final List<AgentContext> sent = new ArrayList<>();
    private final ImagePrivacyBoundary privacy = new ImagePrivacyBoundary();
    private FakeProvider provider;
    private InventoryRepository repository;
    private InventoryService service;
    private Path inventoryFile;

    /** Offline specification provider returning configured evidence per model name. */
    static final class FakeProvider implements EquipmentSpecificationProvider {
        final Map<String, List<SpecificationEvidence>> records = new HashMap<>();
        Result<List<SpecificationEvidence>> failure;
        final List<String> searched = new ArrayList<>();
        public Result<List<Equipment>> lookup(String model) { return Result.success(List.of()); }
        @Override public Result<List<SpecificationEvidence>> search(EquipmentType type, String brand, String model, String code) {
            searched.add(model);
            return failure != null ? failure : Result.success(records.getOrDefault(model, List.of()));
        }
    }
    static SpecificationEvidence evidence(String source, EquipmentType type, String model, Map<String, String> values) {
        return new SpecificationEvidence(source, URI.create("https://example.test/" + source.toLowerCase(Locale.ROOT) + "/" + Math.abs(model.hashCode())),
            type, "Sony", model, values, Instant.parse("2026-11-03T12:00:00Z"));
    }

    @BeforeEach void setUp() {
        inventoryFile = directory.resolve("inventory.json");
        repository = new JsonInventoryRepository(inventoryFile);
        var sessions = new JsonSessionRepository(directory.resolve("sessions"));
        LLMClient model = context -> { sent.add(context); return replies.isEmpty() ? Result.failureKey("ai.error.serviceUnavailable", "503") : replies.poll(); };
        var agent = new PhotoPilotAgent(model, new ToolRegistry(Map.of()), new HistoryRetriever(sessions), 8);
        provider = new FakeProvider();
        provider.records.put("α7 IV", List.of(
            evidence("Wikipedia", EquipmentType.CAMERA, "Sony α7 IV", Map.of("mount", "Sony E-mount", "sensorWidthMm", "35.9", "sensorHeightMm", "23.9",
                "batteryTypes", "NP-FZ100", "resolutionMP", "33", "weightGrams", "658")),
            evidence("Lensfun", EquipmentType.CAMERA, "Alpha 7 IV", Map.of("mount", "Sony E"))));
        provider.records.put("FE 24-70mm F2.8 GM", List.of(evidence("Lensfun", EquipmentType.LENS, "FE 24-70mm f/2.8 GM", Map.of("mount", "Sony E"))));
        provider.records.put("FE 24-70mm F2.8 GM II", List.of(evidence("Wikipedia", EquipmentType.LENS, "Sony FE 24-70mm F2.8 GM II",
            Map.of("mount", "Sony E-mount", "minFocalMm", "24", "maxFocalMm", "70", "maxAperture", "2.8", "filterDiameterMm", "82"))));
        service = new InventoryService(repository, sessions, new GearRecognitionService(agent, provider));
    }
    private void reply(String json) { replies.add(Result.success(new ModelReply(Optional.of(json), List.of(), List.of()))); }
    private SanitizedImage approved() throws Exception {
        var review = privacy.open(ImportFixtures.jpeg(directory, "photo-" + UUID.randomUUID() + ".jpg", 640, 480), ImageReview.Kind.PHOTO).value().orElseThrow();
        return privacy.approveRedacted(privacy.sanitize(review).value().orElseThrow(), true).value().orElseThrow();
    }
    private GearImportDraft recognize(String json) throws Exception {
        reply(json);
        var image = approved();
        var draft = service.recognize(image);
        privacy.discard(image);
        assertTrue(draft.isSuccess(), draft.errors().toString());
        return draft.value().orElseThrow();
    }
    private List<Equipment> stored() { return new JsonInventoryRepository(inventoryFile).load().value().orElseThrow().items(); }

    @Test void cameraIsRecognizedVerifiedConfirmedAndRetrievableAfterReopening() throws Exception {
        reply(ImportFixtures.CAMERA);
        var image = approved();
        byte[] approvedBytes = image.approvedBytes().orElseThrow();
        var result = service.recognize(image);
        assertTrue(result.isSuccess(), result.errors().toString());
        var context = sent.getFirst();
        assertArrayEquals(approvedBytes, context.approvedImage().orElseThrow().approvedBytes().orElseThrow(), "only the approved copy is sent");
        assertTrue(context.responseSchema().contains("visibleSpecifications"));
        privacy.discard(image);
        var draft = result.value().orElseThrow();
        assertEquals(GearImportDraft.Identity.VERIFIED, draft.identity());
        var fields = draft.fields();
        assertEquals("Sony E", fields.get("mount").value(), "the catalogue mount label is preferred for consistent F07 matching");
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, fields.get("mount").status());
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, fields.get("sensorWidthMm").status());
        assertEquals("Wikipedia", fields.get("sensorWidthMm").source());
        assertEquals(SpecificationStatus.UNKNOWN_OR_CONFLICTING, fields.get("burstFps").status(), "nothing invented for absent values");
        assertTrue(draft.unverifiedCritical().isEmpty());
        assertEquals(List.of("α7 IV"), provider.searched);
        assertTrue(stored().isEmpty(), "recognition never saves");

        var saved = service.confirm(draft, GearImportDraft.Decision.none());
        assertTrue(saved.isSuccess(), saved.errors().toString());
        assertTrue(draft.confirmed());
        var reopened = stored();
        assertEquals(1, reopened.size());
        var camera = (Camera) reopened.getFirst();
        assertEquals("Sony", camera.brand()); assertEquals("α7 IV", camera.model()); assertEquals("Sony E", camera.mount());
        assertTrue(camera.verifiedFields().containsAll(Set.of("mount", "sensorWidthMm", "batteryTypes", "weightGrams", "model")));
        assertTrue(camera.sources().containsKey("sensorWidthMm"));
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), camera.verifiedAt().orElseThrow());
        assertTrue(service.confirm(draft, GearImportDraft.Decision.none()).hasError("import.error.alreadyImported"), "a confirmed draft cannot be saved twice");
        assertEquals(1, stored().size());
    }

    @Test void lensWithPartialEvidenceKeepsUnknownsAndNeedsExplicitAcceptance() throws Exception {
        var draft = recognize(ImportFixtures.LENS);
        var fields = draft.fields();
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, fields.get("mount").status());
        assertEquals(SpecificationStatus.AI_PROPOSED, fields.get("minFocalMm").status());
        assertEquals("24", fields.get("minFocalMm").value());
        assertEquals(SpecificationStatus.UNKNOWN_OR_CONFLICTING, fields.get("filterDiameterMm").status());
        assertEquals(List.of("minFocalMm", "maxFocalMm", "maxAperture"), draft.unverifiedCritical());
        assertTrue(service.confirm(draft).hasError("import.error.unverified"));
        assertFalse(Files.exists(inventoryFile), "a refused confirmation writes nothing");
        assertTrue(service.confirm(draft, new GearImportDraft.Decision(true, false, Optional.empty())).isSuccess());
        var lens = (Lens) stored().getFirst();
        assertEquals(24, lens.minFocalMm()); assertEquals(2.8, lens.maxAperture());
        assertTrue(lens.filterDiameterMm().isEmpty(), "missing specifications stay unknown");
        assertTrue(lens.verifiedFields().contains("mount"));
        assertFalse(lens.verifiedFields().contains("maxAperture"), "an AI reading is never stored as verified");
    }

    @Test void ambiguousCandidatesRequireAnExplicitChoice() throws Exception {
        var draft = recognize(ImportFixtures.AMBIGUOUS_LENS);
        assertEquals(2, draft.recognizedCandidates().size());
        assertTrue(draft.ambiguous()); assertFalse(draft.selectionMade());
        assertTrue(draft.problems().stream().anyMatch(problem -> problem.key().equals("import.error.selectCandidate")));
        assertTrue(service.confirm(draft, new GearImportDraft.Decision(true, true, Optional.empty())).hasError("import.error.selectCandidate"));
        var second = service.verify(draft.select(1)).value().orElseThrow();
        assertEquals("FE 24-70mm F2.8 GM II", second.value("model"));
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, second.fields().get("filterDiameterMm").status());
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, second.fields().get("maxAperture").status());
        assertTrue(service.confirm(second).isSuccess(), "fully verified choice imports without extra acceptance");
        assertEquals("FE 24-70mm F2.8 GM II", stored().getFirst().model());
    }

    @Test void unreadableUnsupportedMalformedAndFailedRecognitionNeverCreateEquipment() throws Exception {
        for (var reply : List.of(ImportFixtures.UNREADABLE, ImportFixtures.OTHER_ONLY, "{\"readable\":true,", "not json")) {
            reply(reply);
            var image = approved();
            var result = service.recognize(image);
            privacy.discard(image);
            assertFalse(result.isSuccess(), reply);
        }
        replies.add(Result.failureKey("ai.error.timeout", "30"));
        var image = approved();
        var timedOut = service.recognize(image);
        assertTrue(timedOut.hasError("ai.error.timeout"));
        reply(ImportFixtures.UNREADABLE);
        var unreadable = service.recognize(approved());
        assertTrue(unreadable.hasError("import.error.unrecognized"));
        reply(ImportFixtures.OTHER_ONLY);
        assertTrue(service.recognize(approved()).hasError("import.error.unsupportedType"));
        reply("{\"readable\":true,");
        assertTrue(service.recognize(approved()).hasError("ai.error.malformedOutput"));
        assertFalse(Files.exists(inventoryFile));
        assertTrue(provider.searched.isEmpty(), "failed recognition never queries specifications");
    }

    @Test void noRequestWithoutAnApprovedUnclearedImage() throws Exception {
        assertTrue(service.recognize(null).hasError("import.error.notApproved"));
        var image = approved();
        privacy.discard(image);
        assertTrue(service.recognize(image).hasError("import.error.notApproved"));
        assertTrue(sent.isEmpty(), "nothing may reach the model without approval");
    }

    @Test void conflictingSourcesAndImageReadingsAreRetainedAndNeverVerified() throws Exception {
        provider.records.put("α7 IV", List.of(
            evidence("Wikipedia", EquipmentType.CAMERA, "Sony α7 IV", Map.of("mount", "Canon RF", "sensorWidthMm", "35.9")),
            evidence("Lensfun", EquipmentType.CAMERA, "Alpha 7 IV", Map.of("mount", "Sony E"))));
        var draft = recognize(ImportFixtures.CAMERA);
        var mount = draft.fields().get("mount");
        assertEquals(SpecificationStatus.UNKNOWN_OR_CONFLICTING, mount.status()); assertEquals("", mount.value());
        var conflict = draft.fieldConflicts().getFirst();
        assertEquals("mount", conflict.field());
        assertTrue(conflict.claims().contains("Wikipedia: Canon RF") && conflict.claims().contains("Lensfun: Sony E"));
        assertFalse(service.confirm(draft, new GearImportDraft.Decision(true, false, Optional.empty())).isSuccess(), "a conflicting required value blocks import");
        var resolved = draft.withEdits(Map.of("mount", "Sony E")).value().orElseThrow();
        assertEquals(SpecificationStatus.USER_PROVIDED, resolved.fields().get("mount").status(), "a user choice between sources is not external verification");
        assertTrue(service.confirm(resolved).hasError("import.error.unverified"));
        assertTrue(service.confirm(resolved, new GearImportDraft.Decision(true, false, Optional.empty())).isSuccess());
        assertFalse(stored().getFirst().verifiedFields().contains("mount"));

        provider.records.put("FE 24-70mm F2.8 GM", List.of(evidence("Wikipedia", EquipmentType.LENS, "Sony FE 24-70mm F2.8 GM",
            Map.of("mount", "Sony E-mount", "minFocalMm", "24", "maxFocalMm", "70", "maxAperture", "4"))));
        var lens = recognize(ImportFixtures.LENS);
        assertEquals(SpecificationStatus.UNKNOWN_OR_CONFLICTING, lens.fields().get("maxAperture").status(), "image reading 2.8 vs source 4 is a conflict");
        assertTrue(lens.fieldConflicts().stream().anyMatch(c -> c.field().equals("maxAperture") && c.claims().contains("image: 2.8")));
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, lens.fields().get("minFocalMm").status());
    }

    @Test void correctionsKeepProvenanceHonestAndValidationRejectsBadEntries() throws Exception {
        var draft = recognize(ImportFixtures.CAMERA);
        var same = draft.withEdits(Map.of("sensorWidthMm", "35.9")).value().orElseThrow();
        assertEquals(SpecificationStatus.EXTERNALLY_VERIFIED, same.fields().get("sensorWidthMm").status());
        var changed = draft.withEdits(Map.of("sensorWidthMm", "36")).value().orElseThrow();
        assertEquals(SpecificationStatus.USER_PROVIDED, changed.fields().get("sensorWidthMm").status());
        var renamed = draft.withEdits(Map.of("model", "α7R IV")).value().orElseThrow();
        assertEquals(GearImportDraft.Identity.NOT_CHECKED, renamed.identity());
        assertEquals(SpecificationStatus.USER_PROVIDED, renamed.fields().get("mount").status(), "values of the previous identity lose verification");
        assertTrue(renamed.notices().stream().anyMatch(n -> n.key().equals("import.notice.identityChanged")));
        assertTrue(renamed.unverifiedCritical().contains("model"));
        var cleared = draft.withEdits(Map.of("mount", "")).value().orElseThrow();
        assertFalse(cleared.problems().isEmpty(), "a missing required field blocks import");
        assertFalse(draft.withEdits(Map.of("burstFps", "fast")).value().orElseThrow().confirm().isSuccess());
        assertFalse(draft.withEdits(Map.of("quantity", "0")).value().orElseThrow().confirm().isSuccess());
        assertTrue(draft.withEdits(Map.of("lensSpeed", "1")).hasError("error.gear.unknownField"));
        var recategorized = draft.withEdits(Map.of("type", "lens")).value().orElseThrow();
        assertEquals(EquipmentType.LENS, recategorized.type());
        assertFalse(recategorized.fields().containsKey("sensorWidthMm"));
        assertFalse(recategorized.confirm().isSuccess(), "a lens without focal lengths is invalid");
        var correctedViaStageOne = draft.select(0);
        correctedViaStageOne.correct(corrected());
        assertEquals("Corrected", correctedViaStageOne.value("model"));
        assertEquals(SpecificationStatus.USER_PROVIDED, correctedViaStageOne.fields().get("model").status());
        assertFalse(Files.exists(inventoryFile));
    }
    private static Equipment corrected() {
        return com.photopilot.application.EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(),
            Map.of("brand", "Sony", "model", "Corrected", "mount", "Sony E"), null).value().orElseThrow();
    }

    @Test void duplicatesNeedAnExplicitDecisionAndQuantityIncreaseKeepsTheExistingRecord() throws Exception {
        var existing = com.photopilot.application.EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(),
            Map.of("brand", "Sony", "model", "α7 IV", "mount", "Sony E", "sensorWidthMm", "35.8"), null).value().orElseThrow();
        assertTrue(service.add(existing, false).isSuccess());
        var variant = com.photopilot.application.EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(),
            Map.of("brand", "Sony", "model", "α7R IV", "mount", "Sony E"), null).value().orElseThrow();
        assertTrue(service.add(variant, false).isSuccess());
        var draft = recognize(ImportFixtures.CAMERA);
        var duplicates = service.importDuplicates(EquipmentType.CAMERA, "Sony", "α7 IV").value().orElseThrow();
        assertEquals(List.of(existing.id()), duplicates.stream().map(Equipment::id).toList(), "a distinct variant is not a duplicate");
        assertEquals(1, service.importDuplicates(EquipmentType.CAMERA, "SONY", "a7 iv").value().orElseThrow().size(), "spelling variants are reported");
        assertTrue(service.confirm(draft).hasError("error.gear.duplicate"));
        assertEquals(2, stored().size());
        var increased = service.confirm(draft, new GearImportDraft.Decision(false, false, Optional.of(duplicates.getFirst())));
        assertTrue(increased.isSuccess(), increased.errors().toString());
        var record = stored().stream().filter(item -> item.id().equals(existing.id())).findFirst().orElseThrow();
        assertEquals(2, record.quantity());
        assertEquals("35.8", record.specifications().get("sensorWidthMm"), "the existing record is not overwritten");
        assertEquals(2, stored().size());
        var another = recognize(ImportFixtures.CAMERA);
        assertTrue(service.confirm(another, new GearImportDraft.Decision(false, false, Optional.of(existing))).hasError("error.gear.changedSave"),
            "a stale duplicate choice is refused");
        assertTrue(service.confirm(another, new GearImportDraft.Decision(false, false, Optional.of(variant))).hasError("import.error.notDuplicate"));
        assertTrue(service.confirm(another, new GearImportDraft.Decision(false, true, Optional.empty())).isSuccess());
        assertEquals(3, stored().size());
    }

    @Test void persistenceFailureAndCatalogueOutageAreReportedWithoutFalseSuccess() throws Exception {
        provider.failure = Result.failureKey("import.error.catalogNetwork", "Wikipedia");
        var draft = recognize(ImportFixtures.CAMERA);
        assertEquals(GearImportDraft.Identity.UNAVAILABLE, draft.identity());
        assertTrue(draft.notices().stream().anyMatch(n -> n.key().equals("import.notice.catalogUnavailable")));
        assertTrue(draft.fields().values().stream().noneMatch(field -> field.status() == SpecificationStatus.EXTERNALLY_VERIFIED));
        var failing = new InventoryRepository() {
            public Result<GearInventory> load() { return repository.load(); }
            public Result<GearInventory> save(GearInventory inventory) { return Result.failureKey("error.detail", "disk full"); }
        };
        var sessions = new JsonSessionRepository(directory.resolve("sessions"));
        var broken = new InventoryService(failing, sessions, new GearRecognitionService(
            new PhotoPilotAgent(context -> Result.failureKey("ai.error.network"), new ToolRegistry(Map.of()), new HistoryRetriever(sessions), 8), provider));
        var draftToSave = draft.withEdits(Map.of("mount", "Sony E")).value().orElseThrow();
        var saved = broken.confirm(draftToSave, new GearImportDraft.Decision(true, false, Optional.empty()));
        assertFalse(saved.isSuccess());
        assertFalse(draftToSave.confirmed(), "a failed save is not reported as an import");
        assertTrue(stored().isEmpty());
    }
}
