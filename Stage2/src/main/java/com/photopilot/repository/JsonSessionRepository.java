package com.photopilot.repository;

import com.photopilot.i18n.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 32 (zero-based): {data implementation; storage choice provisional}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class JsonSessionRepository implements SessionRepository {
    private Path directory;

    public JsonSessionRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Result<PhotographySession> save(PhotographySession session) {
        return Result.failureKey("error.notImplemented", "JsonSessionRepository.save");
    }

    public Result<PhotographySession> find(UUID id) {
        return Result.failureKey("error.notImplemented", "JsonSessionRepository.find");
    }

    public Result<List<PhotographySession>> search(String query) {
        return Result.failureKey("error.notImplemented", "JsonSessionRepository.search");
    }

    public Result<List<UUID>> referencingGear(UUID id) {
        if (id == null) return Result.failureKey("error.gear.idRequired");
        // F01 has no session file schema yet. An empty store is provably unreferenced;
        // any existing content must be checked by F01 before gear can be deleted.
        try (var entries = java.nio.file.Files.list(directory)) {
            if (entries.findAny().isEmpty()) return Result.success(List.of());
            return Result.failureKey("error.session.referencesUnavailable");
        } catch (java.nio.file.NoSuchFileException error) {
            return Result.success(List.of());
        } catch (java.io.IOException | SecurityException error) {
            return Result.failureKey("error.session.references", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        }
    }
}
