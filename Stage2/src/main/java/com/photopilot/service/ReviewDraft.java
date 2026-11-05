package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.PostShootReview;

/**
 * F11 UC13 result shown before saving: an unsaved DRAFT review, the deterministic comparison it was based on (for
 * localized display), notes on the evidence actually used (history sessions, statements removed by validation) and
 * the deterministic limits. Only {@link ReviewService#save} persists it, after revalidating the session.
 */
public record ReviewDraft(UUID sessionId, PostShootReview review, List<LocalizedMessage> differences, List<LocalizedMessage> limitations,
        List<LocalizedMessage> notes, int historySessions, int removedStatements) {
    public ReviewDraft {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(review);
        differences = List.copyOf(differences); limitations = List.copyOf(limitations); notes = List.copyOf(notes);
    }
}
