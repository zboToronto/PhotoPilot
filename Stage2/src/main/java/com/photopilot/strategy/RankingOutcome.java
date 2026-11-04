package com.photopilot.strategy;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.GearMatchReport.Candidate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ranked candidates (each carrying any AI explanation). {@code aiRanked} is true only when a structurally valid
 * Gemini ranking was accepted; otherwise {@code notice} explains the clearly labelled deterministic fallback.
 */
public record RankingOutcome(List<Candidate> ranked, boolean aiRanked, String aiSummary, Optional<LocalizedMessage> notice) {
    public RankingOutcome { ranked = List.copyOf(ranked); Objects.requireNonNull(aiSummary); Objects.requireNonNull(notice); }
}
