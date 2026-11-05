package com.photopilot.service;

import java.time.*;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.ActivitySuggestion.TravelEvidence;
import com.photopilot.model.DiscoveryContext.ZoneSource;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.SessionRepository;
import com.photopilot.validation.FeasibilityValidator;

/**
 * Stage 1 UML element 8 (zero-based): {F02; ideas before detailed planning}
 * UC02 / SD01: {@link #prepare} assembles the context from the reviewed request (F05 inventory with UC14-verified
 * loadouts, optional F01 history, F09 evidence when coordinates exist); {@link #discover} asks Gemini for candidate
 * activities, converts the untrusted reply, measures straight-line distances to gazetteer matches, lets
 * {@link FeasibilityValidator#validate} decide each status and ranks the result deterministically. Select returns an
 * unconfirmed F03 draft, save appends to an editable session atomically, dismiss removes an idea from the current
 * results. No session is required to ask for ideas; Gemini failure yields no ideas, never invented ones.
 */
public class DiscoveryService {
    public static final int MAX_IDEAS = 6, MAX_LOOKUPS = 6, MAX_SAVED = 50, MAX_HISTORY = 5, MAX_NOTES = 6, MAX_STATEMENT = 400;
    static final String TASK = """
        You suggest realistic photography activities in the PhotoPilot app for a user who wants to take photographs but
        does not know what to shoot. The application evidence holds the context the user reviewed. Reply only with JSON
        that matches the response schema.
        Rules:
        1. Propose three to six meaningfully different activities (different subjects or settings, not rewordings of one
           idea). Propose fewer when the constraints genuinely allow only a few. This is not a chat: give only activities.
        2. Fit the stated interests and never propose anything listed under avoid. History is a small sample of saved
           sessions; use it as a hint, never as complete knowledge of the user's interests.
        3. Timing: start plus durationMinutes must lie completely inside the availability period, which is a fixed user
           constraint, and durationMinutes must not exceed maxActivityMinutes. Write start as local date-time with the
           UTC offset exactly as shown in the evidence (for example 2026-11-07T15:30-05:00).
        4. Place: placeType "atLocation" means at or within walking distance of the user's stated location; otherwise
           use "destination" with destination = one searchable place name (a town, neighbourhood, park or landmark) and
           region = province/state and country. Never state distances, travel times, opening hours, prices, admission
           or accessibility: the application checks distance itself and cannot verify the rest. Do not invent specific
           public events (festivals, shows, games); if an activity only works during a scheduled event, set
           dependsOnEvent true and say in limitations that the schedule must be checked.
        5. Equipment: the inventory lists the user's confirmed gear with labels G1, G2, ...; refer to owned gear only by
           those labels in usesGear and never assume gear that is not listed. Express hard technical requirements only
           as gearNeeds using the supported capabilities and units in the evidence; for focalLength, maximum is the
           longest reach needed and minimum the widest (shortest) focal length needed. If the inventory is empty, keep
           usesGear and gearNeeds empty and say in limitations that the needed equipment is unknown.
        6. light is the dominant light the activity needs: daylight, goldenHour, blueHour, twilight, night, mixed, or any
           when light does not matter (for example indoors). outdoor is true when weather affects the activity.
           needsClearSky is true only when a clear sky is essential (for example stars).
        7. Use only the environment evidence for weather and sun. Quote a value only exactly as given with its unit;
           otherwise describe it in words. If evidence is unavailable or stale, never assume good conditions; state the
           uncertainty in limitations.
        8. rationale: one to three sentences on why the activity suits these circumstances. limitations: what is
           uncertain and what the user must check before going.
        9. ranking lists your suggestion ids from most to least suitable. missingContext lists details that would
           improve the suggestions; adjustments lists changes the user could make when constraints are restrictive.
        """;

    private PhotoPilotAgent agent;
    private EnvironmentService environment;
    private FeasibilityValidator validator;
    private SessionRepository sessions;
    private final Optional<InventoryRepository> inventory;
    private final Optional<GearMatchingService> matching;
    private final Clock clock;
    /** Latest result and the exact idea objects it issued (identity-checked on select/save/dismiss); never persisted. */
    private DiscoveryResult latest;
    private final Map<UUID, ActivitySuggestion> issued = new LinkedHashMap<>();

    public DiscoveryService(PhotoPilotAgent agent, EnvironmentService environment, FeasibilityValidator validator, SessionRepository sessions) {
        this(agent, environment, validator, sessions, null, null, Clock.systemUTC());
    }

    /** F02 composition: the F05 inventory port and F07's deterministic loadout generation supply equipment evidence. */
    public DiscoveryService(PhotoPilotAgent agent, EnvironmentService environment, FeasibilityValidator validator, SessionRepository sessions,
            InventoryRepository inventory, GearMatchingService matching) {
        this(agent, environment, validator, sessions, inventory, matching, Clock.systemUTC());
    }

