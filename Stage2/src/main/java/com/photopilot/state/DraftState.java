package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 26 (zero-based): {Concrete State; approve valid plan -&gt; Planned}
 * Guarded session lifecycle; plan/review production remains in F10/F11.
 */
public class DraftState implements SessionState {

    public DraftState() {
    }

    public Result<SessionState> next(PhotographySession session, SessionEvent event) {
        if (event != SessionEvent.PLAN_APPROVED) return Result.failureKey("error.session.transition", "DRAFT", event);
        if (session.plans().stream().noneMatch(com.photopilot.command.ShootPlan::approved))
            return Result.failureKey("error.session.planRequired");
        return Result.success(new PlannedState());
    }

    public boolean canEditPlan() {
        return true;
    }
}
