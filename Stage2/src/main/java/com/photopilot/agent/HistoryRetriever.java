package com.photopilot.agent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import com.photopilot.repository.SessionRepository;

/**
 * Stage 1 UML element 18 (zero-based): {F01, F02, F10, F11; application memory}
 * Deterministic history retrieval; agent context assembly remains scaffolded.
 */
public class HistoryRetriever {
    private SessionRepository repository;

    public HistoryRetriever(SessionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<List<PhotographySession>> retrieve(String query) {
        return repository.search(query);
    }

    public Result<AgentContext> contextFor(UUID id) {
        return Result.failureKey("error.notImplemented", "HistoryRetriever.contextFor");
    }
}
