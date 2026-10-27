package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 25 (zero-based): {State contract}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface SessionState {

    public Result<SessionState> next(PhotographySession session, SessionEvent event);

    public boolean canEditPlan();
}
