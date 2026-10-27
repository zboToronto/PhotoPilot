package com.photopilot.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.integration.MetadataExtractor;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.PostShootReview;
import com.photopilot.repository.SessionRepository;

/**
 * Stage 1 UML element 9 (zero-based): {F11; statistics independent of AI}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ReviewService {
    private MetadataExtractor metadata;
    private StatisticsCalculator statistics;
    private PhotoPilotAgent agent;
    private SessionRepository sessions;

    public ReviewService(MetadataExtractor metadata, StatisticsCalculator statistics, PhotoPilotAgent agent, SessionRepository sessions) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public Result<ExifStatistics> analyze(UUID id, List<Path> files) {
        return Result.failureKey("error.notImplemented", "ReviewService.analyze");
    }

    public Result<PostShootReview> review(UUID id) {
        return Result.failureKey("error.notImplemented", "ReviewService.review");
    }

    public Result<PostShootReview> save(UUID id, PostShootReview review) {
        return Result.failureKey("error.notImplemented", "ReviewService.save");
    }
}
