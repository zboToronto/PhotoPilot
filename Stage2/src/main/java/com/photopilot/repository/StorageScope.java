package com.photopilot.repository;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable ownership/address boundary. Resolving a scope never creates or migrates data. */
public record StorageScope(Path root, Optional<UUID> profileId) {
    public StorageScope {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        profileId = Objects.requireNonNull(profileId, "profileId");
    }

    /** Existing unowned data keeps exactly the original layout. */
    public static StorageScope legacy(Path root) { return new StorageScope(root, Optional.empty()); }

    /** Reserved for a future profile manager; the ID is never a user-supplied path fragment. */
    public static StorageScope forProfile(Path root, UUID profileId) {
        return new StorageScope(root, Optional.of(Objects.requireNonNull(profileId, "profileId")));
    }

    public Path directory() {
        return profileId.map(id -> root.resolve("profiles").resolve(id.toString())).orElse(root);
    }
    public Path inventoryFile() { return directory().resolve("inventory.json"); }
    public Path sessionsDirectory() { return directory().resolve("sessions"); }
}
