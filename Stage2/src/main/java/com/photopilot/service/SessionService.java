package com.photopilot.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import com.photopilot.repository.SessionRepository;
import com.photopilot.state.SessionEvent;

/**
 * Stage 1 UML element 5 (zero-based): {F01; deterministic}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class SessionService {
    private SessionRepository repository;

    public SessionService(SessionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<PhotographySession> create(String title, LocalDate date) {
        return Result.failure("NOT IMPLEMENTED: SessionService.create");
    }

    public Result<PhotographySession> save(PhotographySession session) {
        return Result.failure("NOT IMPLEMENTED: SessionService.save");
    }

    public Result<PhotographySession> open(UUID id) {
        return Result.failure("NOT IMPLEMENTED: SessionService.open");
    }

    public Result<List<PhotographySession>> search(String query) {
        return Result.failure("NOT IMPLEMENTED: SessionService.search");
    }

    public Result<PhotographySession> transition(UUID id, SessionEvent event) {
        return Result.failure("NOT IMPLEMENTED: SessionService.transition");
    }
}
