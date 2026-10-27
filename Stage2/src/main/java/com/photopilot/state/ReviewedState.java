package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 29 (zero-based): {Concrete State; historical plan read-only}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ReviewedState implements SessionState {

    public ReviewedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return Result.failureKey("error.notImplemented", "ReviewedState.next");
    }

    public boolean canEditPlan() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ReviewedState.canEditPlan");
    }
}
