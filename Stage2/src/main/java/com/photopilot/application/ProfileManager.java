package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.config.*;
import com.photopilot.model.LocalProfile;
import com.photopilot.repository.*;
import java.io.IOException;
import java.util.*;

/** Local profile lifecycle. Presentation must resolve drafts and stop scheduling old work before switching. */
public final class ProfileManager implements AutoCloseable {
    private final Map<String, String> environment;
    private final JsonProfileRepository repository;
    private ApplicationBootstrap active;
    private boolean closed;

    public ProfileManager(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
        repository = new JsonProfileRepository(StorageConfiguration.resolve(environment).scope().root());
        try {
            LocalProfile profile = repository.initializeDefault();
            active = new ApplicationBootstrap(environment, StorageScope.forProfile(repository.root(), profile.id()));
        } catch (IOException error) { throw new IllegalArgumentException("Cannot initialize local profiles: " + error.getMessage(), error); }
    }
    public synchronized ApplicationBootstrap activeContext() {
        if (closed) throw new IllegalStateException("Profile manager is closed."); return active;
    }
    public synchronized Result<LocalProfile> activeProfile() { return call(() -> repository.find(activeContext().storageScope().profileId().orElseThrow())); }
    public synchronized Result<List<LocalProfile>> listProfiles() { return call(repository::list); }
    public synchronized Result<LocalProfile> getProfile(UUID id) { return call(() -> repository.find(id)); }
    public synchronized Result<LocalProfile> createProfile(String name) { return call(() -> repository.create(name)); }
    public synchronized Result<LocalProfile> renameProfile(UUID id, String name) { return call(() -> repository.rename(id, name)); }

    /** Explicit transition boundary: caller has saved/discarded drafts and quiesced its UI task queue. */
    public synchronized Result<LocalProfile> selectProfile(UUID id, boolean pendingChangesHandled) {
        return call(() -> {
            if (!pendingChangesHandled) throw new IOException("Save or discard pending changes and stop old UI work before switching profiles.");
            LocalProfile profile = repository.find(id);
            if (active.storageScope().profileId().orElseThrow().equals(id)) return profile;
            // Prepare and validate everything first. A failure leaves the previous context untouched.
            ApplicationBootstrap replacement = new ApplicationBootstrap(environment, StorageScope.forProfile(repository.root(), id));
            ApplicationBootstrap previous = active;
            previous.close(); // Revokes old repositories and waits for any operation already executing.
            active = replacement;
            return profile;
        });
    }
    public synchronized Result<Boolean> deleteProfile(UUID id, boolean confirmed) {
        return call(() -> {
            if (active.storageScope().profileId().orElseThrow().equals(id)) throw new IOException("Switch away from the active profile before deleting it.");
            repository.archive(id, confirmed); return true;
        });
    }
    @FunctionalInterface private interface Operation<T> { T run() throws IOException; }
    private <T> Result<T> call(Operation<T> operation) {
        if (closed) return Result.failureKey("error.profile.closed");
        try { return Result.success(operation.run()); }
        catch (IOException | RuntimeException error) { return Result.failureKey("error.profile.operation", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
    }
    @Override public synchronized void close() { if (!closed) { active.close(); closed = true; } }
}