    public DiscoveryService(PhotoPilotAgent agent, EnvironmentService environment, FeasibilityValidator validator, SessionRepository sessions,
            InventoryRepository inventory, GearMatchingService matching, Clock clock) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.inventory = Optional.ofNullable(inventory);
        this.matching = Optional.ofNullable(matching);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Location selection (manual, consented device, place lookup) shared with F09. */
    public LocationService location() { return environment.location(); }

    /** UC02 steps 2-6 as one call: reviewed request -> context -> Gemini candidates -> validation -> ranking. */
    public Result<DiscoveryResult> discover(DiscoveryRequest request) {
        var context = prepare(request);
        return context.isSuccess() ? discover(context.value().orElseThrow()) : Result.failure(context);
    }

    /** Stage 1 operation: the ideas the user may act on (feasible, then unverified with limitations), ranked. */
    public Result<List<ActivitySuggestion>> suggest(DiscoveryContext context) {
        var result = discover(context);
        return result.isSuccess() ? Result.success(result.value().orElseThrow().usable()) : Result.failure(result);
    }

    /**
     * Builds the validated context. Provider failures, an unreadable inventory and missing history become notices;
     * only invalid input, a past date or an availability already over stop the request.
     */
    public Result<DiscoveryContext> prepare(DiscoveryRequest request) {
        if (request == null) return Result.failureKey("discover.error.context");
        var valid = request.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        var notices = new ArrayList<LocalizedMessage>();
        Optional<EnvironmentContext> evidence = Optional.empty();
        if (request.hasCoordinates()) {
            var retrieved = environment.retrieve(request.place(), request.date(), request.endDate());
            if (retrieved.isSuccess()) { evidence = retrieved.value(); notices.addAll(evidence.orElseThrow().notices()); }
            else notices.add(LocalizedMessage.of("discover.notice.environmentUnavailable", reason(retrieved)));
        } else notices.add(LocalizedMessage.of("discover.notice.noCoordinates"));
        ZoneId zone; ZoneSource source;
        if (evidence.flatMap(EnvironmentContext::zone).isPresent()) {
            zone = evidence.get().zone().get(); source = ZoneSource.PROVIDER;
            request.zoneHint().filter(hint -> !hint.getRules().equals(zone.getRules()))
                .ifPresent(hint -> notices.add(LocalizedMessage.of("env.notice.zoneMismatch", hint.getId(), zone.getId())));
        } else if (request.zoneHint().isPresent()) {
            zone = request.zoneHint().get(); source = ZoneSource.LOOKUP; notices.add(LocalizedMessage.of("env.notice.zoneFromLookup", zone.getId()));
        } else {
            zone = ZoneId.systemDefault(); source = ZoneSource.COMPUTER; notices.add(LocalizedMessage.of("discover.notice.zoneComputer", zone.getId()));
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (request.date().isBefore(today)) return Result.failureKey("env.error.pastDate", request.date().toString());
        if (request.date().isAfter(today.plusDays(DiscoveryRequest.MAX_DAYS_AHEAD))) return Result.failureKey("discover.error.farDate", DiscoveryRequest.MAX_DAYS_AHEAD);
        var start = FeasibilityValidator.resolveLocal(LocalDateTime.of(request.date(), request.availableFrom()), zone, true, notices);
        if (!start.isSuccess()) return Result.failure(start);
        var end = FeasibilityValidator.resolveLocal(LocalDateTime.of(request.endDate(), request.availableUntil()), zone, false, notices);
        if (!end.isSuccess()) return Result.failure(end);
        ZonedDateTime from = start.value().orElseThrow(), until = end.value().orElseThrow();
        if (!until.isAfter(from)) return Result.failureKey("env.error.emptyAvailability");
        if (!until.toInstant().isAfter(now)) return Result.failureKey("env.error.availabilityOver");
        if (from.toInstant().isBefore(now)) notices.add(LocalizedMessage.of("env.notice.partlyPast"));

        Set<String> stale = Set.of();
        List<EnvironmentAssessment.LightPhase> phases = List.of();
        if (evidence.isPresent()) {
            var context = evidence.get();
            stale = environment.staleFields(context);
            if (!stale.isEmpty()) notices.add(LocalizedMessage.of("env.notice.stale", String.join(", ", stale)));
            if (!context.hasWeather()) notices.add(LocalizedMessage.of("discover.notice.noForecast", request.date().toString()));
            long lead = Duration.between(today.atStartOfDay(), request.date().atStartOfDay()).toDays();
            if (lead > EnvironmentService.LONG_RANGE_DAYS && context.hasWeather()) notices.add(LocalizedMessage.of("env.notice.longRange", lead));
            phases = SolarCalculator.phases(request.place().latitude().orElseThrow(), request.place().longitude().orElseThrow(), zone, request.date(), request.endDate());
        }

        GearInventory gear = new GearInventory(List.of());
        boolean readable = inventory.isPresent();
        List<Loadout> loadouts = List.of();
        if (inventory.isEmpty()) notices.add(LocalizedMessage.of("discover.notice.inventoryUnavailable"));
        else {
            var loaded = inventory.get().load();
            if (loaded.isSuccess()) gear = loaded.value().orElseThrow();
            else { readable = false; notices.add(LocalizedMessage.of("discover.notice.inventoryUnreadable", reason(loaded))); }
        }
        if (readable && gear.items().isEmpty()) notices.add(LocalizedMessage.of("discover.notice.noGear"));
        if (readable && !gear.items().isEmpty() && matching.isPresent()) {
            var candidates = matching.get().compatibleCandidates(gear);
            if (candidates.isSuccess()) loadouts = candidates.value().orElseThrow();
            if (loadouts.isEmpty()) notices.add(LocalizedMessage.of("discover.notice.noLoadout"));
        }

        List<PhotographySession> history = List.of();
        if (!request.useHistory()) notices.add(LocalizedMessage.of("discover.notice.historyOff"));
        else {
            var found = sessions.search("");
            if (!found.isSuccess()) notices.add(LocalizedMessage.of("discover.notice.historyUnavailable", reason(found)));
            else {
                history = found.value().orElseThrow().stream().filter(s -> s.requirements().filter(ShootRequirement::confirmed).isPresent() || !s.savedIdeas().isEmpty())
                    .sorted(Comparator.comparing(PhotographySession::date).reversed()).limit(MAX_HISTORY).toList();
                notices.add(history.isEmpty() ? LocalizedMessage.of("discover.notice.noHistory") : LocalizedMessage.of("discover.notice.history", history.size()));
            }
        }
        if (request.place().approximate() && request.hasCoordinates()) notices.add(LocalizedMessage.of("discover.notice.approximate"));
        if (request.place().fromDevice()) notices.add(LocalizedMessage.of("discover.notice.device"));
        var context = new DiscoveryContext(Optional.of(request.place()), from, Duration.between(from, until), request.maxDistanceKm(), request.interests(),
            gear, history, evidence, request.avoid(), request.portability(), request.notes(), request.maxActivity(), source, readable, loadouts, phases, stale, notices);
        return context.validate();
    }

    /** One Gemini request for the context; the reply is untrusted and every idea passes the deterministic validator. */
    public Result<DiscoveryResult> discover(DiscoveryContext context) {
        if (context == null) return Result.failureKey("discover.error.context");
        var valid = context.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        var labels = labels(context.gear());
        var prompt = new AgentContext(UUID.randomUUID(), List.of("Suggest photography activities that fit these circumstances."),
            evidence(context, labels), List.of(), Optional.empty()).withResponseSchema(DiscoveryProposal.SCHEMA);
        var reply = agent.run(TASK, prompt, DiscoveryProposal.class);
        // Gemini failure: no ideas at all, never replacement or hard-coded ones. The caller keeps the user's inputs.
        if (!reply.isSuccess()) return Result.failure(reply);
        var result = convert(reply.value().orElseThrow(), context, labels);
        synchronized (this) {
            latest = result; issued.clear();
            result.ideas().forEach(idea -> issued.put(idea.id(), idea));
        }
        return Result.success(result);
    }

    public synchronized Optional<DiscoveryResult> latest() { return Optional.ofNullable(latest); }

    /** Stage 1 save: appends a copy to the session's saved ideas (Draft/Planned only) against its current version. */
    public Result<ActivitySuggestion> save(UUID id, ActivitySuggestion idea) {
        if (id == null) return Result.failureKey("error.session.id");
        var session = sessions.find(id);
        return session.isSuccess() ? save(session.value().orElseThrow(), idea) : Result.failure(session);
    }

    /** Saves against the session version the user chose; success is reported only after the atomic save succeeded. */
    public Result<ActivitySuggestion> save(PhotographySession expected, ActivitySuggestion idea) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (idea == null) return Result.failureKey("discover.error.idea");
        if (!issuedNow(idea)) return Result.failureKey("discover.error.unknownIdea");
        if (idea.feasibility() == Feasibility.INFEASIBLE) return Result.failureKey("discover.error.infeasible");
        var attached = expected.attachIdea(idea.forStorage());
        if (!attached.isSuccess()) return Result.failure(attached);
        var saved = sessions.save(attached.value().orElseThrow());
        if (!saved.isSuccess()) return Result.failure(saved);
        return saved.value().orElseThrow().savedIdeas().stream().filter(stored -> stored.id().equals(idea.id())).findFirst()
            .map(Result::success).orElseGet(() -> Result.failureKey("discover.error.saveFailed"));
    }

