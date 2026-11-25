package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.progress.ErrorKind;
import com.photopilot.progress.NextStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic next-step guidance from the actual outcome and the workflow order F01 -> F03 -> F06 -> F07 -> F09 ->
 * F10 -> (shoot) -> F01 -> F11. Shared by GUI and CLI so both suggest the same action; nothing here calls a model,
 * saves, confirms or navigates by itself (targets are only offered).
 */
public final class NextSteps {
    private NextSteps() { }

    /** Missing prerequisites (blocker message keys from the services) and where they are resolved. */
    private static final Map<String, NextStep> PREREQUISITES = Map.ofEntries(
        Map.entry("plan.missing.requirement", NextStep.to("F03", "next.before.requirement")),
        Map.entry("needs.error.requirement", NextStep.to("F03", "next.before.requirement")),
        Map.entry("requirement.error.none", NextStep.to("F03", "next.before.requirement")),
        Map.entry("plan.missing.profile", NextStep.to("F06", "next.before.profile")),
        Map.entry("needs.error.none", NextStep.to("F06", "next.before.profile")),
        Map.entry("needs.error.stale", NextStep.to("F06", "next.before.profile")),
        Map.entry("review.missing.completed", NextStep.to("F01", "next.before.completed")),
        Map.entry("review.missing.plan", NextStep.to("F10", "next.before.plan")),
        Map.entry("review.missing.requirement", NextStep.to("F03", "next.before.requirement")),
        Map.entry("review.missing.statistics", NextStep.of("next.before.statistics")),
        Map.entry("match.error.noSavedSelection", NextStep.to("F07", "next.before.selection")),
        Map.entry("plan.error.noSavedPlan", NextStep.of("next.before.generate")));

    /** The first prerequisite among {@code blockers} that another step resolves, if any. */
    public static Optional<NextStep> before(List<LocalizedMessage> blockers) {
        if (blockers == null) return Optional.empty();
        return blockers.stream().map(m -> PREREQUISITES.get(m.key())).filter(java.util.Objects::nonNull).findFirst();
    }

    /** Guidance after {@code kind} ended with {@code result} (success, failure or cancellation). */
    public static Optional<NextStep> after(OperationKind kind, Result<?> result) {
        if (result.isSuccess()) return success(kind, result.value().orElseThrow());
        var error = ErrorKind.of(result.messages());
        if (error == ErrorKind.CANCELLED) return Optional.of(NextStep.of("next.cancelled"));
        var prerequisite = before(result.messages());
        if (prerequisite.isPresent()) return prerequisite;
        return switch (error) {
            case CONFLICT -> Optional.of(NextStep.of("next.failed.conflict"));
            case STORAGE_BUSY -> Optional.of(NextStep.of("next.failed.busy"));
            case STORAGE -> Optional.of(NextStep.of("next.failed.storage"));
            case CREDENTIALS -> Optional.of(manual(kind).orElse(NextStep.of("next.failed.credentials")));
            case RATE_LIMITED, SERVICE_UNAVAILABLE, TIMEOUT, NETWORK, INVALID_RESPONSE -> Optional.of(manual(kind).orElse(NextStep.of("next.failed.retry")));
            default -> Optional.empty();
        };
    }

    /** The manual or deterministic alternative when the AI step of {@code kind} is unavailable. */
    private static Optional<NextStep> manual(OperationKind kind) {
        return Optional.ofNullable(switch (kind) {
            case DISCOVER -> NextStep.to("F03", "next.manual.discover");
            case REQUIREMENT_PARSE, REQUIREMENT_CLARIFY -> NextStep.of("next.manual.requirement");
            case IMPORT_RECOGNIZE, IMPORT_VERIFY -> NextStep.to("F05", "next.manual.import");
            case NEEDS_ANALYZE, NEEDS_CLARIFY -> NextStep.of("next.manual.needs");
            case PLAN_GENERATE, PLAN_REVISE -> NextStep.of("next.manual.plan");
            case REVIEW_DRAFT -> NextStep.of("next.manual.review");
            case MINI_AI -> NextStep.to("F02", "next.manual.mini");
            default -> null;
        });
    }

    private static Optional<NextStep> success(OperationKind kind, Object value) {
        return Optional.ofNullable(switch (kind) {
            case DISCOVER -> NextStep.of("next.after.discover");
            case REQUIREMENT_PARSE, REQUIREMENT_CLARIFY -> NextStep.of("next.after.requirementDraft");
            case REQUIREMENT_CONFIRM -> NextStep.to("F06", "next.after.requirementConfirm");
            case IMPORT_RECOGNIZE, IMPORT_VERIFY -> NextStep.of("next.after.importDraft");
            case IMPORT_CONFIRM -> NextStep.to("F05", "next.after.importConfirm");
            case NEEDS_ANALYZE, NEEDS_CLARIFY -> NextStep.of("next.after.needsDraft");
            case NEEDS_CONFIRM -> NextStep.to("F07", "next.after.needsConfirm");
            case MATCH -> value instanceof com.photopilot.model.GearMatchReport report && report.recommendations().isEmpty()
                ? NextStep.to("F05", "next.after.matchEmpty") : NextStep.of("next.after.match");
            case MATCH_SELECT -> NextStep.to("F09", "next.after.matchSelect");
            case ENV_LOOKUP -> NextStep.of("next.after.envLookup");
            case ENV_ASSESS, ENV_RETRY_AI -> NextStep.of("next.after.envAssess");
            case ENV_RECORD -> NextStep.to("F10", "next.after.envRecord");
            case PLAN_GENERATE, PLAN_REVISE -> value instanceof com.photopilot.service.PlanPreview preview && !preview.approvable()
                ? NextStep.of("next.after.planBlocked") : NextStep.of("next.after.plan");
            case PLAN_APPROVE -> NextStep.to("F01", "next.after.planApprove");
            case REVIEW_ANALYZE -> value instanceof com.photopilot.service.ExifAnalysis analysis ? analysis(analysis) : null;
            case REVIEW_DRAFT -> NextStep.of("next.after.reviewDraft");
            case REVIEW_SAVE -> NextStep.to("F01", "next.after.reviewSave");
            case MINI_AI -> NextStep.to("F02", "next.after.mini");
            case SHUTTER_CHECK -> null;
        });
    }

    private static NextStep analysis(com.photopilot.service.ExifAnalysis analysis) {
        return switch (analysis.status()) {
            case SAVED, REPLACED, UNCHANGED -> NextStep.of("next.after.statistics");
            case REPLACE_REQUIRED -> NextStep.of("next.after.statisticsReplace");
            case NO_USABLE_DATA -> NextStep.of("next.after.statisticsNone");
            case SAVE_FAILED -> NextStep.of("next.failed.storage");
        };
    }

    /**
     * The session's recommended next milestone, from its stored records (the same rule as the sidebar and Home, see
     * SessionWorkflowService); saved gear is not re-checked against the inventory here. Empty once it is complete.
     */
    public static Optional<NextStep> session(com.photopilot.model.PhotographySession session) {
        if (session == null) return Optional.empty();
        return com.photopilot.service.SessionWorkflowService.evaluateStored(session).nextStep();
    }
}
