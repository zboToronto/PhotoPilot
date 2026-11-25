package com.photopilot.repository;

import com.photopilot.model.PhotographySession;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observer subject for one profile's stores: announces each successful session or inventory write of this process
 * after the write returned (never for a failed or refused one), so open views can follow persisted state without
 * polling. Other processes (for example the CLI) are not observed; views also re-read when they are shown.
 * Announcements run on the writing thread; a listener exception never affects the write or other listeners.
 */
public final class StoreChanges {
    public enum Kind { SESSION_SAVED, SESSION_DELETED, INVENTORY_SAVED }

    /** {@code session} is the stored aggregate (with its new revision) for SESSION_SAVED; {@code sessionId} names the session. */
    public record Change(Kind kind, Optional<PhotographySession> session, Optional<UUID> sessionId) {
        public Change { Objects.requireNonNull(kind); Objects.requireNonNull(session); Objects.requireNonNull(sessionId); }
        static Change saved(PhotographySession stored) { return new Change(Kind.SESSION_SAVED, Optional.of(stored), Optional.of(stored.id())); }
        static Change deleted(UUID id) { return new Change(Kind.SESSION_DELETED, Optional.empty(), Optional.of(id)); }
        static Change inventory() { return new Change(Kind.INVENTORY_SAVED, Optional.empty(), Optional.empty()); }
    }

    @FunctionalInterface public interface Listener { void changed(Change change); }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Registers {@code listener} until the returned handle is closed. */
    public AutoCloseable listen(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void announce(Change change) {
        for (Listener listener : listeners) {
            try { listener.changed(change); } catch (RuntimeException ignored) { /* a view's failure never affects storage */ }
        }
    }

    /** The session store of this profile, announcing successful insert/save/append/delete. */
    public SessionRepository observe(SessionRepository sessions) { return new ObservedSessionRepository(sessions, this); }
    /** The inventory store of this profile, announcing successful saves and transactions. */
    public InventoryRepository observe(InventoryRepository inventory) { return new ObservedInventoryRepository(inventory, this); }
}
