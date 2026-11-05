package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.model.ShootRequirement;

/**
 * F11 readiness of one session, read-only and without AI: what UC12 may import into, what UC13 would interpret
 * (saved statistics, latest approved plan, confirmed goals), the deterministic comparison and limits, and the reviews
 * already saved. {@code analysisBlockers}/{@code reviewBlockers} name every missing prerequisite.
 */
public record ReviewInputs(PhotographySession session, Optional<ExifStatistics> statistics, Optional<ShootPlan> plan,
        Optional<ShootRequirement> requirement, List<LocalizedMessage> analysisBlockers, List<LocalizedMessage> reviewBlockers,
        List<LocalizedMessage> notes, List<LocalizedMessage> differences, List<LocalizedMessage> limitations, List<PostShootReview> saved) {
    public ReviewInputs {
        Objects.requireNonNull(session); Objects.requireNonNull(statistics); Objects.requireNonNull(plan); Objects.requireNonNull(requirement);
        analysisBlockers = List.copyOf(analysisBlockers); reviewBlockers = List.copyOf(reviewBlockers); notes = List.copyOf(notes);
        differences = List.copyOf(differences); limitations = List.copyOf(limitations); saved = List.copyOf(saved);
    }
    public boolean canAnalyze() { return analysisBlockers.isEmpty(); }
    public boolean canReview() { return reviewBlockers.isEmpty(); }
}
