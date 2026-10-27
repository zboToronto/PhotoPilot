package com.photopilot.repository;

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
        return Result.failure("NOT IMPLEMENTED: JsonSessionRepository.save");
    }

    public Result<PhotographySession> find(UUID id) {
        return Result.failure("NOT IMPLEMENTED: JsonSessionRepository.find");
    }

    public Result<List<PhotographySession>> search(String query) {
        return Result.failure("NOT IMPLEMENTED: JsonSessionRepository.search");
    }

    public Result<List<UUID>> referencingGear(UUID id) {
        if (id == null) return Result.failure("Equipment ID is required.");
        // F01 has no session file schema yet. An empty store is provably unreferenced;
        // any existing content must be checked by F01 before gear can be deleted.
        try (var entries = java.nio.file.Files.list(directory)) {
            if (entries.findAny().isEmpty()) return Result.success(List.of());
            return Result.failure("Cannot check saved-session references until session storage is implemented. Removal blocked; historical files preserved.");
        } catch (java.nio.file.NoSuchFileException error) {
            return Result.success(List.of());
        } catch (java.io.IOException | SecurityException error) {
            return Result.failure("Cannot check saved-session references. Removal blocked: " + error.getMessage());
        }
    }
}
