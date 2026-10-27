package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 28 (zero-based): {Concrete State; saved review -&gt; Reviewed}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CompletedState implements SessionState {

    public CompletedState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        return Result.failureKey("error.notImplemented", "CompletedState.next");
    }

    public boolean canEditPlan() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: CompletedState.canEditPlan");
    }
}
