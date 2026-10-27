package com.photopilot.repository;

import java.util.List;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 31 (zero-based): {persistence port; atomic aggregate save}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface SessionRepository {

    public Result<PhotographySession> save(PhotographySession session);

    public Result<PhotographySession> find(UUID id);

    public Result<List<PhotographySession>> search(String query);

    public Result<List<UUID>> referencingGear(UUID id);
}
