package com.photopilot.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.MetadataExtractor;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.model.ShootRequirement;
import com.photopilot.repository.SessionRepository;
import com.photopilot.state.CompletedState;
import com.photopilot.state.ReviewedState;
import com.photopilot.state.SessionEvent;

/**
 * Stage 1 UML element 9 (zero-based): {F11; statistics independent of AI}
 * SD05 orchestration. UC12 {@link #analysis}: completed session -> MetadataExtractor (local, read-only) ->
 * StatisticsCalculator -> attachStatistics -> one atomic save; it never calls the agent, so it works without a key or
 * network. UC13 {@link #draft}: saved statistics + latest approved plan + confirmed goals -> deterministic comparison
 * -> PhotoPilotAgent (bounded run, no tools, history via HistoryRetriever) -> Gemini -> ReviewEvidence checks ->
 * unsaved DRAFT registered by identity. {@link #save}: reloads and revalidates, appendReview + REVIEW_SAVED
 * (Completed -> Reviewed through the State pattern) and one atomic save. Failures never write or report success.
 */
public class ReviewService {
    private MetadataExtractor metadata;
    private StatisticsCalculator statistics;
    private PhotoPilotAgent agent;
    private SessionRepository sessions;
    /** Drafts issued by this service, keyed by the exact draft object, with their owning session (never persisted). */
    private record Issued(PostShootReview review) {
        @Override public boolean equals(Object other) { return other instanceof Issued that && that.review == review; }
        @Override public int hashCode() { return System.identityHashCode(review); }
    }
    private final Map<Issued, UUID> issued = new LinkedHashMap<>();
    private static final int MAX_ISSUED = 32;

    public ReviewService(MetadataExtractor metadata, StatisticsCalculator statistics, PhotoPilotAgent agent, SessionRepository sessions) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public MetadataExtractor metadata() { return metadata; }
    public StatisticsCalculator calculator() { return statistics; }

    /** Stage 1 SD05 entry: the saved (or identical, already saved) statistics, or why none were saved. */
    public Result<ExifStatistics> analyze(UUID id, List<Path> files) {
        var result = analysis(id, files, false);
        if (!result.isSuccess()) return Result.failure(result);
        var analysis = result.value().orElseThrow();
        if (analysis.status().persisted()) return Result.success(analysis.statistics().orElseThrow());
        return analysis.notices().isEmpty() ? Result.failureKey("review.error.noUsable") : Result.failures(analysis.notices());
    }

    /**
     * UC12. Only a completed session accepts statistics; a reviewed one keeps the evidence it was reviewed with.
     * Valid statistics are saved at once; different statistics already saved are replaced only with {@code replace},
     * a repeated import of the same files is recognised and not rewritten, and no-data results never overwrite.
     */
    public Result<ExifAnalysis> analysis(UUID id, List<Path> files, boolean replace) {
        if (id == null) return Result.failureKey("error.session.id");
        if (files == null || files.isEmpty()) return Result.failureKey("review.error.noFiles");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (session.state() instanceof ReviewedState) return Result.failureKey("review.error.reviewedStatistics");
        if (!(session.state() instanceof CompletedState)) return Result.failureKey("review.error.notCompleted", session.status());
        var scanned = metadata.scan(files);
        if (!scanned.isSuccess()) return Result.failure(scanned);
        var scan = scanned.value().orElseThrow();
        var previous = session.statistics();
        if (scan.records().isEmpty()) return Result.success(new ExifAnalysis(id, scan, Optional.empty(), previous, ExifAnalysis.Status.NO_USABLE_DATA,
            List.of(LocalizedMessage.of("review.analysis.noRecords"))));
        var summary = statistics.summarize(scan.records());
        if (!summary.isSuccess()) return Result.success(new ExifAnalysis(id, scan, Optional.empty(), previous, ExifAnalysis.Status.NO_USABLE_DATA, summary.messages()));
        var stats = summary.value().orElseThrow().withSource((int) scan.failedFiles(), Instant.now(), scan.fingerprint());
        if (previous.isPresent() && previous.get().sameEvidence(stats))
            return Result.success(new ExifAnalysis(id, scan, previous, previous, ExifAnalysis.Status.UNCHANGED, List.of(LocalizedMessage.of("review.analysis.unchanged"))));
        if (previous.isPresent() && !replace)
            return Result.success(new ExifAnalysis(id, scan, Optional.of(stats), previous, ExifAnalysis.Status.REPLACE_REQUIRED, List.of(LocalizedMessage.of("review.analysis.replaceRequired"))));
        var attached = session.attachStatistics(stats);
        if (!attached.isSuccess()) return Result.success(new ExifAnalysis(id, scan, Optional.of(stats), previous, ExifAnalysis.Status.SAVE_FAILED, attached.messages()));
        var saved = sessions.save(attached.value().orElseThrow());
        if (!saved.isSuccess()) return Result.success(new ExifAnalysis(id, scan, Optional.of(stats), previous, ExifAnalysis.Status.SAVE_FAILED, saved.messages()));
        var stored = saved.value().orElseThrow().statistics().orElseThrow();
        return Result.success(new ExifAnalysis(id, scan, Optional.of(stored), previous, previous.isPresent() ? ExifAnalysis.Status.REPLACED : ExifAnalysis.Status.SAVED, List.of()));
    }

