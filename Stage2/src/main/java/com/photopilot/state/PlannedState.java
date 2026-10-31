package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 27 (zero-based): {Concrete State; completion -&gt; Completed}
 * Guarded session lifecycle; plan/review production remains in F10/F11.
 */
public class PlannedState implements SessionState {

    public PlannedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return event == SessionEvent.SHOOT_COMPLETED ? Result.success(new CompletedState())
            : Result.failureKey("error.session.transition", "PLANNED", event);
    }

    public boolean canEditPlan() {
        return true;
    }
}
