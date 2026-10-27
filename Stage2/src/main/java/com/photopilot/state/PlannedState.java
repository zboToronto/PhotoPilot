package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 27 (zero-based): {Concrete State; completion -&gt; Completed}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PlannedState implements SessionState {

    public PlannedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return Result.failure("NOT IMPLEMENTED: PlannedState.next");
    }

    public boolean canEditPlan() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PlannedState.canEditPlan");
    }
}