    /**
     * Stage 1 select: an unconfirmed F03 draft for an editable session. The idea must come from the current results or
     * be saved in that same session; nothing is saved and F03 performs its own review and explicit confirmation.
     */
    public Result<ShootRequirement> select(UUID id, ActivitySuggestion idea) {
        if (id == null) return Result.failureKey("error.session.id");
        if (idea == null) return Result.failureKey("discover.error.idea");
        var session = sessions.find(id);
        if (!session.isSuccess()) return Result.failure(session);
        var target = session.value().orElseThrow();
        if (!target.state().canEditPlan()) return Result.failureKey("requirement.error.historical");
        ActivitySuggestion chosen;
        if (issuedNow(idea)) chosen = idea;
        else {
            var stored = target.savedIdeas().stream().filter(saved -> saved.id().equals(idea.id())).findFirst();
            if (stored.isEmpty()) return Result.failureKey(savedElsewhere(idea.id(), id) ? "discover.error.otherSession" : "discover.error.unknownIdea");
            chosen = stored.get();
        }
        if (chosen.feasibility() == Feasibility.INFEASIBLE) return Result.failureKey("discover.error.infeasible");
        return Result.success(chosen.toRequirement());
    }

    /** A Draft session created for an idea together with the unconfirmed F03 draft that F03 then reviews. */
    public record NewSessionDraft(PhotographySession session, ShootRequirement draft) { }