    /** Read-only readiness, saved evidence, deterministic comparison and saved reviews; no AI call. */
    public Result<ReviewInputs> inputs(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        var analysisBlockers = new ArrayList<LocalizedMessage>(); var reviewBlockers = new ArrayList<LocalizedMessage>(); var notes = new ArrayList<LocalizedMessage>();
        if (session.state() instanceof ReviewedState) {
            analysisBlockers.add(LocalizedMessage.of("review.error.reviewedStatistics"));
            reviewBlockers.add(LocalizedMessage.of("review.missing.reviewed"));
        } else if (!(session.state() instanceof CompletedState)) {
            analysisBlockers.add(LocalizedMessage.of("review.error.notCompleted", session.status()));
            reviewBlockers.add(LocalizedMessage.of("review.missing.completed", session.status()));
        }
        var stats = session.statistics().filter(s -> s.usableCount() > 0);
        var plan = PlanningService.latest(session.plans());
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (stats.isEmpty()) reviewBlockers.add(LocalizedMessage.of("review.missing.statistics"));
        if (plan.isEmpty()) reviewBlockers.add(LocalizedMessage.of("review.missing.plan"));
        if (requirement.isEmpty()) reviewBlockers.add(LocalizedMessage.of("review.missing.requirement"));
        else if (requirement.get().goals().isEmpty()) notes.add(LocalizedMessage.of("review.note.noGoals", requirement.get().subject()));
        var differences = stats.isPresent() && plan.isPresent() ? statistics.differences(stats.get(), plan.get()) : List.<LocalizedMessage>of();
        var limits = stats.map(statistics::limitations).orElse(List.of());
        return Result.success(new ReviewInputs(session, session.statistics(), plan, requirement, analysisBlockers, reviewBlockers, notes, differences, limits, session.reviews()));
    }

    /** Stage 1 SD05 entry: the validated, unsaved draft review. */
    public Result<PostShootReview> review(UUID id) {
        var draft = draft(id);
        return draft.isSuccess() ? Result.success(draft.value().orElseThrow().review()) : Result.failure(draft);
    }

    /**
     * UC13: every prerequisite is checked before Gemini is asked; missing ones are named and nothing is invented. The
     * model receives only ReviewEvidence; its reply is schema-checked by the agent, then claim-checked here. The draft
     * keeps the deterministic comparison, references and limits, and nothing is saved.
     */
    public Result<ReviewDraft> draft(UUID id) {
        var loaded = inputs(id);
        if (!loaded.isSuccess()) return Result.failure(loaded);
        var in = loaded.value().orElseThrow();
        if (!in.canReview()) return Result.failures(in.reviewBlockers());
        var stats = in.statistics().orElseThrow(); var plan = in.plan().orElseThrow(); var requirement = in.requirement().orElseThrow();
        String evidence = ReviewEvidence.json(stats, plan, requirement, in.differences(), in.limitations());
        var context = new AgentContext(id, List.of("Review this completed photography session from the verified evidence."), evidence, List.of(), Optional.empty())
            .withResponseSchema(ReviewInterpretation.SCHEMA);
        var run = agent.runWithTools(ReviewEvidence.TASK, context, ReviewInterpretation.class, Set.of());
        if (!run.isSuccess()) return run.hasError("ai.error.malformedOutput") ? Result.failureKey("review.error.malformed") : Result.failure(run);
        var output = run.value().orElseThrow();
        var checked = ReviewEvidence.check(output.output(), evidence, output.historyEvidence());
        var notes = new ArrayList<>(in.notes());
        notes.add(LocalizedMessage.of("review.note.history", output.historySessions()));
        if (checked.removed() > 0) notes.add(LocalizedMessage.of("review.note.removed", checked.removed()));
        var limitations = new ArrayList<String>();
        in.limitations().forEach(limit -> limitations.add(StatisticsCalculator.english(limit)));
        limitations.addAll(checked.limitations());
        if (checked.removed() > 0) limitations.add(StatisticsCalculator.english(LocalizedMessage.of("review.note.removed", checked.removed())));
        var review = new PostShootReview(UUID.randomUUID(), Instant.now(), stats, plan.id(), plan.revision(), statistics.compare(stats, plan),
            checked.lessons(), limitations, Optional.of(id), checked.summary(), checked.technical(), checked.equipment(), checked.exposure(),
            checked.improvements(), references(stats, plan, requirement, output.historySessions()), PostShootReview.DRAFT);
        var valid = review.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        issue(review, id);
        return Result.success(new ReviewDraft(id, review, in.differences(), in.limitations(), notes, output.historySessions(), checked.removed()));
    }

