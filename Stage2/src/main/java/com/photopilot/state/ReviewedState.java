package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 29 (zero-based): {Concrete State; historical plan read-only}
 * Guarded session lifecycle; plan/review production remains in F10/F11.
 */
public class ReviewedState implements SessionState {

    public ReviewedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return Result.failureKey("error.session.transition", "REVIEWED", event);
    }

    public boolean canEditPlan() {
        return false;
    }
}
