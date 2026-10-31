package com.photopilot.state;


import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 25 (zero-based): {State contract}
 * Guarded session lifecycle; plan/review production remains in F10/F11.
 */
public interface SessionState {

    public Result<SessionState> next(PhotographySession session, SessionEvent event);

    public boolean canEditPlan();
}
