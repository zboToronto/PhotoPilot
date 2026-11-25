package com.photopilot.ui.gui;

import javafx.event.Event;
import javafx.event.EventType;

/**
 * A next-step action asking the workspace to show another page: a stable feature identifier (F01-F11), HOME, or one of
 * the session and tool entries ({@link #SHOOT}, {@link #SESSION}, {@link #REPORT}, {@link #SHUTTER}). Fired from a node
 * inside a page, it bubbles to {@link PhotoPilotWorkspace}, which uses its normal navigation; the page that fired it
 * needs no reference to the shell. Navigation never saves, confirms or approves anything.
 */
final class NavigationRequest extends Event {
    static final EventType<NavigationRequest> NAVIGATE = new EventType<>(Event.ANY, "PHOTOPILOT_NAVIGATE");
    /** Shooting completion: the current session in the session editor, at its lifecycle action. */
    static final String SHOOT = com.photopilot.workflow.Milestone.SHOOT_TARGET;
    /** The current session's overview in Photography Sessions. */
    static final String SESSION = "SESSION";
    /** The gear capability report on its own. */
    static final String REPORT = "REPORT";
    /** The camera shutter count checker on its own. */
    static final String SHUTTER = "SHUTTER";
    private final String target;
    /** The session a continuation was computed for (empty: the page as it is, e.g. a prerequisite or an alternative). */
    private final java.util.UUID session;

    NavigationRequest(String target) { this(target, null); }
    NavigationRequest(String target, java.util.UUID session) { super(NAVIGATE); this.target = target; this.session = session; }

    String target() { return target; }
    java.util.Optional<java.util.UUID> session() { return java.util.Optional.ofNullable(session); }

    /** Localization key of the page name of {@code target}, as the sidebar shows it. */
    static String titleKey(String target) {
        return switch (target) {
            case "HOME" -> "home.title";
            case SHOOT -> "workflow.milestone.SHOOT";
            case SESSION -> "feature.F01.title";
            case REPORT -> "nav.capabilityReport";
            case SHUTTER -> "nav.shutterCount";
            default -> "feature." + target + ".title";
        };
    }
}
