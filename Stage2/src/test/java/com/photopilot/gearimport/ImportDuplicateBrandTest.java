package com.photopilot.gearimport;

import com.photopilot.agent.*;
import com.photopilot.application.EquipmentForm;
import com.photopilot.common.Result;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.*;
import com.photopilot.repository.*;
import com.photopilot.service.*;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F04 duplicate review always includes the F05 duplicates the add would refuse (same type, brand and model), also for
 * a brand written without ASCII letters or digits, so the import can be completed by an explicit choice.
 */
class ImportDuplicateBrandTest {
    private static final String CANON_CN = ImportFixtures.recognition(false,
        ImportFixtures.candidate("CAMERA", "佳能", "EOS R5", null, ImportFixtures.visible("Canon RF", null, null, null, null)));
    @TempDir Path directory;
    private final Deque<Result<ModelReply>> replies = new ArrayDeque<>();
    private final ImagePrivacyBoundary privacy = new ImagePrivacyBoundary();
    private Path inventoryFile;
    private InventoryService service;

    @BeforeEach void setUp() {
        inventoryFile = directory.resolve("inventory.json");
        var sessions = new JsonSessionRepository(directory.resolve("sessions"));
        LLMClient model = context -> replies.isEmpty() ? Result.failureKey("ai.error.serviceUnavailable", "503") : replies.poll();
        var agent = new PhotoPilotAgent(model, new ToolRegistry(Map.of()), new HistoryRetriever(sessions), 8);
        service = new InventoryService(new JsonInventoryRepository(inventoryFile), sessions,
            new GearRecognitionService(agent, new GearImportWorkflowTest.FakeProvider()));
    }
    private GearImportDraft recognize(String json) throws Exception {
        replies.add(Result.success(new ModelReply(Optional.of(json), List.of(), List.of())));
        var review = privacy.open(ImportFixtures.jpeg(directory, "photo-" + UUID.randomUUID() + ".jpg", 640, 480), ImageReview.Kind.PHOTO).value().orElseThrow();
        var image = privacy.approveRedacted(privacy.sanitize(review).value().orElseThrow(), true).value().orElseThrow();
        var draft = service.recognize(image);
        privacy.discard(image); privacy.discard(review);
        assertTrue(draft.isSuccess(), draft.errors().toString());
        return draft.value().orElseThrow();
    }
    private static Equipment camera(String brand, String model) {
        return EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(), Map.of("brand", brand, "model", model, "mount", "Canon RF"), null).value().orElseThrow();
    }
    private List<Equipment> stored() { return new JsonInventoryRepository(inventoryFile).load().value().orElseThrow().items(); }

    @Test void nonAsciiBrandDuplicateIsOfferedAndTheImportCanComplete() throws Exception {
        var existing = camera("佳能", "EOS R5");
        assertTrue(service.add(existing, false).isSuccess());
        assertTrue(service.add(camera("尼康", "EOS R5"), false).isSuccess());
        assertTrue(service.add(camera("佳能", "EOS R6"), false).isSuccess());
        var draft = recognize(CANON_CN);
        assertEquals("佳能", draft.value("brand"));

        var duplicates = service.importDuplicates(EquipmentType.CAMERA, " 佳能 ", "eos r5").value().orElseThrow();
        assertEquals(List.of(existing.id()), duplicates.stream().map(Equipment::id).toList(), "the record F05 calls a duplicate is offered");
        assertTrue(service.confirm(draft, new GearImportDraft.Decision(true, false, Optional.empty())).hasError("error.gear.duplicate"));
        var increased = service.confirm(draft, new GearImportDraft.Decision(true, false, Optional.of(duplicates.getFirst())));
        assertTrue(increased.isSuccess(), increased.errors().toString());
        assertEquals(2, stored().stream().filter(item -> item.id().equals(existing.id())).findFirst().orElseThrow().quantity());
        assertEquals(3, stored().size());

        var separate = recognize(CANON_CN);
        assertTrue(service.confirm(separate, new GearImportDraft.Decision(true, true, Optional.empty())).isSuccess(), "or kept as a separate record");
        assertEquals(4, stored().size());
    }
}
