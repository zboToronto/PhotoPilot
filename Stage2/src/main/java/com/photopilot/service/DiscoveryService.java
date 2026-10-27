package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.DiscoveryContext;
import com.photopilot.model.ShootRequirement;
import com.photopilot.repository.SessionRepository;
import com.photopilot.validation.FeasibilityValidator;

/**
 * Stage 1 UML element 8 (zero-based): {F02; ideas before detailed planning}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class DiscoveryService {
    private PhotoPilotAgent agent;
    private EnvironmentService environment;
    private FeasibilityValidator validator;
    private SessionRepository sessions;

    public DiscoveryService(PhotoPilotAgent agent, EnvironmentService environment, FeasibilityValidator validator, SessionRepository sessions) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public Result<List<ActivitySuggestion>> suggest(DiscoveryContext context) {
        return Result.failure("NOT IMPLEMENTED: DiscoveryService.suggest");
    }

    public Result<ActivitySuggestion> save(UUID id, ActivitySuggestion idea) {
        return Result.failure("NOT IMPLEMENTED: DiscoveryService.save");
    }

    public Result<ShootRequirement> select(UUID id, ActivitySuggestion idea) {
        return Result.failure("NOT IMPLEMENTED: DiscoveryService.select");
    }

    public void dismiss(ActivitySuggestion idea) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: DiscoveryService.dismiss");
    }
}
