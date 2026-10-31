package com.photopilot.inventory;

import com.photopilot.application.EquipmentForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.repository.*;
import com.photopilot.service.InventoryService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTest {
    @TempDir Path directory;
    Path file() { return directory.resolve("inventory.json"); }
    ApplicationBootstrap bootstrap() { return new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString())); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static Equipment camera(UUID id, String model) {
        return ok(EquipmentForm.parse(EquipmentType.CAMERA, id, Map.of("brand","Test Brand","model",model,"mount","Test Mount"), null));
    }
    @Test void crudSearchFilterAndRestartUseSamePersistedInventory() {
        UUID id = UUID.randomUUID();
        try (var app = bootstrap()) {
            var service = app.facade().inventory();
            assertTrue(ok(service.list("")).items().isEmpty()); assertFalse(Files.exists(file()));
            var gear = ok(service.add(camera(id, "Alpha"), false));
            assertEquals(1, ok(service.list("ALPHA", EquipmentType.CAMERA)).items().size());
            assertTrue(ok(service.list("Alpha", EquipmentType.LENS)).items().isEmpty());
            var changed = ok(EquipmentForm.parse(EquipmentType.CAMERA, id, Map.of("model","Beta","quantity","2"), gear));
            ok(service.update(changed, false));
            assertTrue(ok(service.list("Alpha")).items().isEmpty());
            assertEquals(2, ok(service.list("test mount")).items().getFirst().quantity());
            assertFalse(app.facade().analyzeNeeds(UUID.randomUUID()).isSuccess());
        }
        try (var reopened = bootstrap()) {
            var service = reopened.facade().inventory();
            assertEquals("Beta", ok(service.list("")).items().getFirst().model());
            ok(service.remove(id, false));
        }
        try (var reopened = bootstrap()) { assertTrue(ok(reopened.facade().inventory().list("")).items().isEmpty()); }
    }
    @Test void duplicateIdsAndPossibleDuplicatesRequireDifferentHandling() {
        try (var app = bootstrap()) {
            var service = app.facade().inventory(); Equipment first = camera(UUID.randomUUID(), "Alpha");
            ok(service.add(first, false));
            assertFalse(service.add(first, true).isSuccess());
            var sameModel = camera(UUID.randomUUID(), "alpha");
            assertEquals(1, service.duplicates(sameModel).size());
            assertFalse(service.add(sameModel, false).isSuccess());
            ok(service.add(sameModel, true));
            assertEquals(2, ok(service.list("")).items().size());
            assertFalse(service.update(camera(UUID.randomUUID(),"Missing"), false).isSuccess());
            assertFalse(service.remove(UUID.randomUUID(),false).isSuccess());
            assertFalse(service.save(null).isSuccess());
        }
    }
    @Test void allFiveCategoriesRoundTripOptionalValuesAndProvenance() {
        var repository = new JsonInventoryRepository(file());
        List<Equipment> items = new ArrayList<>();
        items.add(camera(UUID.randomUUID(), "Camera"));
        items.add(ok(new EquipmentData("LENS",UUID.randomUUID().toString(),"Lens Brand","Zoom",1,450.0,
            Set.of("maxAperture"),Map.of("maxAperture","https://example.org/spec"),"2026-10-26T00:00:00Z",
            Map.of("mount","Test Mount","minFocalMm","24","maxFocalMm","70","maxAperture","2.8","autofocus","false")).toEquipment()));
        items.add(ok(EquipmentForm.parse(EquipmentType.FILTER, UUID.randomUUID(),Map.of("brand","Brand","model","ND","diameterMm","77","ndStops","0"),null)));
        items.add(ok(EquipmentForm.parse(EquipmentType.BATTERY, UUID.randomUUID(),Map.of("brand","Brand","model","Battery","batteryType","B1","capacityMah","2000","ratedShots","400"),null)));
        items.add(ok(EquipmentForm.parse(EquipmentType.STORAGEMEDIA, UUID.randomUUID(),Map.of("brand","Brand","model","Card","format","SD","capacityGB","128","writeSpeedMBps","90"),null)));
        ok(repository.save(new GearInventory(items)));
        var restored = ok(new JsonInventoryRepository(file()).load());
        assertEquals(items.stream().map(EquipmentData::from).toList(),restored.items().stream().map(EquipmentData::from).toList());
        assertTrue(((Camera) restored.items().getFirst()).stabilization().isEmpty());
        assertEquals(Optional.of(false), ((Lens) restored.items().get(1)).autofocus());
        assertThrows(UnsupportedOperationException.class, () -> restored.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> restored.items().getFirst().specifications().clear());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","NaN","Infinity","abc"})
    void rejectsInvalidNumericDetails(String value) {
        assertFalse(EquipmentForm.parse(EquipmentType.CAMERA,UUID.randomUUID(),Map.of("brand","B","model","M","mount","E","weightGrams",value),null).isSuccess());
    }
    @Test void rejectsMissingAndInconsistentFieldsAndPreservesPreviousFile() throws Exception {
        var gear = camera(UUID.randomUUID(), "Alpha");
        try (var app = bootstrap()) {
            ok(app.facade().saveGear(gear)); byte[] before = Files.readAllBytes(file());
            for (Map<String,String> fields : List.of(Map.of("brand"," "),Map.of("quantity","1.5"),Map.of("mount",""),Map.of("stabilization","yes"),Map.of("surprise","value"))) {
                assertFalse(EquipmentForm.parse(EquipmentType.CAMERA,gear.id(),fields,gear).isSuccess());
            }
            assertFalse(EquipmentForm.parse(EquipmentType.LENS, UUID.randomUUID(), Map.of("brand","B","model","M","mount","E","minFocalMm","70","maxFocalMm","24","maxAperture","2.8"),null).isSuccess());
            assertArrayEquals(before, Files.readAllBytes(file()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "{\"schemaVersion\":2,\"items\":[]}",
        "{\"schemaVersion\":1,\"items\":null}","{\"schemaVersion\":1,\"items\":[]} true", "{\"schemaVersion\":1,\"items\":[],\"items\":[]}", "{schemaVersion:1,items:[]}", "{\"schemaVersion\":1,\"items\":[],\"future\":1}"})
    void malformedFilesAreNotSilentlyResetOrOverwritten(String content) throws Exception {
        Files.writeString(file(),content);
        try (var app = bootstrap()) {
            assertFalse(app.facade().inventory().list("").isSuccess());
            assertFalse(app.facade().inventory().add(camera(UUID.randomUUID(), "New"), false).isSuccess());
            assertEquals(content,Files.readString(file()));
        }
    }
    @Test void duplicateStoredIdsAndUnknownTypesAreRejected() throws Exception {
        var repository = new JsonInventoryRepository(file());
        ok(repository.save(new GearInventory(List.of(camera(UUID.randomUUID(),"Alpha")))));
        String source = Files.readString(file());
        var tree = com.google.gson.JsonParser.parseString(source).getAsJsonObject();
        tree.getAsJsonArray("items").add(tree.getAsJsonArray("items").get(0).deepCopy());
        Files.writeString(file(),tree.toString()); assertFalse(repository.load().isSuccess());
        Files.writeString(file(),source.replace("CAMERA","UNKNOWN")); assertFalse(repository.load().isSuccess());
    }
    @Test void failedAtomicReplaceKeepsPersistedAndPreviouslyLoadedState() throws Exception {
        var repository = new JsonInventoryRepository(file());
        ok(repository.save(new GearInventory(List.of(camera(UUID.randomUUID(),"Alpha")))));
        byte[] before = Files.readAllBytes(file()); GearInventory loaded = ok(repository.load());
        var failing = new JsonInventoryRepository(file()) {
            @Override protected void replace(Path temporary) throws IOException { throw new IOException("simulated disk failure"); }
        };
        assertFalse(failing.transact(inventory -> inventory.add(camera(UUID.randomUUID(),"Beta"))).isSuccess());
        assertArrayEquals(before,Files.readAllBytes(file())); assertEquals(1,loaded.items().size());
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void staleSavesAndOverlappingWritersCannotClobberInventory() throws Exception {
        var repository = new JsonInventoryRepository(file());
        ok(repository.save(new GearInventory(List.of(camera(UUID.randomUUID(),"Alpha")))));
        var stale = ok(repository.load());
        ok(new JsonInventoryRepository(file()).transact(inv -> inv.add(camera(UUID.randomUUID(),"Beta"))));
        assertFalse(repository.save(stale).isSuccess());
        try (var channel = FileChannel.open(directory.resolve("inventory.json.lock"),StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertFalse(repository.transact(inv -> inv.remove(stale.items().getFirst().id())).isSuccess());
        }
        assertEquals(2,ok(repository.load()).items().size());
    }
    @Test void inaccessiblePathsReturnErrors() throws Exception {
        Files.createDirectory(file()); assertFalse(new JsonInventoryRepository(file()).load().isSuccess());
        assertFalse(new JsonInventoryRepository(file()).save(new GearInventory(List.of())).isSuccess());
        Path parentFile=directory.resolve("parent-file"); Files.writeString(parentFile,"keep");
        assertFalse(new JsonInventoryRepository(parentFile.resolve("inventory.json")).save(new GearInventory(List.of())).isSuccess());
        assertEquals("keep",Files.readString(parentFile));
    }
    @Test void removalChecksReferencesRequiresAcceptanceAndNeverChangesHistoricalSnapshot() {
        try (var app = bootstrap()) {
            var gear=camera(UUID.randomUUID(),"Alpha"); var historical=gear.snapshot(); UUID sessionId=UUID.randomUUID();
            SessionRepository sessions = new SessionRepository() {
                public Result<PhotographySession> save(PhotographySession s) { return Result.failure("unused"); }
                public Result<PhotographySession> find(UUID id) { return Result.failure("unused"); }
                public Result<List<PhotographySession>> search(String query) { return Result.failure("unused"); }
                public Result<List<UUID>> referencingGear(UUID id) { return Result.success(List.of(sessionId)); }
            };
            // Reuse the already wired recognition collaborator; it is never called by F05.
            var service = new InventoryService(new JsonInventoryRepository(file()),sessions,recognition(app.facade().inventory()));
            ok(service.add(gear,false)); assertFalse(service.remove(gear.id(),false).isSuccess());
            ok(service.remove(gear.id(),true)); assertEquals("Alpha",historical.model()); assertEquals(gear.id(),historical.sourceGearId());
            assertEquals("Test Mount",historical.specs().get("mount"));
        }
    }
    private static com.photopilot.service.GearRecognitionService recognition(InventoryService service) {
        try { var field=InventoryService.class.getDeclaredField("recognition");field.setAccessible(true);return (com.photopilot.service.GearRecognitionService)field.get(service); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test void unknownSessionStorageBlocksRemovalEvenWithAcceptance() throws Exception {
        try (var app=bootstrap()) {
            var gear=camera(UUID.randomUUID(),"Alpha"); ok(app.facade().saveGear(gear));
            Files.createDirectories(directory.resolve("sessions")); Files.writeString(directory.resolve("sessions/historical.json"),"historical data");
            assertFalse(app.facade().inventory().remove(gear.id(),true).isSuccess());
            assertEquals(1,ok(app.facade().inventory().list("")).items().size());
            assertEquals("historical data",Files.readString(directory.resolve("sessions/historical.json")));
        }
    }
    @Test void editsPreserveUnchangedEvidenceAndClearChangedVerification() {
        var lens=ok(new EquipmentData("LENS",UUID.randomUUID().toString(),"Brand","Zoom",1,null,Set.of("maxAperture"),
            Map.of("maxAperture","https://example.org/spec"),"2026-10-26T00:00:00Z",Map.of("mount","E","minFocalMm","24","maxFocalMm","70","maxAperture","2.8")).toEquipment());
        var quantity=ok(EquipmentForm.parse(lens.type(),lens.id(),Map.of("quantity","2"),lens));
        assertEquals(lens.sources(),quantity.sources()); assertEquals(lens.verifiedFields(),quantity.verifiedFields());
        var edited=ok(EquipmentForm.parse(lens.type(),lens.id(),Map.of("maxAperture","4"),lens));
        assertTrue(edited.verifiedFields().isEmpty());assertTrue(edited.sources().isEmpty());assertTrue(edited.verifiedAt().isEmpty());
    }

    @Test void malformedNumericTypesAndUtf8NeverLoseOriginalData() throws Exception {
        var repository=new JsonInventoryRepository(file());
        ok(repository.save(new GearInventory(List.of(camera(UUID.randomUUID(),"Alpha")))));
        String source=Files.readString(file());
        for (String invalid : List.of(source.replace("\"quantity\": 1", "\"quantity\": 1.5"),
                source.replace("\"quantity\": 1", "\"quantity\": \"1\""), source.replace("\"brand\": \"Test Brand\"", "\"brand\": 42"))) {
            assertNotEquals(source,invalid);Files.writeString(file(),invalid);assertFalse(repository.load().isSuccess());
            assertFalse(repository.transact(inv -> inv.add(camera(UUID.randomUUID(),"Beta"))).isSuccess());assertEquals(invalid,Files.readString(file()));
        }
        byte[] invalidUtf8={(byte)0xC3,(byte)0x28};Files.write(file(),invalidUtf8);
        assertFalse(repository.load().isSuccess());assertArrayEquals(invalidUtf8,Files.readAllBytes(file()));
    }
    @Test void staleEditorsCannotOverwriteOrRemoveChangesFromAnotherInterface() {
        try(var app=bootstrap()) {
            var service=app.facade().inventory();var original=camera(UUID.randomUUID(),"Alpha");ok(service.add(original,false));
            var changed=ok(EquipmentForm.parse(original.type(),original.id(),Map.of("quantity","2"),original));ok(service.update(changed,false,original));
            assertFalse(service.update(original,false,original).isSuccess());
            assertFalse(service.remove(original.id(),true,original).isSuccess());
            assertEquals(2,ok(service.list("")).items().getFirst().quantity());
        }
    }
}
