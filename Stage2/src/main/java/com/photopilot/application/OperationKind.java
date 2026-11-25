package com.photopilot.application;

import com.photopilot.progress.ExecutionProgressListener;
import com.photopilot.progress.OperationContext;
import com.photopilot.progress.PersistenceStatus;
import java.util.List;

/**
 * The user-visible operations that report execution progress, shared by GUI and CLI. Each names its feature, its
 * localized title ({@code progress.op.<key>}), the stages its service code is expected to publish in order (stages not
 * reached are dropped, extra ones such as tool calls or retries are inserted as they happen), whether it waits on an
 * external service or long local work (so it must not hold up other work), whether cancelling it is safe, and what a
 * successful result is when nothing was written. Inexpensive deterministic work (F08, list/search) has no entry.
 */
public enum OperationKind {
    DISCOVER("F02", "discover", true, true, PersistenceStatus.RESULT_AVAILABLE,
        "discover.context", "env.weather", "env.sun", "discover.gear", "ai.wait", "ai.check", "discover.validate"),
    REQUIREMENT_PARSE("F03", "requirementParse", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "session.load", "ai.wait", "ai.check", "requirement.draft"),
    REQUIREMENT_CLARIFY("F03", "requirementClarify", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "session.load", "ai.wait", "ai.check", "requirement.draft"),
    REQUIREMENT_CONFIRM("F03", "requirementConfirm", false, false, PersistenceStatus.NOT_APPLICABLE,
        "requirement.validate", "requirement.save"),
    IMPORT_RECOGNIZE("F04", "importRecognize", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "import.approved", "ai.wait", "ai.check", "import.candidates", "import.source", "import.evidence"),
    IMPORT_VERIFY("F04", "importVerify", true, true, PersistenceStatus.UNSAVED_DRAFT, "import.source", "import.evidence"),
    IMPORT_CONFIRM("F04", "importConfirm", false, false, PersistenceStatus.NOT_APPLICABLE,
        "import.validate", "import.duplicates", "import.save"),
    SHUTTER_CHECK("F05", "shutterCheck", true, false, PersistenceStatus.RESULT_AVAILABLE, "shutter.read", "shutter.interpret"),
    NEEDS_ANALYZE("F06", "needsAnalyze", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "session.load", "needs.evidence", "ai.wait", "ai.check", "needs.validate"),
    NEEDS_CLARIFY("F06", "needsClarify", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "session.load", "needs.evidence", "ai.wait", "ai.check", "needs.validate"),
    NEEDS_CONFIRM("F06", "needsConfirm", false, false, PersistenceStatus.NOT_APPLICABLE, "needs.validate", "needs.save"),
    MATCH("F07", "match", true, true, PersistenceStatus.RESULT_AVAILABLE,
        "session.load", "match.inventory", "match.compatibility", "match.rank", "ai.wait", "ai.check", "match.verify"),
    MATCH_SELECT("F07", "matchSelect", false, false, PersistenceStatus.NOT_APPLICABLE, "match.recheck", "match.save"),
    ENV_LOOKUP("F09", "envLookup", true, true, PersistenceStatus.RESULT_AVAILABLE, "env.geocode"),
    ENV_ASSESS("F09", "envAssess", true, true, PersistenceStatus.RESULT_AVAILABLE,
        "session.load", "env.weather", "env.sun", "env.zoneCheck", "env.solar", "ai.wait", "ai.check", "env.validate"),
    ENV_RECORD("F09", "envRecord", false, false, PersistenceStatus.NOT_APPLICABLE, "env.recordCheck", "env.record"),
    /** Gemini interpretation of the retained F09 evidence only; no location, weather or sun request. */
    ENV_RETRY_AI("F09", "envRetryAi", true, true, PersistenceStatus.RESULT_AVAILABLE, "session.load", "env.retryCheck", "ai.wait", "ai.check", "env.validate"),
    PLAN_GENERATE("F10", "planGenerate", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "plan.inputs", "plan.offers", "plan.environment", "agent.history", "ai.wait", "ai.check", "plan.validate", "plan.ready"),
    PLAN_REVISE("F10", "planRevise", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "plan.inputs", "plan.offers", "plan.environment", "agent.history", "ai.wait", "ai.check", "plan.commands", "plan.validate", "plan.ready"),
    PLAN_APPROVE("F10", "planApprove", false, false, PersistenceStatus.NOT_APPLICABLE, "plan.inputs", "plan.validate", "plan.save"),
    // Not cancellable: RAW files are read through the shared ExifTool process, which an interrupt must not reach.
    REVIEW_ANALYZE("F11", "reviewAnalyze", true, false, PersistenceStatus.RESULT_AVAILABLE,
        "review.read", "review.statistics", "review.saveStatistics"),
    REVIEW_DRAFT("F11", "reviewDraft", true, true, PersistenceStatus.UNSAVED_DRAFT,
        "review.inputs", "review.evidence", "agent.history", "ai.wait", "ai.check", "review.claims"),
    REVIEW_SAVE("F11", "reviewSave", false, false, PersistenceStatus.NOT_APPLICABLE, "review.save"),
    MINI_AI("HOME", "miniAi", true, true, PersistenceStatus.RESULT_AVAILABLE, "mini.context", "ai.wait", "mini.check");

    private final String feature, key;
    private final boolean background, cancellable;
    private final PersistenceStatus result;
    private final List<String> stages;

    OperationKind(String feature, String key, boolean background, boolean cancellable, PersistenceStatus result, String... stages) {
        this.feature = feature; this.key = key; this.background = background; this.cancellable = cancellable;
        this.result = result; this.stages = List.of(stages);
    }

    public String feature() { return feature; }
    /** Localization key of the operation title. */
    public String titleKey() { return "progress.op." + key; }
    public String key() { return key; }
    /** Waits on an external service or long local work: runs apart from quick local work and shows progress at once. */
    public boolean background() { return background; }
    public boolean cancellable() { return cancellable; }
    public List<String> stages() { return stages; }
    /**
     * Saves a session workflow milestone (requirement, capability profile, selection, environment check, plan,
     * statistics, review): after it saved, the session's own next milestone is the guidance that follows.
     */
    public boolean advancesSession() {
        return switch (this) {
            case REQUIREMENT_CONFIRM, NEEDS_CONFIRM, MATCH_SELECT, ENV_RECORD, PLAN_APPROVE, REVIEW_ANALYZE, REVIEW_SAVE -> true;
            default -> false;
        };
    }

    /** A new, not yet started operation of this kind, observed by {@code listeners}. */
    public OperationContext start(ExecutionProgressListener... listeners) {
        var context = OperationContext.create(feature, key, stages, cancellable).resultIs(result);
        for (var listener : listeners) context.listen(listener);
        return context;
    }
}
