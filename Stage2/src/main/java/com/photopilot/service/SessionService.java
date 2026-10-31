package com.photopilot.service;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.GeoLocation;
import com.photopilot.repository.SessionRepository;
import com.photopilot.state.SessionEvent;

/**
 * Stage 1 UML element 5 (zero-based): {F01; deterministic}
 * Shared deterministic session CRUD, history and lifecycle operations.
 */
public class SessionService {
    private SessionRepository repository;

    public SessionService(SessionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<PhotographySession> create(String title, LocalDate date) {
        return create(title, date, Optional.empty(), "");
    }

    public Result<PhotographySession> create(String title, LocalDate date, Optional<GeoLocation> location, String notes) {
        if (title == null || date == null || location == null || notes == null) return Result.failureKey("error.session.details");
        Instant now = Instant.now();
        var session = new PhotographySession(UUID.randomUUID(), title, date, location, notes, now, now,
            new com.photopilot.state.DraftState(), Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        var valid = session.validate();
        return valid.isSuccess() ? repository.insert(session) : valid;
    }

    public Result<PhotographySession> save(PhotographySession session) {
        if (session == null) return Result.failureKey("error.session.details");
        var valid = session.validate();
        return valid.isSuccess() ? repository.save(session) : valid;
    }

    public Result<PhotographySession> open(UUID id) {
        return id == null ? Result.failureKey("error.session.id") : repository.find(id);
    }

    public Result<List<PhotographySession>> search(String query) {
        return repository.search(query);
    }

    public Result<PhotographySession> transition(UUID id, SessionEvent event) {
        var loaded = open(id);
        return loaded.isSuccess() ? transition(loaded.value().orElseThrow(), event) : Result.failure(loaded);
    }

    public Result<PhotographySession> transition(PhotographySession expected, SessionEvent event) {
        if (expected == null || event == null) return Result.failureKey("error.session.state");
        var changed = expected.apply(event);
        return changed.isSuccess() ? save(changed.value().orElseThrow()) : changed;
    }
    public Result<PhotographySession> update(PhotographySession expected, String title, LocalDate date, Optional<GeoLocation> location, String notes) {
        if (expected == null || title == null || date == null || location == null || notes == null) return Result.failureKey("error.session.details");
        return save(expected.edit(title, date, location, notes));
    }
    public Result<Boolean> delete(PhotographySession expected, boolean confirmed) {
        if (!confirmed) return Result.failureKey("error.session.confirmDelete");
        if (expected == null) return Result.failureKey("error.session.id");
        return repository.delete(expected.id(), expected.revision());
    }
    public Result<List<PhotographySession>> search(String query, String status, LocalDate date) {
        if (status != null && !status.isBlank() && !java.util.Set.of("DRAFT", "PLANNED", "COMPLETED", "REVIEWED").contains(status))
            return Result.failureKey("error.session.state");
        var found = search(query);
        return found.isSuccess() ? Result.success(found.value().orElseThrow().stream()
            .filter(s -> status == null || status.isBlank() || s.status().equals(status))
            .filter(s -> date == null || s.date().equals(date)).toList()) : Result.failure(found);
    }
}
