package com.photopilot.ui.gui;

import com.photopilot.model.PhotographySession;
import javafx.beans.value.ObservableValue;

/** A page that works on one chosen session; the page's context bar and the active session follow its choice. */
interface SessionScoped {
    /** The session the page currently shows (null: none). */
    ObservableValue<PhotographySession> selectedSession();
    /** Joins the window's session context (see {@link SessionChooser}). */
    void useSessions(SessionWorkflowModel sessions);
}
