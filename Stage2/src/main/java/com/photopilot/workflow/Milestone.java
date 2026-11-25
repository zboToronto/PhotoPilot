package com.photopilot.workflow;

/**
 * The seven milestones of a photography session in the recommended order. Each names the page that holds it (a
 * navigation target shared with {@link com.photopilot.progress.NextStep}); shooting completion is the lifecycle action
 * of the session editor, so its target is {@link #SHOOT_TARGET}. Titles are the page titles, so the sidebar, page
 * headings and guidance use one name.
 */
public enum Milestone {
    REQUIREMENTS("F03"), EQUIPMENT_NEEDS("F06"), GEAR_SELECTION("F07"), ENVIRONMENT("F09"), PLAN("F10"), SHOOT("SHOOT"), REVIEW("F11");

    /** Navigation target of shooting completion: the session editor's lifecycle action on the sessions page. */
    public static final String SHOOT_TARGET = "SHOOT";

    private final String target;
    Milestone(String target) { this.target = target; }

    /** 1-based position in the workflow. */
    public int number() { return ordinal() + 1; }
    public String target() { return target; }
    public String titleKey() { return this == SHOOT ? "workflow.milestone.SHOOT" : "feature." + target + ".title"; }
}
