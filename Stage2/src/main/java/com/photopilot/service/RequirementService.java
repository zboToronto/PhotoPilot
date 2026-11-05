package com.photopilot.service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CapabilityNeed;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GeoLocation;
import com.photopilot.model.ShootRequirement;

/**
 * Stage 1 UML element 35 (zero-based): {F03, F06; AI interpretation, deterministic gates}
 * F03: the agent interprets text into an untrusted draft; this service converts it without inventing values,
 * and confirmation is a separate deterministic step. F06: the agent proposes capability needs for a confirmed
 * requirement; {@link #toProposal} drops anything that fails the domain rules or names products, re-applies the
 * confirmed user constraints and returns an unreviewed draft. Confirmation and saving belong to PlanningService.
 */
public class RequirementService {
    public static final int MAX_REQUEST = 4000, MAX_ANSWERS = 2000;
    /** Unspecified requirement fields that change capability sizing (storage, batteries, body and lens counts). */
    static final List<String> CAPABILITY_FIELDS = List.of("duration", "cameraCount", "lensChanges");
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
    static final String NEEDS_TASK = """
        You analyze which photographic equipment CAPABILITIES a planned shoot requires, for the PhotoPilot planning app.
        Reply only with JSON that matches the response schema. The evidence holds the user's confirmed shooting requirement,
        optional environmental context and supportedCapabilities (names, units and the bounds each one accepts).
        Rules:
        1. Describe capabilities only. Never name a brand, manufacturer, product, model or product line, and never recommend
           specific cameras, lenses or accessories. Matching against the user's own equipment is a separate later step.
        2. Include only capabilities this shoot actually needs and omit irrelevant ones. Use each capability at most once,
           only the names in the schema enum, values in the listed unit, and only the bounds that capability accepts.
           For required-only capabilities (autofocus, stabilization) set minimum and maximum to null.
        3. Derive values from the requirement and sound photographic practice; never invent user preferences.
           Preserve every confirmed constraint: a stated camera-body count n becomes cameraCount minimum = maximum = n;
           avoiding or minimizing lens changes means at most one lens per body (lensCount maximum = n);
           a stated weight limit is the totalWeight maximum.
        4. priority: 1 = most important ... 10 = least important; reflect the user's stated priorities.
           mandatory is true only when the shoot fails without the capability or the user stated it as a hard limit.
        5. rationale: one short sentence per need. tradeOffs: at most six short sentences explaining priorities and
           conflicts between needs (for example telephoto reach versus carried weight). Use the user's language.
        6. unsupportedNeeds: relevant needs none of the supported capabilities can express (for example weather sealing).
        7. clarificationQuestions: at most four, only about missing information that would materially change the needs
           (for example subject distance or shooting duration). Never ask about details that were stated.
        """;
    static final String NEEDS_CLARIFY = """
        The user is answering clarification questions about an existing capability draft (currentDraft in the evidence).
        Return the complete updated result. Keep every currentDraft need, including the user's manual edits, unless the
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

    /** Stage 1 UC06 step: Gemini-proposed, unreviewed profile for a confirmed requirement (no environment evidence). */
    public Result<EquipmentRequirementProfile> deriveProfile(ShootRequirement req) {
        var proposal = analyzeProfile(req, "");
        return proposal.isSuccess() ? Result.success(proposal.value().orElseThrow().profile()) : Result.failure(proposal);
    }

    /**
     * F06 analysis with the review context the user needs (questions, adjustments, unsupported needs). Only the
     * confirmed requirement, the supported-capability list and the given environment summary are sent; no inventory,
     * identifiers or coordinates. Never saves; a failure leaves every draft untouched.
     */
    public Result<CapabilityProposal> analyzeProfile(ShootRequirement req, String environment) {
        if (req == null || !req.confirmed()) return Result.failureKey("needs.error.requirement");
        var prompt = new AgentContext(UUID.randomUUID(), List.of("Identify the equipment capabilities this confirmed shoot requires."),
            needsEvidence(req, environment, null), List.of(), Optional.empty()).withResponseSchema(CapabilityExtraction.SCHEMA);
        var extracted = agent.run(NEEDS_TASK, prompt, CapabilityExtraction.class);
        return extracted.isSuccess() ? toProposal(req, extracted.value().orElseThrow(), present(environment)) : Result.failure(extracted);
    }

    /** Resubmits the current (possibly edited) draft with the user's answers; the result is again an unreviewed draft. */
    public Result<CapabilityProposal> clarifyProfile(ShootRequirement req, String environment, CapabilityProposal current, String answers) {
        if (req == null || !req.confirmed()) return Result.failureKey("needs.error.requirement");
        if (current == null) return Result.failureKey("needs.error.noDraft");
        if (answers == null || answers.isBlank()) return Result.failureKey("needs.error.answers");
        if (answers.strip().length() > MAX_ANSWERS) return Result.failureKey("needs.error.answersLength", MAX_ANSWERS);
        var messages = new ArrayList<String>();
        if (!current.clarificationQuestions().isEmpty()) messages.add("Questions asked:\n- " + String.join("\n- ", current.clarificationQuestions()));
        messages.add("User's answers and additional details:\n" + answers.strip());
        var prompt = new AgentContext(UUID.randomUUID(), messages, needsEvidence(req, environment, current.profile()), List.of(), Optional.empty())
            .withResponseSchema(CapabilityExtraction.SCHEMA);
        var extracted = agent.run(NEEDS_TASK + "\n" + NEEDS_CLARIFY, prompt, CapabilityExtraction.class);
        return extracted.isSuccess() ? toProposal(req, extracted.value().orElseThrow(), present(environment)) : Result.failure(extracted);
    }

    /** Requirement fields that are unspecified and would change capability sizing; reported, never filled in. */
    public static List<String> capabilityGaps(ShootRequirement req) {
        return req.unspecifiedFields().stream().filter(CAPABILITY_FIELDS::contains).toList();
    }

    /** F06 deterministic gate: structural checks of the capability needs (consistency with the requirement is separate). */
    public Result<EquipmentRequirementProfile> validateProfile(EquipmentRequirementProfile profile) {
        if (profile == null) return Result.failureKey("needs.error.none");
        var found = profile.problems();
        return found.isEmpty() ? Result.success(profile) : Result.failures(found);
    }

    private String needsEvidence(ShootRequirement req, String environment, EquipmentRequirementProfile draft) {
        JsonObject evidence = new JsonObject();
        JsonObject requirement = new JsonObject();
        requirement.addProperty("originalRequest", req.originalText());
        requirement.addProperty("subject", req.subject());
        requirement.add("goals", JSON.toJsonTree(req.goals()));
        requirement.addProperty("date", req.date().map(Object::toString).orElse(null));
        requirement.addProperty("timeWindow", req.timeWindow().isBlank() ? null : req.timeWindow());
        requirement.addProperty("durationMinutes", req.duration().map(Duration::toMinutes).orElse(null));
        requirement.addProperty("cameraCount", req.cameraCount().orElse(null));
        requirement.addProperty("lensChangesAllowed", req.lensChangesAllowed().orElse(null));
        requirement.addProperty("maxWeightGrams", req.maxWeightGrams().orElse(null));
        requirement.addProperty("portability", req.portability().isBlank() ? null : req.portability());
        requirement.add("gearConstraints", JSON.toJsonTree(req.gearConstraints()));
        requirement.add("priorities", JSON.toJsonTree(req.priorities()));
        requirement.add("fixedConstraints", JSON.toJsonTree(req.fixedConstraints()));
        evidence.add("confirmedRequirement", requirement);
        var supported = new JsonArray();
        CapabilityNeed.SUPPORTED.forEach((name, definition) -> {
            JsonObject item = new JsonObject();
            item.addProperty("name", name); item.addProperty("unit", definition.unit());
            item.addProperty("accepts", definition.minimum() && definition.maximum() ? "minimum and/or maximum"
                : definition.minimum() ? "minimum only" : definition.maximum() ? "maximum only" : "no bounds (required yes/no)");
            if (definition.hasBounds()) item.addProperty("limit", definition.limit());
            supported.add(item);
        });
        evidence.add("supportedCapabilities", supported);
        if (present(environment)) evidence.add("environment", JsonParser.parseString(environment));
        if (draft != null) evidence.add("currentDraft", CapabilityExtraction.from(draft));
        return evidence.toString();
    }
    private static boolean present(String environment) { return environment != null && !environment.isBlank(); }

    /**
     * Converts untrusted model output into an unreviewed AI-assisted draft. A need that is unsupported, malformed or
     * fails the domain rules is dropped with its reason; a capability proposed twice with different values is dropped
     * as contradictory; text naming a brand or product is removed. The confirmed requirement's camera count, weight
     * limit and lens-change restriction then override the proposal. Nothing is guessed in place of a dropped value.
     */
    Result<CapabilityProposal> toProposal(ShootRequirement req, CapabilityExtraction out, boolean environmentUsed) {
        var adjustments = new ArrayList<LocalizedMessage>();
        boolean[] productRemoved = {false};
        var proposed = new LinkedHashMap<String, CapabilityNeed>();
        var conflicting = new LinkedHashSet<String>();
        for (var raw : list(out.needs())) {
            String capability = raw == null ? "" : text(raw.capability());
            if (capability.isEmpty()) { adjustments.add(LocalizedMessage.of("needs.ai.malformed")); continue; }
            if (!CapabilityNeed.SUPPORTED.containsKey(capability)) { adjustments.add(LocalizedMessage.of("needs.ai.unsupported", capability)); continue; }
            var name = LocalizedMessage.of("needs.capability." + capability);
            String rationale = text(raw.rationale());
            if (CapabilityExtraction.namesProduct(rationale)) { rationale = ""; productRemoved[0] = true; }
            rationale = limit(rationale, CapabilityNeed.MAX_RATIONALE);
            CapabilityNeed need;
            try {
                if (raw.priority() == null || raw.mandatory() == null) throw new IllegalArgumentException("Missing priority or mandatory flag.");
                need = CapabilityNeed.of(capability, Optional.ofNullable(raw.minimum()), Optional.ofNullable(raw.maximum()), raw.priority(), raw.mandatory(), rationale);
            } catch (IllegalArgumentException invalid) { adjustments.add(LocalizedMessage.of("needs.ai.dropped", name)); continue; }
            var problems = need.problems();
            if (!problems.isEmpty()) { adjustments.add(LocalizedMessage.of("needs.ai.dropped", name)); adjustments.addAll(problems); continue; }
            var earlier = proposed.get(capability);
            if (earlier == null) proposed.put(capability, need);
            else if (!same(earlier, need)) conflicting.add(capability);
        }
        for (String capability : conflicting) {
            proposed.remove(capability);
            adjustments.add(LocalizedMessage.of("needs.ai.conflict", LocalizedMessage.of("needs.capability." + capability)));
        }
        if (proposed.isEmpty()) return Result.failures(withFirst(LocalizedMessage.of("needs.ai.noUsable"), adjustments));
        preserve(req, proposed, adjustments);
        var tradeOffs = texts(out.tradeOffs(), EquipmentRequirementProfile.MAX_TRADE_OFFS, EquipmentRequirementProfile.MAX_TRADE_OFF, productRemoved);
        var unsupported = texts(out.unsupportedNeeds(), 6, 200, productRemoved);
        var questions = texts(out.clarificationQuestions(), 4, 300, productRemoved);
        if (productRemoved[0]) adjustments.add(LocalizedMessage.of("needs.ai.productRemoved"));
        var draft = new EquipmentRequirementProfile(List.copyOf(proposed.values()), false, EquipmentRequirementProfile.AI_ASSISTED, tradeOffs);
        return Result.success(new CapabilityProposal(draft, questions, unsupported, adjustments, capabilityGaps(req), environmentUsed));
    }

    /** Confirmed user constraints are authoritative: the model's proposal is corrected, never the requirement. */
    private static void preserve(ShootRequirement req, Map<String, CapabilityNeed> needs, List<LocalizedMessage> adjustments) {
        req.cameraCount().ifPresent(count -> {
            Optional<Double> exact = Optional.of((double) count);
            var current = needs.get("cameraCount");
            if (current != null && current.minimum().equals(exact) && current.maximum().equals(exact)) return;
            needs.put("cameraCount", CapabilityNeed.of("cameraCount", exact, exact, current == null ? 1 : current.priority(),
                current == null || current.hardConstraint(), current == null ? "" : current.rationale()));
            adjustments.add(LocalizedMessage.of("needs.ai.preserved.cameraCount", count));
        });
        req.maxWeightGrams().ifPresent(limit -> {
            var current = needs.get("totalWeight");
            if (current != null && current.maximum().filter(max -> max <= limit).isPresent()) return;
            needs.put("totalWeight", CapabilityNeed.of("totalWeight", Optional.empty(), Optional.of(limit), current == null ? 1 : current.priority(),
                true, current == null ? "" : current.rationale()));
            adjustments.add(LocalizedMessage.of("needs.ai.preserved.weight", limit));
        });
        if (req.lensChangesAllowed().equals(Optional.of(false))) req.cameraCount().ifPresent(count -> {
            double bodies = count;
            var current = needs.get("lensCount");
            if (current != null && current.maximum().filter(max -> max <= bodies).isPresent()) return;
            var minimum = current == null ? Optional.<Double>empty() : current.minimum().filter(min -> min <= bodies);
            needs.put("lensCount", CapabilityNeed.of("lensCount", minimum, Optional.of(bodies), current == null ? 2 : current.priority(),
                current != null && current.hardConstraint(), current == null ? "" : current.rationale()));
            adjustments.add(LocalizedMessage.of("needs.ai.preserved.lensChanges", count));
        });
    }
    private static boolean same(CapabilityNeed a, CapabilityNeed b) {
        return a.minimum().equals(b.minimum()) && a.maximum().equals(b.maximum()) && a.priority() == b.priority() && a.hardConstraint() == b.hardConstraint();
    }
    private static List<String> texts(List<String> values, int count, int length, boolean[] productRemoved) {
        var kept = new ArrayList<String>();
        for (String value : texts(values)) {
            if (CapabilityExtraction.namesProduct(value)) { productRemoved[0] = true; continue; }
            if (kept.size() < count) kept.add(limit(value, length));
        }
        return kept;
    }
    private static String limit(String value, int length) { return value.length() <= length ? value : value.substring(0, length - 1).strip() + "…"; }
    private static List<LocalizedMessage> withFirst(LocalizedMessage first, List<LocalizedMessage> rest) {
        var all = new ArrayList<LocalizedMessage>(); all.add(first); all.addAll(rest); return all;
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
