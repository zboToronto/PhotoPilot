package com.photopilot.service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GeoLocation;
import com.photopilot.model.ShootRequirement;

/**
 * Stage 1 UML element 35 (zero-based): {F03, F06; AI interpretation, deterministic gates}
 * F03: the agent interprets text into an untrusted draft; this service converts it without inventing values,
 * and confirmation is a separate deterministic step. F06 profile derivation remains NOT IMPLEMENTED.
 */
public class RequirementService {
    public static final int MAX_REQUEST = 4000;
    private static final Gson JSON = new Gson();
    static final String TASK = """
        You extract structured photography shoot requirements from a user's request for the PhotoPilot planning app.
        Reply only with JSON that matches the response schema.
        Rules:
        1. Extract only what the user stated or what follows unambiguously from it. Never invent a date, time, location,
           duration, camera count, owned equipment or any other value. Use null or an empty list for anything not stated.
        2. Preserve user constraints exactly, including camera-body count, lens-change restrictions, weight or portability
           preferences and other hard limits. Keep extracted values in the user's own wording and language.
        3. Resolve relative dates such as "next Saturday" with the "today" value in the application evidence.
           If a date is unclear, use null and ask.
        4. location only for a named place the user stated (a city, venue, park, airfield or address). A kind of event or
           setting such as "an airshow", "a wedding" or "the beach" is the subject, not a location: use null and ask.
           startTime only for a stated clock time. Put descriptive timing such as "around sunset" in timeWindow;
           never convert it into a clock time.
        5. lensChangesAllowed is false when the user wants to avoid, minimize or forbid lens changes, true only when
           the user explicitly allows them, otherwise null.
        6. gearConstraints lists equipment requirements or restrictions the user stated. Do not add equipment the user
           did not mention. fixedConstraints holds other hard constraints not covered by a dedicated field.
        7. priorities lists what the user said matters most, ranked from 1 (most important).
        8. clarificationQuestions: at most four short questions about missing or ambiguous details that would materially
           change shoot planning (for example a missing date or location) and about every contradiction.
           Do not ask about details that were clearly stated.
        9. flaggedFields names the schema fields that are ambiguous or contradictory. For a contradiction, set that
           field to null and ask which value applies.
        """;
    static final String CLARIFY = """
        The user is answering clarification questions about an existing draft (currentDraft in the evidence).
        Return the complete updated requirement. Keep every currentDraft value, including manual edits, unless the
        answers change it; incorporate the answers; only ask further questions that are still unresolved.
        """;

    private PhotoPilotAgent agent;
    private final Clock clock;

    public RequirementService(PhotoPilotAgent agent) {
        this(agent, Clock.systemDefaultZone());
    }

    public RequirementService(PhotoPilotAgent agent, Clock clock) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Model-assisted draft. Never confirmed or saved here; failures leave the caller's text untouched. */
    public Result<ShootRequirement> parse(String text, AgentContext context) {
        if (text == null || text.isBlank()) return Result.failureKey("requirement.error.request");
        if (text.strip().length() > MAX_REQUEST) return Result.failureKey("requirement.error.requestLength", MAX_REQUEST);
        if (context == null) return Result.failureKey("error.session.id");
        String request = text.strip();
        var prompt = new AgentContext(context.sessionId(), List.of("Photography request:\n" + request), evidence(null),
            List.of(), Optional.empty()).withResponseSchema(RequirementExtraction.SCHEMA);
        var extracted = agent.run(TASK, prompt, RequirementExtraction.class);
        return extracted.isSuccess() ? Result.success(toDraft(request, extracted.value().orElseThrow())) : Result.failure(extracted);
    }

    /** Resubmits the original request with the user's answers and current (possibly edited) draft. */
    public Result<ShootRequirement> clarify(ShootRequirement draft, String answers) {
        if (draft == null) return Result.failureKey("requirement.error.noDraft");
        if (answers == null || answers.isBlank()) return Result.failureKey("requirement.error.answers");
        String combined = draft.originalText().isBlank() ? answers.strip() : draft.originalText() + "\n\nClarification: " + answers.strip();
        if (combined.length() > ShootRequirement.MAX_ORIGINAL_TEXT) return Result.failureKey("requirement.error.requestLength", ShootRequirement.MAX_ORIGINAL_TEXT);
        var messages = new ArrayList<String>();
        messages.add("Original photography request:\n" + (draft.originalText().isBlank() ? "(entered manually)" : draft.originalText()));
        if (!draft.clarificationQuestions().isEmpty()) messages.add("Questions asked:\n- " + String.join("\n- ", draft.clarificationQuestions()));
        messages.add("User's answers and additional details:\n" + answers.strip());
        var prompt = new AgentContext(UUID.randomUUID(), messages, evidence(draft), List.of(), Optional.empty())
            .withResponseSchema(RequirementExtraction.SCHEMA);
        var extracted = agent.run(TASK + "\n" + CLARIFY, prompt, RequirementExtraction.class);
        return extracted.isSuccess() ? Result.success(toDraft(combined, extracted.value().orElseThrow())) : Result.failure(extracted);
    }