    /**
     * select into a new Draft session (F01) named after the idea. The idea is checked before the session exists, and
     * nothing can fail after the insert, so a refused selection never leaves an empty session behind.
     */
    public Result<NewSessionDraft> selectInNewSession(ActivitySuggestion idea) {
        var session = newSession(idea);
        if (!session.isSuccess()) return Result.failure(session);
        var stored = sessions.insert(session.value().orElseThrow());
        return stored.isSuccess() ? Result.success(new NewSessionDraft(stored.value().orElseThrow(), idea.toRequirement())) : Result.failure(stored);
    }

    /** save into a new Draft session: the session and the saved idea are written by one atomic insert, or not at all. */
    public Result<PhotographySession> saveInNewSession(ActivitySuggestion idea) {
        var session = newSession(idea);
        if (!session.isSuccess()) return Result.failure(session);
        var attached = session.value().orElseThrow().attachIdea(idea.forStorage());
        return attached.isSuccess() ? sessions.insert(attached.value().orElseThrow()) : Result.failure(attached);
    }

    private Result<PhotographySession> newSession(ActivitySuggestion idea) {
        if (idea == null) return Result.failureKey("discover.error.idea");
        if (!issuedNow(idea)) return Result.failureKey("discover.error.unknownIdea");
        if (idea.feasibility() == Feasibility.INFEASIBLE) return Result.failureKey("discover.error.infeasible");
        String title = idea.subject().length() > 200 ? idea.subject().substring(0, 200) : idea.subject();
        return SessionService.draft(title, idea.start().map(start -> start.toLocalDate()).orElse(java.time.LocalDate.now()), Optional.empty(), "");
    }

    /** Stage 1 dismiss: removes the idea from the current results only; saved records are never touched. */
    public void dismiss(ActivitySuggestion idea) {
        var removed = dismiss(idea == null ? null : idea.id());
        if (!removed.isSuccess()) throw new LocalizedException(removed.messages().getFirst().key());
    }
    public synchronized Result<ActivitySuggestion> dismiss(UUID id) {
        if (id == null || latest == null || !issued.containsKey(id)) return Result.failureKey("discover.error.unknownIdea");
        var removed = issued.remove(id);
        latest = latest.without(id);
        return Result.success(removed);
    }

    /** Ideas saved in one session (F01 record), oldest first. */
    public Result<List<ActivitySuggestion>> saved(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var session = sessions.find(id);
        return session.isSuccess() ? Result.success(session.value().orElseThrow().savedIdeas()) : Result.failure(session);
    }

    private synchronized boolean issuedNow(ActivitySuggestion idea) { return issued.get(idea.id()) == idea; }
    private boolean savedElsewhere(UUID idea, UUID session) {
        var all = sessions.search("");
        return all.isSuccess() && all.value().orElseThrow().stream().anyMatch(s -> !s.id().equals(session) && s.savedIdeas().stream().anyMatch(i -> i.id().equals(idea)));
    }

