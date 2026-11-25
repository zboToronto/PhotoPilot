package com.photopilot.repository;

import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decorator: the same session store, announcing each successful write to {@link StoreChanges} after it returned.
 * Reads, locking, revision checks, history rules and failures are the delegate's, unchanged.
 */
final class ObservedSessionRepository implements SessionRepository {
    private final SessionRepository delegate;
    private final StoreChanges changes;

    ObservedSessionRepository(SessionRepository delegate, StoreChanges changes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate"); this.changes = Objects.requireNonNull(changes, "changes");
    }

    @Override public Result<PhotographySession> save(PhotographySession session) { return announced(delegate.save(session)); }
    @Override public Result<PhotographySession> append(PhotographySession session) { return announced(delegate.append(session)); }
    @Override public Result<PhotographySession> insert(PhotographySession session) { return announced(delegate.insert(session)); }
    @Override public Result<Boolean> delete(UUID id, String revision) {
        var removed = delegate.delete(id, revision);
        if (removed.isSuccess()) changes.announce(StoreChanges.Change.deleted(id));
        return removed;
    }
    @Override public Result<PhotographySession> find(UUID id) { return delegate.find(id); }
    @Override public Result<List<PhotographySession>> search(String query) { return delegate.search(query); }
    @Override public Result<List<UUID>> referencingGear(UUID id) { return delegate.referencingGear(id); }

    private Result<PhotographySession> announced(Result<PhotographySession> stored) {
        stored.value().ifPresent(session -> changes.announce(StoreChanges.Change.saved(session)));
        return stored;
    }
}