    /** Deterministic gate: required fields, accepted values and consistency; then explicit confirmation. */
    public Result<ShootRequirement> confirm(ShootRequirement edited) {
        return edited == null ? Result.failureKey("requirement.error.noDraft") : edited.confirm();
    }

    public Result<EquipmentRequirementProfile> deriveProfile(ShootRequirement req) {
        return Result.failureKey("error.notImplemented", "RequirementService.deriveProfile");
    }

    public Result<EquipmentRequirementProfile> validateProfile(EquipmentRequirementProfile profile) {
        return Result.failureKey("error.notImplemented", "RequirementService.validateProfile");
    }

    private String evidence(ShootRequirement draft) {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("today", LocalDate.now(clock).toString());
        evidence.addProperty("timeZone", clock.getZone().getId());
        if (draft != null) evidence.add("currentDraft", JSON.toJsonTree(RequirementExtraction.from(draft)));
        return evidence.toString();
    }

    /**
     * Converts untrusted output into an unconfirmed draft. Unreadable or impossible values are dropped to
     * "not specified" and the field is flagged for review instead of guessing a replacement.
     */
    ShootRequirement toDraft(String originalText, RequirementExtraction out) {
        Set<String> flagged = new TreeSet<>();
        for (String name : list(out.flaggedFields())) {
            String field = RequirementExtraction.SCHEMA_NAMES.getOrDefault(name, name);
            if (ShootRequirement.FIELDS.contains(field)) flagged.add(field);
        }
        Optional<LocalDate> date = Optional.empty();
        if (!text(out.date()).isEmpty()) {
            try { date = Optional.of(LocalDate.parse(text(out.date()))); } catch (DateTimeParseException unreadable) { flagged.add("date"); }
        }
        String window = text(out.timeWindow());
        Optional<ZonedDateTime> time = Optional.empty();
        if (!text(out.startTime()).isEmpty()) {
            try {
                LocalTime clockTime = LocalTime.parse(text(out.startTime()));
                if (date.isPresent()) time = Optional.of(ZonedDateTime.of(date.get(), clockTime, clock.getZone()));
                else {
                    // A clock time without a date cannot become a timestamp; keep the user's words instead.
                    window = window.isEmpty() ? text(out.startTime()) : window + ", " + text(out.startTime());
                    flagged.add("date");
                }
            } catch (DateTimeParseException unreadable) { flagged.add("time"); }
        }
        Optional<GeoLocation> location = Optional.empty();
        String place = text(out.location());
        if (!place.isEmpty()) {
            if (GeoLocation.manual(place).validate().isSuccess()) location = Optional.of(GeoLocation.manual(place)); else flagged.add("location");
        }
        Optional<Duration> duration = positive(out.durationMinutes(), "duration", flagged).map(Duration::ofMinutes);
        Optional<Integer> cameras = positive(out.cameraCount(), "cameraCount", flagged);
        Optional<Double> weight = Optional.empty();
        if (out.maxWeightGrams() != null) {
            if (Double.isFinite(out.maxWeightGrams()) && out.maxWeightGrams() > 0) weight = Optional.of(out.maxWeightGrams()); else flagged.add("maxWeight");
        }
        Map<String, Integer> priorities = new LinkedHashMap<>();
        for (var priority : list(out.priorities())) {
            if (priority == null || text(priority.name()).isEmpty() || priority.rank() == null) { flagged.add("priorities"); continue; }
            priorities.putIfAbsent(text(priority.name()), priority.rank());
        }
        Map<String, String> constraints = new LinkedHashMap<>();
        for (var constraint : list(out.fixedConstraints())) {
            if (constraint == null || text(constraint.name()).isEmpty() || text(constraint.value()).isEmpty()) { flagged.add("constraints"); continue; }
            constraints.putIfAbsent(text(constraint.name()), text(constraint.value()));
        }
        var questions = texts(out.clarificationQuestions()).stream().limit(6).toList();
        return new ShootRequirement(originalText, text(out.subject()), texts(out.goals()), time, date, window, location, duration,
            cameras, Optional.ofNullable(out.lensChangesAllowed()), weight, text(out.portability()), texts(out.gearConstraints()),
            priorities, constraints, questions, false).withReviewFields(flagged);
    }
    private static Optional<Integer> positive(Integer value, String field, Set<String> flagged) {
        if (value == null) return Optional.empty();
        if (value > 0) return Optional.of(value);
        flagged.add(field); return Optional.empty();
    }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static List<String> texts(List<String> values) {
        return list(values).stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
