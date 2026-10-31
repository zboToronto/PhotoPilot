package com.photopilot.repository;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** One revocable business context. Closing waits for its current operation; retained services then fail closed. */
public final class ProfileAccess implements AutoCloseable {
    private final JsonProfileRepository registry;
    private final StorageScope scope;
    private boolean closed;
    private final SessionRepository sessions;
    private final InventoryRepository inventory;

    ProfileAccess(JsonProfileRepository registry, StorageScope scope) {
        this.registry = registry; this.scope = scope;
        sessions = new ScopedSessions(new JsonSessionRepository(scope.sessionsDirectory()));
        inventory = new ScopedInventory(new JsonInventoryRepository(scope.inventoryFile()));
    }
    public StorageScope scope() { return scope; }
    public SessionRepository sessions() { return sessions; }
    public InventoryRepository inventory() { return inventory; }
    private String prefix() { return scope.profileId().orElseThrow() + ":"; }
    private String unstamp(String revision) {
        if (revision == null || !revision.startsWith(prefix())) throw new IllegalArgumentException("Record belongs to a different storage context; reload it in the active profile.");
        return revision.substring(prefix().length());
    }
    private synchronized <T> Result<T> run(Supplier<Result<T>> action) {
        if (closed) return Result.failureKey("error.profile.closed");
        try { return registry.withProfile(scope.profileId().orElseThrow(), action::get); }
        catch (IOException | RuntimeException error) { return Result.failureKey("error.profile.operation", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
    }
    @Override public synchronized void close() { closed = true; }
    private <T, U> Result<U> map(Result<T> result, Function<T, U> mapping) {
        return result.isSuccess() ? Result.success(mapping.apply(result.value().orElseThrow())) : Result.failure(result);
    }
    private PhotographySession stamp(PhotographySession session) { return session.withRevision(prefix() + session.revision()); }
    private GearInventory stamp(GearInventory value) { return new GearInventory(value.items(), prefix() + value.revision()); }

    private final class ScopedSessions implements SessionRepository {
        private final SessionRepository delegate;
        ScopedSessions(SessionRepository delegate) { this.delegate = delegate; }
        public Result<PhotographySession> save(PhotographySession session) {
            return run(() -> map(delegate.save(session.withRevision(unstamp(session.revision()))), ProfileAccess.this::stamp));
        }
        public Result<PhotographySession> insert(PhotographySession session) {
            return run(() -> {
                if (!session.revision().isEmpty()) return Result.failureKey("error.profile.foreign");
                return map(delegate.insert(session), ProfileAccess.this::stamp);
            });
        }
        public Result<PhotographySession> find(UUID id) { return run(() -> map(delegate.find(id), ProfileAccess.this::stamp)); }
        public Result<List<PhotographySession>> search(String query) {
            return run(() -> map(delegate.search(query), items -> items.stream().map(ProfileAccess.this::stamp).toList()));
        }
        public Result<List<UUID>> referencingGear(UUID id) { return run(() -> delegate.referencingGear(id)); }
        public Result<Boolean> delete(UUID id, String revision) { return run(() -> delegate.delete(id, unstamp(revision))); }
    }
    private final class ScopedInventory implements InventoryRepository {
        private final InventoryRepository delegate;
        ScopedInventory(InventoryRepository delegate) { this.delegate = delegate; }
        public Result<GearInventory> load() { return run(() -> map(delegate.load(), ProfileAccess.this::stamp)); }
        public Result<GearInventory> save(GearInventory value) {
            return run(() -> map(delegate.save(new GearInventory(value.items(), unstamp(value.revision()))), ProfileAccess.this::stamp));
        }
        public <T> Result<T> transact(Function<GearInventory, Result<T>> change) { return run(() -> delegate.transact(change)); }
    }
}
