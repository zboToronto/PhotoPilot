package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.progress.NextStep;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic next-step guidance: it follows the workflow order and the actual outcome, offers the manual or
 * deterministic alternative when a provider fails, points at the page that resolves a missing prerequisite, and every
 * operation title, stage label and guidance sentence exists in English and Chinese.
 */
class NextStepsTest {
    private static NextStep after(OperationKind kind, Result<?> result) { return NextSteps.after(kind, result).orElseThrow(); }
    private static Result<Object> ok() { return Result.success(new Object()); }

    @Test void successesLeadToTheNextWorkflowPage() {
        assertEquals(Optional.of("F06"), after(OperationKind.REQUIREMENT_CONFIRM, ok()).target());
        assertEquals(Optional.of("F07"), after(OperationKind.NEEDS_CONFIRM, ok()).target());
        assertEquals(Optional.of("F09"), after(OperationKind.MATCH_SELECT, ok()).target());
        // An assessment counts for the session only once it is added to it (on the same page); then the plan follows.
        assertTrue(after(OperationKind.ENV_ASSESS, ok()).target().isEmpty());
        assertEquals("next.after.envAssess", after(OperationKind.ENV_ASSESS, ok()).message().key());
        assertEquals(Optional.of("F10"), after(OperationKind.ENV_RECORD, ok()).target());
        assertEquals(Optional.of("F01"), after(OperationKind.PLAN_APPROVE, ok()).target());
        assertEquals(Optional.of("F01"), after(OperationKind.REVIEW_SAVE, ok()).target());
        assertEquals(Optional.of("F05"), after(OperationKind.IMPORT_CONFIRM, ok()).target());
        // Drafts are reviewed on the same page before anything is saved; guidance never confirms on the user's behalf.
        for (var draft : List.of(OperationKind.REQUIREMENT_PARSE, OperationKind.NEEDS_ANALYZE, OperationKind.PLAN_GENERATE, OperationKind.REVIEW_DRAFT, OperationKind.IMPORT_RECOGNIZE))
            assertTrue(after(draft, ok()).target().isEmpty(), draft::name);
        assertEquals("next.after.plan", after(OperationKind.PLAN_GENERATE, ok()).message().key());
    }

    @Test void failuresOfferTheAvailableAlternativeAndPrerequisitesPointWhereTheyAreResolved() {
        Result<Object> unavailable = Result.failureKey("ai.error.serviceUnavailable", "503");
        assertEquals("next.manual.requirement", after(OperationKind.REQUIREMENT_PARSE, unavailable).message().key());
        assertEquals("next.manual.needs", after(OperationKind.NEEDS_ANALYZE, Result.failureKey("ai.error.timeout", "30")).message().key());
        assertEquals(Optional.of("F05"), after(OperationKind.IMPORT_RECOGNIZE, unavailable).target(), "add the item manually in Gear Inventory");
        assertEquals(Optional.of("F03"), after(OperationKind.DISCOVER, Result.failureKey("ai.error.rateLimited")).target());
        assertEquals("next.manual.plan", after(OperationKind.PLAN_GENERATE, Result.failureKey("plan.error.malformed")).message().key());
        assertEquals("next.manual.review", after(OperationKind.REVIEW_DRAFT, unavailable).message().key());
        assertEquals("next.cancelled", after(OperationKind.PLAN_GENERATE, Result.failureKey("progress.cancelled")).message().key());
        assertEquals("next.failed.conflict", after(OperationKind.REQUIREMENT_CONFIRM, Result.failureKey("error.session.changed")).message().key());
        assertEquals("next.failed.busy", after(OperationKind.MATCH_SELECT, Result.failureKey("error.session.busy")).message().key());
        assertEquals(Optional.of("F03"), after(OperationKind.PLAN_GENERATE, Result.failureKey("plan.missing.requirement")).target());
        assertEquals(Optional.of("F06"), after(OperationKind.PLAN_GENERATE, Result.failureKey("plan.missing.profile")).target());
        assertEquals(Optional.of("F01"), NextSteps.before(List.of(LocalizedMessage.of("review.missing.completed", "PLANNED"))).orElseThrow().target());
        assertTrue(NextSteps.after(OperationKind.PLAN_GENERATE, Result.failureKey("plan.error.exists", 1)).isEmpty(),
            "a rule the message already explains gets no invented step");
        assertTrue(NextSteps.before(List.of()).isEmpty());
    }

    @Test void sessionStateDecidesTheNextWorkflowAction() throws Exception {
        var store = new com.photopilot.repository.JsonSessionRepository(java.nio.file.Files.createTempDirectory("next-steps"));
        var service = new com.photopilot.service.SessionService(store);
        var draft = service.create("Next steps", java.time.LocalDate.now().plusDays(2)).value().orElseThrow();
        assertEquals(Optional.of("F03"), NextSteps.session(draft).orElseThrow().target(), "a Draft without a confirmed requirement goes to F03");
        assertTrue(NextSteps.session(null).isEmpty());
    }

    @Test void everyOperationTitleStageAndGuidanceIsLocalizedInBothLanguages() throws Exception {
        var english = bundle("i18n/messages_en.properties"); var chinese = bundle("i18n/messages_zh_CN.properties");
        var keys = new TreeSet<String>();
        for (var kind : OperationKind.values()) {
            keys.add(kind.titleKey());
            kind.stages().forEach(stage -> keys.add("progress.stage." + stage));
            for (Result<?> result : List.of(ok(), Result.failureKey("ai.error.serviceUnavailable", "503"), Result.failureKey("progress.cancelled"),
                    Result.failureKey("error.session.changed"), Result.failureKey("ai.error.keyMissing"), Result.failureKey("error.session.storage", "x")))
                NextSteps.after(kind, result).ifPresent(step -> keys.add(step.message().key()));
        }
        // Stages published outside the declared plans (agent, adapters, fallbacks, retries).
        for (String stage : List.of("agent.tool", "retry", "env.zone", "env.geocode", "env.cached", "env.factsOnly", "match.fallback"))
            keys.add("progress.stage." + stage);
        for (var status : com.photopilot.progress.OperationStatus.values()) keys.add("progress.status." + status);
        for (var persistence : com.photopilot.progress.PersistenceStatus.values())
            if (persistence != com.photopilot.progress.PersistenceStatus.NOT_APPLICABLE) keys.add("progress.persistence." + persistence);
        keys.addAll(List.of("progress.elapsed", "progress.files", "progress.attempt", "progress.then", "progress.next", "progress.open", "progress.continue",
            "progress.cancel", "progress.cancelling", "progress.cancelled", "progress.cli.stage", "progress.cli.done", "progress.cli.next"));
        for (String key : keys) {
            assertFalse(english.getProperty(key, "").isBlank(), "English: " + key);
            assertFalse(chinese.getProperty(key, "").isBlank(), "Chinese: " + key);
        }
        assertTrue(keys.size() > 90, "the check covers the whole catalogue: " + keys.size());
    }
    private static Properties bundle(String name) throws Exception {
        var properties = new Properties();
        try (var input = NextStepsTest.class.getClassLoader().getResourceAsStream(name)) {
            properties.load(new InputStreamReader(Objects.requireNonNull(input, name), StandardCharsets.UTF_8));
        }
        return properties;
    }
}