    /**
     * SD05 save of the draft the user reviewed: it must have been issued for this session and still match the saved
     * statistics and latest approved plan; appendReview, REVIEW_SAVED and one atomic save. The persisted session stays
     * Completed on any failure, so a retry is safe; a saved draft cannot be saved again.
     */
    public Result<PostShootReview> save(UUID id, PostShootReview review) {
        if (id == null) return Result.failureKey("error.session.id");
        if (review == null || !review.status().equals(PostShootReview.DRAFT)) return Result.failureKey("review.error.noDraft");
        UUID owner; synchronized (issued) { owner = issued.get(new Issued(review)); }
        if (!id.equals(owner) || review.sessionId().filter(id::equals).isEmpty()) return Result.failureKey("review.error.foreign");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (session.state() instanceof ReviewedState) return Result.failureKey("review.error.alreadyReviewed");
        if (!(session.state() instanceof CompletedState)) return Result.failureKey("review.error.notCompleted", session.status());
        if (session.statistics().filter(s -> s.sameEvidence(review.statistics())).isEmpty()) return Result.failureKey("review.error.staleStatistics");
        var plan = PlanningService.latest(session.plans());
        if (plan.filter(p -> p.id().equals(review.comparedPlanId()) && p.revision() == review.comparedRevision()).isEmpty())
            return Result.failureKey("review.error.stalePlan");
        var appended = session.appendReview(review.savedCopy());
        if (!appended.isSuccess()) return Result.failure(appended);
        var transitioned = appended.value().orElseThrow().apply(SessionEvent.REVIEW_SAVED);
        if (!transitioned.isSuccess()) return Result.failure(transitioned);
        var saved = sessions.save(transitioned.value().orElseThrow());
        if (!saved.isSuccess()) return Result.failure(saved);
        discard(id, review);
        return saved.value().orElseThrow().reviews().stream().filter(r -> r.id().equals(review.id())).findFirst()
            .map(Result::success).orElseGet(() -> Result.failureKey("review.error.notSaved"));
    }

    /** The user cancelled the draft: it can no longer be saved. Nothing was or is written. */
    public void discard(UUID id, PostShootReview review) {
        if (review == null) return;
        synchronized (issued) { issued.remove(new Issued(review)); }
    }

    /** Saved reviews of a session, oldest first. */
    public Result<List<PostShootReview>> reviews(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        return Result.success(found.value().orElseThrow().reviews().stream().sorted(Comparator.comparing(PostShootReview::createdAt)).toList());
    }

    /** Deterministic statistical references stored with the review (English, like other persisted evidence). */
    private static List<String> references(ExifStatistics stats, ShootPlan plan, ShootRequirement requirement, int history) {
        var lines = new ArrayList<LocalizedMessage>();
        lines.add(LocalizedMessage.of("review.ref.photos", stats.importedCount(), stats.usableCount(), stats.failedFileCount()));
        for (String field : com.photopilot.model.ExifRecord.FIELDS)
            lines.add(LocalizedMessage.of("review.ref.field", StatisticsCalculator.label(field), stats.count(field), stats.importedCount()));
        lines.add(LocalizedMessage.of("review.ref.plan", plan.revision()));
        lines.add(LocalizedMessage.of("review.ref.goals", requirement.subject(), requirement.goals().size()));
        lines.add(LocalizedMessage.of("review.note.history", history));
        stats.analyzedAt().ifPresent(at -> lines.add(LocalizedMessage.of("review.ref.analyzed", at.toString())));
        return lines.stream().map(StatisticsCalculator::english).toList();
    }
    private void issue(PostShootReview review, UUID session) {
        synchronized (issued) {
            issued.put(new Issued(review), session);
            while (issued.size() > MAX_ISSUED) issued.remove(issued.keySet().iterator().next());
        }
    }
}
