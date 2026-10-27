package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 26 (zero-based): {Concrete State; approve valid plan -&gt; Planned}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class DraftState implements SessionState {

    public DraftState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return Result.failureKey("error.notImplemented", "DraftState.next");
    }

    public boolean canEditPlan() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: DraftState.canEditPlan");
    }
}
