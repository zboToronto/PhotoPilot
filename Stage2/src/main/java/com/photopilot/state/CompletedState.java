package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 28 (zero-based): {Concrete State; saved review -&gt; Reviewed}
 * Guarded session lifecycle; plan/review production remains in F10/F11.
 */
public class CompletedState implements SessionState {

    public CompletedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        if (event != SessionEvent.REVIEW_SAVED) return Result.failureKey("error.session.transition", "COMPLETED", event);
        if (session.reviews().isEmpty()) return Result.failureKey("error.session.reviewRequired");
        return Result.success(new ReviewedState());
    }

    public boolean canEditPlan() {
        return false;
    }
}