    /** Untrusted reply -> structurally valid candidates (others rejected with reasons) -> validated, ranked ideas. */
    DiscoveryResult convert(DiscoveryProposal reply, DiscoveryContext context, Map<String, Equipment> labels) {
        Instant now = clock.instant();
        ZoneId zone = context.start().getZone();
        var rejected = new ArrayList<DiscoveryResult.Rejected>();
        var candidates = new ArrayList<ActivitySuggestion>();
        var proposedIds = new HashSet<String>();
        var keys = new HashSet<String>();
        var lookups = new LinkedHashMap<String, TravelEvidence>();
        var proposals =reply.suggestions() == null ? List.<DiscoveryProposal.Idea>of() : reply.suggestions();
        for (var raw : proposals) {
            if (raw == null) { rejected.add(new DiscoveryResult.Rejected("", "", List.of(LocalizedMessage.of("discover.reject.format")))); continue; }
            String label = text(raw.id()), subject = text(raw.subject());
            if (candidates.size() >= MAX_IDEAS) { rejected.add(new DiscoveryResult.Rejected(label, subject, List.of(LocalizedMessage.of("discover.reject.tooMany", MAX_IDEAS)))); continue; }
            var reasons = new ArrayList<LocalizedMessage>();
            if (!label.matches("[A-Za-z0-9_-]{1,16}")) reasons.add(LocalizedMessage.of("discover.reject.id", label));
            else if (!proposedIds.add(label)) reasons.add(LocalizedMessage.of("discover.reject.duplicateId", label));
            String genre = text(raw.genre()), setting = text(raw.setting()), type = text(raw.placeType()), destination = text(raw.destination()), region = text(raw.region());
            if (subject.isEmpty() || subject.length() > 160 || genre.length() > 60 || setting.length() > MAX_STATEMENT) reasons.add(LocalizedMessage.of("discover.reject.text"));
            boolean atLocation = type.equals("atLocation");
            if (!atLocation && !type.equals("destination")) reasons.add(LocalizedMessage.of("discover.reject.placeType", type));
            else if (!atLocation && (destination.isEmpty() || destination.length() > 120 || region.length() > 120)) reasons.add(LocalizedMessage.of("discover.reject.destination"));
            var start = FeasibilityValidator.parseProposed(raw.start(), zone);
            if (!start.isSuccess()) reasons.addAll(start.messages());
            Integer minutes = raw.durationMinutes();
            if (minutes == null || minutes < 10 || minutes > 24 * 60) reasons.add(LocalizedMessage.of("discover.reject.duration"));
            String light = text(raw.light());
            if (!ActivitySuggestion.LIGHT.contains(light)) reasons.add(LocalizedMessage.of("env.reject.lightCategory", light));
            var needs = new ArrayList<CapabilityNeed>();
            for (var need : raw.gearNeeds() == null ? List.<DiscoveryProposal.Need>of() : raw.gearNeeds()) {
                String capability = need == null ? "" : text(need.capability());
                var definition = CapabilityNeed.SUPPORTED.get(capability);
                // Required-only capabilities (autofocus, stabilization) carry no bounds; numbers sent for them mean nothing
                // and are dropped, which keeps the need itself. Every other bound is kept exactly and validated.
                boolean boundless = definition != null && !definition.hasBounds();
                try {
                    var parsed = CapabilityNeed.of(capability, Optional.ofNullable(need == null || boundless ? null : need.minimum()),
                        Optional.ofNullable(need == null || boundless ? null : need.maximum()), 1, true, "");
                    if (parsed.problems().isEmpty() && needs.stream().noneMatch(n -> n.capability().equals(capability))) needs.add(parsed);
                    else reasons.add(LocalizedMessage.of("discover.reject.gearNeed", capability));
                } catch (IllegalArgumentException invalid) { reasons.add(LocalizedMessage.of("discover.reject.gearNeed", capability)); }
            }
            var gear = new ArrayList<String>();
            for (String used : texts(raw.usesGear())) {
                var item = labels.get(used);
                if (item == null) reasons.add(LocalizedMessage.of("discover.reject.gearLabel", used));
                else if (!gear.contains(name(item))) gear.add(name(item));
            }
            if (!reasons.isEmpty()) { rejected.add(new DiscoveryResult.Rejected(label, subject, reasons)); continue; }
            String key = normal(subject) + "|" + (atLocation ? "" : normal(destination));
            if (!keys.add(key)) { rejected.add(new DiscoveryResult.Rejected(label, subject, List.of(LocalizedMessage.of("discover.reject.duplicate")))); continue; }

            var notes = new ArrayList<LocalizedMessage>();
            int[] dropped = {0};
            String rationale = named(statement(raw.rationale(), context, dropped), labels), gearNotes = named(statement(raw.gearNotes(), context, dropped), labels);
            var limitations = new ArrayList<String>();
            for (String item : texts(raw.limitations())) {
                String kept = named(statement(item, context, dropped), labels);
                if (!kept.isEmpty() && limitations.size() < MAX_NOTES) limitations.add(kept);
            }
            if (!gearNotes.isEmpty()) gear.add(gearNotes);
            if (dropped[0] > 0) notes.add(LocalizedMessage.of("discover.note.statementsDropped", dropped[0]));
            if (!atLocation) notes.add(LocalizedMessage.of("discover.note.placeUnverified", destination));
            String place = atLocation ? context.location().orElseThrow().label() : region.isEmpty() ? destination : destination + ", " + region;
            var idea = new ActivitySuggestion(UUID.randomUUID(), subject, genre, setting, place, atLocation, Optional.of(start.value().orElseThrow()),
                Duration.ofMinutes(minutes), Optional.empty(), light, gear, rationale, limitations, Feasibility.UNVERIFIED.name(), Optional.of(now))
                .withProposal(label, !Boolean.FALSE.equals(raw.outdoor()), Boolean.TRUE.equals(raw.needsClearSky()), Boolean.TRUE.equals(raw.dependsOnEvent()), needs, notes);
            candidates.add(atLocation ? idea : idea.withTravel(travel(destination, region, context, lookups)));
        }

        var validated = new ArrayList<ActivitySuggestion>();
        for (var idea : candidates) {
            var checked = validator.validate(idea, context);
            if (checked.isSuccess()) validated.add(checked.value().orElseThrow());
            else rejected.add(new DiscoveryResult.Rejected(idea.aiId(), idea.subject(), checked.messages()));
        }
        var notices = new ArrayList<>(context.notices());
        var aiOrder = new ArrayList<String>();
        var ranking = texts(reply.ranking());
        boolean rankingValid = !ranking.isEmpty() && ranking.stream().allMatch(proposedIds::contains) && new HashSet<>(ranking).size() == ranking.size();
        if (rankingValid) aiOrder.addAll(ranking); else if (!ranking.isEmpty()) notices.add(LocalizedMessage.of("discover.notice.rankingIgnored"));
        var ranked = rank(validated, context, aiOrder);
        var limits = ranked.stream().anyMatch(i -> i.feasibility() == Feasibility.FEASIBLE) ? List.<LocalizedMessage>of() : limitingFactors(ranked, rejected.size(), context);
        int[] dropped = {0};
        var missing = texts(reply.missingContext()).stream().map(s -> statement(s, context, dropped)).filter(s -> !s.isEmpty()).limit(MAX_NOTES).toList();
        var adjustments = texts(reply.adjustments()).stream().map(s -> statement(s, context, dropped)).filter(s -> !s.isEmpty()).limit(MAX_NOTES).toList();
        String summary = statement(reply.summary(), context, dropped);
        if (dropped[0] > 0) notices.add(LocalizedMessage.of("discover.notice.statementsDropped", dropped[0]));
        if (!rejected.isEmpty()) notices.add(LocalizedMessage.of("discover.notice.rejected", rejected.size()));
        return new DiscoveryResult(context, ranked, rejected, notices, limits, missing, adjustments, summary, now);
    }

