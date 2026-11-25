package com.photopilot.repository;

import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.service.SessionService;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.repository.StoreChanges.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Observer decorators of one profile's stores: each successful session insert/save/append/delete and inventory
 * save/transaction is announced once, after the write returned, with the stored aggregate and its new revision; failed
 * or refused writes and reads announce nothing; a failing listener never affects the write or the other listeners; a
 * closed registration hears nothing more.
 */
class StoreChangesTest {
    @TempDir Path root;
    final StoreChanges changes = new StoreChanges();
    final List<StoreChanges.Change> seen = new CopyOnWriteArrayList<>();
    final GearFixtures.Airshow kit = new GearFixtures.Airshow();
    JsonSessionRepository plainSessions; JsonInventoryRepository plainInventory;
    SessionRepository sessions; InventoryRepository inventory;

    @BeforeEach void open() {
        plainSessions = new JsonSessionRepository(root.resolve("sessions")); plainInventory = new JsonInventoryRepository(root.resolve("inventory.json"));
        sessions = changes.observe(plainSessions); inventory = changes.observe(plainInventory);
        changes.listen(seen::add);
    }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static PhotographySession draft(String title) { return ok(SessionService.draft(title, LocalDate.of(2026, 12, 5), Optional.empty(), "")); }
    static List<StoreChanges.Kind> kinds(List<StoreChanges.Change> changes) { return changes.stream().map(StoreChanges.Change::kind).toList(); }
    static ShootPlan approvedPlan() {
        return new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.empty(), Map.of("iso", "200"), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    @Test void sessionWritesAnnounceTheStoredSessionWithItsNewRevision() {
        // What a listener reads back from the store when it is told, and on which thread it is told.
        var persisted = new ArrayList<String>(); var threads = new HashSet<Thread>();
        changes.listen(change -> {
            threads.add(Thread.currentThread());
            var found = plainSessions.find(change.sessionId().orElseThrow());
            persisted.add(found.isSuccess() ? found.value().orElseThrow().revision() : "missing");
        });
        var inserted = ok(sessions.insert(draft("Harbour")));
        var saved = ok(sessions.save(inserted.edit("Harbour at dawn", inserted.date(), inserted.location(), "")));
        var appended = ok(sessions.append(ok(ok(saved.appendApprovedPlan(approvedPlan())).apply(SessionEvent.PLAN_APPROVED))));
        assertTrue(ok(sessions.delete(appended.id(), appended.revision())));
        assertEquals(List.of(SESSION_SAVED, SESSION_SAVED, SESSION_SAVED, SESSION_DELETED), kinds(seen));
        var stored = List.of(inserted, saved, appended);
        for (int i = 0; i < stored.size(); i++) {
            var change = seen.get(i);
            assertEquals(Optional.of(inserted.id()), change.sessionId());
            assertEquals(stored.get(i).revision(), change.session().orElseThrow().revision(), "the stored aggregate with its new revision");
        }
        assertEquals(3, new HashSet<>(stored.stream().map(PhotographySession::revision).toList()).size(), "every write has a new revision");
        assertEquals("Harbour at dawn", seen.get(1).session().orElseThrow().title());
        assertEquals("PLANNED", seen.get(2).session().orElseThrow().status());
        assertEquals(1, seen.get(2).session().orElseThrow().plans().size());
        assertTrue(seen.get(3).session().isEmpty()); assertEquals(Optional.of(inserted.id()), seen.get(3).sessionId());
        // Announced after the write returned: the store already holds what was announced.
        assertEquals(List.of(inserted.revision(), saved.revision(), appended.revision(), "missing"), persisted);
        assertEquals(Set.of(Thread.currentThread()), threads, "announced on the writing thread");
    }

    @Test void inventoryWritesAnnounceAfterSaveAndTransact() {
        var counted = new ArrayList<Integer>();
        changes.listen(change -> counted.add(ok(plainInventory.load()).items().size()));
        ok(inventory.save(new GearInventory(List.of(kit.a7))));
        ok(inventory.transact(gear -> gear.add(kit.standard)));
        assertEquals(List.of(INVENTORY_SAVED, INVENTORY_SAVED), kinds(seen));
        for (var change : seen) { assertTrue(change.session().isEmpty()); assertTrue(change.sessionId().isEmpty()); }
        assertEquals(List.of(1, 2), counted, "the store already holds each announced write");
        assertEquals(2, ok(inventory.load()).items().size());
        assertEquals(2, seen.size(), "a load announces nothing");
    }

    @Test void failedOrRefusedWritesAnnounceNothing() throws Exception {
        var stored = ok(sessions.insert(draft("Harbour")));
        var newer = ok(sessions.save(stored.edit("Harbour at dawn", stored.date(), stored.location(), "")));
        var historical = ok(sessions.insert(SessionFixtures.linked(new CompletedState(), List.of(), false)));
        ok(inventory.save(new GearInventory(List.of(kit.a7))));
        seen.clear();
        var sessionsFile = root.resolve("sessions").resolve("sessions.json"); var inventoryFile = root.resolve("inventory.json");
        String sessionsBefore = Files.readString(sessionsFile), inventoryBefore = Files.readString(inventoryFile);
        assertTrue(sessions.save(stored.edit("Stale", stored.date(), stored.location(), "")).hasError("error.session.changed"));
        assertTrue(sessions.save(draft("Never stored")).hasError("error.session.missing"));
        assertTrue(sessions.insert(newer).hasError("error.session.duplicate"));
        assertTrue(sessions.append(newer).hasError("error.session.history"), "an append must add a record");
        assertTrue(sessions.delete(stored.id(), stored.revision()).hasError("error.session.changed"));
        assertTrue(sessions.delete(UUID.randomUUID(), "").hasError("error.session.missing"));
        var rewritten = new PhotographySession(historical.id(), historical.title(), historical.date(), historical.location(), historical.notes(), historical.createdAt(),
            historical.updatedAt(), historical.state(), Optional.empty(), historical.plans(), historical.reviews(), historical.profile(), historical.statistics(),
            historical.savedIdeas(), historical.selectedLoadout(), historical.environmentCheck()).withRevision(historical.revision());
        assertTrue(sessions.save(rewritten).hasError("requirement.error.historical"));
        assertTrue(inventory.save(new GearInventory(List.of(kit.a7, kit.standard))).hasError("error.inventory.changed"), "stale inventory revision");
        assertTrue(inventory.transact(gear -> gear.remove(UUID.randomUUID())).hasError("error.gear.notFound"), "a refused change is not written");
        assertEquals(List.of(), seen);
        assertEquals(sessionsBefore, Files.readString(sessionsFile)); assertEquals(inventoryBefore, Files.readString(inventoryFile));
    }

    @Test void aThrowingListenerNeverChangesTheWriteOrBlocksOtherListeners() {
        var guarded = new StoreChanges(); var heard = new ArrayList<StoreChanges.Change>();
        guarded.listen(change -> { throw new IllegalStateException("view failed"); });
        guarded.listen(heard::add);
        guarded.listen(change -> { throw new UnsupportedOperationException("another view failed"); });
        var observedSessions = guarded.observe(plainSessions); var observedInventory = guarded.observe(plainInventory);
        var inserted = observedSessions.insert(draft("Harbour"));
        assertTrue(inserted.isSuccess(), () -> inserted.errors().toString());
        assertEquals(inserted.value().orElseThrow().revision(), ok(plainSessions.find(inserted.value().orElseThrow().id())).revision());
        assertTrue(observedInventory.save(new GearInventory(List.of(kit.a7))).isSuccess());
        assertEquals(1, ok(plainInventory.load()).items().size());
        assertTrue(ok(observedSessions.delete(inserted.value().orElseThrow().id(), inserted.value().orElseThrow().revision())));
        assertEquals(List.of(SESSION_SAVED, INVENTORY_SAVED, SESSION_DELETED), kinds(heard));
        assertEquals(List.of(), seen, "another subject's listeners hear nothing");
    }

    @Test void closingTheRegistrationStopsItsAnnouncements() throws Exception {
        var late = new ArrayList<StoreChanges.Change>();
        var registration = changes.listen(late::add);
        var inserted = ok(sessions.insert(draft("Harbour")));
        registration.close();
        ok(sessions.save(inserted.edit("Harbour at dawn", inserted.date(), inserted.location(), "")));
        ok(inventory.save(new GearInventory(List.of(kit.a7))));
        assertEquals(List.of(SESSION_SAVED), kinds(late));
        assertEquals(List.of(SESSION_SAVED, SESSION_SAVED, INVENTORY_SAVED), kinds(seen), "other listeners still hear every write");
        assertDoesNotThrow(registration::close, "closing twice is harmless");
        assertThrows(NullPointerException.class, () -> changes.listen(null));
    }

    @Test void readsPassThroughUnchangedAndAnnounceNothing() {
        // Written through the undecorated stores: not announced either.
        var stored = ok(plainSessions.insert(SessionFixtures.linked(new PlannedState(), List.of(kit.a7.snapshot()), false)));
        ok(plainInventory.save(new GearInventory(List.of(kit.a7))));
        assertEquals(stored.revision(), ok(sessions.find(stored.id())).revision());
        assertEquals(List.of(stored.id()), ok(sessions.search("linked")).stream().map(PhotographySession::id).toList());
        assertEquals(List.of(), ok(sessions.search("no such session")));
        assertEquals(List.of(stored.id()), ok(sessions.referencingGear(kit.a7.id())));
        assertTrue(sessions.find(UUID.randomUUID()).hasError("error.session.missing"));
        assertEquals(ok(plainInventory.load()).revision(), ok(inventory.load()).revision());
        assertEquals(List.of(), seen);
    }
}