    /**
     * Straight-line distances to the gazetteer matches of an AI-suggested place, narrowed by its stated region. At most
     * {@link #MAX_LOOKUPS} distinct lookups per request; repeated destinations reuse the first answer.
     */
    private TravelEvidence travel(String destination, String region, DiscoveryContext context, Map<String, TravelEvidence> cache) {
        String query = destination.split(",")[0].strip();
        if (!context.hasCoordinates() || context.maxDistanceKm() == 0) return new TravelEvidence(query, List.of(), List.of(), Optional.empty());
        String key = query.toLowerCase(Locale.ROOT) + "|" + region.toLowerCase(Locale.ROOT);
        if (!cache.containsKey(key) && cache.size() >= MAX_LOOKUPS)
            return TravelEvidence.unavailable(query, LocalizedMessage.of("discover.check.travel.lookupLimit", MAX_LOOKUPS));
        return cache.computeIfAbsent(key, ignored -> {
            var found = location().search(query);
            if (!found.isSuccess()) return TravelEvidence.unavailable(query, LocalizedMessage.of(found.hasError("env.location.error.notFound")
                ? "discover.check.travel.notFound" : "discover.check.travel.lookupFailed", destination));
            var terms = Arrays.stream(region.toLowerCase(Locale.ROOT).split(",")).map(String::strip).filter(t -> t.length() >= 2).toList();
            var matches = found.value().orElseThrow().stream().filter(c -> terms.isEmpty()
                || terms.stream().anyMatch(t -> (c.region() + " " + c.country()).toLowerCase(Locale.ROOT).contains(t))).toList();
            if (matches.isEmpty()) return TravelEvidence.unavailable(query, LocalizedMessage.of("discover.check.travel.notFound", destination));
            var origin = context.location().orElseThrow();
            double lat = origin.latitude().orElseThrow(), lon = origin.longitude().orElseThrow();
            return new TravelEvidence(query, matches.stream().map(PlaceCandidate::displayName).toList(),
                matches.stream().map(c -> FeasibilityValidator.straightLineKm(lat, lon, c.latitude(), c.longitude())).toList(), Optional.empty());
        });
    }

    /**
     * Deterministic order: status (feasible, unverified, infeasible), stated interests matched, fewer forecast
     * warnings, then Gemini's validated priority, then proposal order. No invented scores.
     */
    private static List<ActivitySuggestion> rank(List<ActivitySuggestion> ideas, DiscoveryContext context, List<String> aiOrder) {
        record Scored(ActivitySuggestion idea, List<String> interests, long warnings, int ai, int index) { }
        var scored = new ArrayList<Scored>();
        for (int i = 0; i < ideas.size(); i++) {
            var idea = ideas.get(i);
            String text = (idea.subject() + " " + idea.genre() + " " + idea.setting()).toLowerCase(Locale.ROOT);
            String genre = idea.genre().toLowerCase(Locale.ROOT);
            var matched = context.preferences().stream().filter(p -> p.length() >= 2 && (text.contains(p.toLowerCase(Locale.ROOT))
                || !genre.isBlank() && p.toLowerCase(Locale.ROOT).contains(genre))).toList();
            long warnings = idea.check("environment").map(c -> c.findings().stream().filter(f -> f.key().startsWith("discover.check.env.warn")).count()).orElse(0L);
            int ai = aiOrder.indexOf(idea.aiId());
            scored.add(new Scored(idea, matched, warnings, ai < 0 ? Integer.MAX_VALUE : ai, i));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.idea().feasibility().ordinal()).thenComparing(s -> -s.interests().size())
            .thenComparingLong(Scored::warnings).thenComparingInt(Scored::ai).thenComparingInt(Scored::index));
        var result = new ArrayList<ActivitySuggestion>();
        for (var s : scored) {
            var reasons = new ArrayList<LocalizedMessage>();
            reasons.add(LocalizedMessage.of("discover.rank.status", LocalizedMessage.of("discover.status." + s.idea().feasibility().name())));
            reasons.add(context.preferences().isEmpty() ? LocalizedMessage.of("discover.rank.noInterests")
                : s.interests().isEmpty() ? LocalizedMessage.of("discover.rank.noMatch") : LocalizedMessage.of("discover.rank.interests", String.join(", ", s.interests())));
            if (s.warnings() > 0) reasons.add(LocalizedMessage.of("discover.rank.warnings", s.warnings()));
            reasons.add(s.ai() == Integer.MAX_VALUE ? LocalizedMessage.of("discover.rank.noAi") : LocalizedMessage.of("discover.rank.ai", s.ai() + 1, aiOrder.size()));
            result.add(s.idea().withRankReasons(reasons));
        }
        return result;
    }

    /** When nothing is feasible: which constraints blocked the ideas and what the user could change (never relaxed here). */
    private static List<LocalizedMessage> limitingFactors(List<ActivitySuggestion> ideas, int rejected, DiscoveryContext context) {
        var found = new ArrayList<LocalizedMessage>();
        if (ideas.isEmpty()) { found.add(LocalizedMessage.of("discover.limit.noValid", rejected)); return found; }
        for (String criterion : ActivitySuggestion.CRITERIA) {
            long blocked = ideas.stream().filter(i -> i.check(criterion).filter(c -> c.status() == Feasibility.INFEASIBLE).isPresent()).count();
            if (blocked > 0) { found.add(LocalizedMessage.of("discover.limit." + criterion, blocked, ideas.size())); found.add(LocalizedMessage.of("discover.adjust." + criterion)); }
        }
        long unverified = ideas.stream().filter(i -> i.feasibility() == Feasibility.UNVERIFIED).count();
        if (unverified > 0) found.add(LocalizedMessage.of("discover.limit.unverified", unverified));
        if (!context.hasCoordinates()) found.add(LocalizedMessage.of("discover.adjust.coordinates"));
        if (context.gear().items().isEmpty()) found.add(LocalizedMessage.of("discover.adjust.gear"));
        return found;
    }

    /** Inventory labels G1..Gn in a stable order; Gemini never sees item identifiers. */
    static Map<String, Equipment> labels(GearInventory gear) {
        var sorted = new ArrayList<>(gear.items());
        sorted.sort(Comparator.comparing((Equipment e) -> e.type().ordinal()).thenComparing(e -> e.brand().toLowerCase(Locale.ROOT))
            .thenComparing(e -> e.model().toLowerCase(Locale.ROOT)).thenComparing(e -> e.id().toString()));
        var labels = new LinkedHashMap<String, Equipment>();
        for (int i = 0; i < sorted.size(); i++) labels.put("G" + (i + 1), sorted.get(i));
        return labels;
    }
    private static String name(Equipment item) { return item.brand() + " " + item.model(); }
    /** Inventory labels the model used in its text, shown as the owned item names (unknown labels stay as written). */
    static String named(String text, Map<String, Equipment> labels) {
        if (text.isEmpty()) return text;
        var matcher = java.util.regex.Pattern.compile("\\bG\\d{1,3}\\b").matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            var item = labels.get(matcher.group());
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(item == null ? matcher.group() : name(item)));
        }
        return matcher.appendTail(out).toString();
    }

    /**
     * Evidence for Gemini: the reviewed constraints, place label and time zone (never coordinates), inventory labels
     * and recorded specifications (no identifiers), provider sun/forecast data with sources and timestamps, and a
     * short history of confirmed subjects (no titles, notes or locations).
     */
    private String evidence(DiscoveryContext c, Map<String, Equipment> labels) {
        ZoneId zone = c.start().getZone();
        var root = new JsonObject();
        root.addProperty("today", LocalDate.ofInstant(clock.instant(), zone).toString());
        var where = new JsonObject();
        var place = c.location().orElseThrow();
        where.addProperty("label", place.label());
        where.addProperty("approximate", place.approximate());
        where.addProperty("timeZone", zone.getId());
        where.addProperty("timeZoneVerified", c.zoneSource() == ZoneSource.PROVIDER);
        where.addProperty("distanceCheckPossible", c.hasCoordinates());
        root.add("location", where);
        var period = new JsonObject();
        period.addProperty("start", local(c.start())); period.addProperty("end", local(c.end()));
        period.addProperty("minutes", c.availableTime().toMinutes()); period.addProperty("fixedUserConstraint", true);
        root.add("availability", period);
        root.addProperty("maxActivityMinutes", c.maxActivity().toMinutes());
        root.addProperty("maxTravelKmStraightLine", c.maxDistanceKm());
        root.add("interests", array(c.preferences()));
        root.add("avoid", array(c.avoid()));
        if (!c.portability().isBlank()) root.addProperty("portability", c.portability());
        if (!c.notes().isBlank()) root.addProperty("otherConstraints", c.notes());
        var items = new JsonArray();
        labels.forEach((label, item) -> {
            var entry = new JsonObject();
            entry.addProperty("label", label); entry.addProperty("type", item.type().name()); entry.addProperty("name", name(item));
            entry.addProperty("quantity", item.quantity());
            item.weightGrams().ifPresent(w -> entry.addProperty("weightGrams", w));
            var specs = new JsonObject(); item.specifications().forEach((k, v) -> { if (!v.isBlank()) specs.addProperty(k, v); });
            entry.add("specs", specs);
            items.add(entry);
        });
        root.add("inventory", items);
        root.addProperty("inventoryEmpty", labels.isEmpty());
        root.addProperty("verifiedCameraLensCombinations", c.usableLoadouts().size());
        var capabilities = new JsonObject();
        CapabilityNeed.SUPPORTED.forEach((name, definition) -> capabilities.addProperty(name, definition.unit()));
        root.add("supportedCapabilities", capabilities);
        root.add("environment", environmentEvidence(c));
        var history = new JsonArray();
        for (var session : c.history()) {
            var item = new JsonObject();
            session.requirements().filter(ShootRequirement::confirmed).ifPresent(r -> item.addProperty("subject", r.subject()));
            var ideas = new JsonArray(); session.savedIdeas().stream().limit(3).forEach(i -> ideas.add(i.subject())); item.add("savedIdeas", ideas);
            item.addProperty("date", session.date().toString()); item.addProperty("state", session.status());
            history.add(item);
        }
        root.add("history", history);
        root.addProperty("historyNote", "A small sample of saved sessions, not the user's complete interests.");
        return root.toString();
    }
    private static JsonObject environmentEvidence(DiscoveryContext c) {
        var env = new JsonObject();
        if (c.environment().isEmpty()) {
            env.addProperty("available", false);
            env.addProperty("reason", c.hasCoordinates() ? "provider data unavailable" : "no coordinates were provided, so weather and sun data were not retrieved");
            return env;
        }
        var context = c.environment().get();
        ZoneId zone = c.start().getZone();
        env.addProperty("available", true);
        var sun = new JsonArray();
        for (var event : context.sunEvents()) {
            var item = new JsonObject(); item.addProperty("kind", event.kind()); item.addProperty("time", local(event.time().atZone(zone)));
            item.addProperty("source", event.source()); sun.add(item);
        }
        env.add("sunEvents", sun);
        var phases = new JsonArray();
        for (var phase : c.lightPhases()) {
            var item = new JsonObject(); item.addProperty("kind", phase.kind()); item.addProperty("start", local(phase.start()));
            item.addProperty("end", local(phase.end())); item.addProperty("source", phase.source()); phases.add(item);
        }
        env.add("lightPhaseEstimates", phases);
        var hours = new JsonArray();
        for (var hour : context.hours(c.start().toInstant().minus(Duration.ofHours(1)), c.end().toInstant())) {
            var item = new JsonObject(); item.addProperty("time", local(hour.time().atZone(zone)));
            hour.values().forEach(item::addProperty); hours.add(item);
        }
        env.add("forecastHours", hours);
        var units = new JsonObject(); EnvironmentContext.UNITS.forEach(units::addProperty); env.add("units", units);
        env.add("unavailable", array(List.copyOf(context.unavailableFields())));
        env.add("stale", array(List.copyOf(c.staleFields())));
        env.addProperty("forecastSource", "Open-Meteo forecast (provider data, a forecast, not an observation)");
        env.addProperty("retrievedAt", context.retrievedAt().toString());
        return env;
    }
    private static JsonArray array(List<String> values) { var array = new JsonArray(); values.forEach(array::add); return array; }
    private static String local(ZonedDateTime time) { return time.toOffsetDateTime().withSecond(0).withNano(0).toString(); }

    /** AI text kept only when bounded and free of distances, travel times or measurements absent from the evidence. */
    private static String statement(String raw, DiscoveryContext context, int[] dropped) {
        String value = text(raw);
        if (value.isEmpty()) return "";
        if (value.length() > MAX_STATEMENT || !FeasibilityValidator.unverifiedFigures(value, context.environment()).isEmpty()) { dropped[0]++; return ""; }
        return value;
    }
    /** The first failure as a locale-neutral message, so a later notice renders it in the current language. */
    private static Object reason(Result<?> failed) { return failed.messages().isEmpty() ? "" : failed.messages().getFirst(); }
    private static String normal(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").strip(); }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static List<String> texts(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).distinct().toList();
    }
}
