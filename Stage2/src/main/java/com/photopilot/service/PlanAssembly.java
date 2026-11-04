package com.photopilot.service;

import java.time.*;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.CalculationTool;
import com.photopilot.command.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.model.GearMatchReport.NeedStatus;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.FeasibilityValidator;
import com.photopilot.validation.PlanValidator;

/**
 * F10 assembly between verified application data and the model: labelled verified loadout offers, the timing
 * basis, the evidence JSON (no identifiers, coordinates, titles or notes) and the conversion of untrusted model
 * output into a ShootPlan or PlanCommands. Labels the model invents, unreadable times and equipment mentioned in the
 * checklist are reported, never silently repaired into an apparently valid plan.
 */
final class PlanAssembly {
    static final int MAX_OFFERS = 6, MAX_HOURS = 30;
    static final Set<String> SPEC_KEYS = Set.of("weightGrams", "mount", "minFocalMm", "maxFocalMm", "maxAperture", "maxApertureAtMaxFocal",
        "burstFps", "resolutionMP", "stabilization", "autofocus", "autofocusModes", "sensorWidthMm", "sensorHeightMm", "capacityGB",
        "writeSpeedMBps", "batteryType", "ratedShots", "format", "diameterMm", "ndStops");

    static final String GENERATE = """
        You are the shoot-planning assistant of the PhotoPilot photography app. Build one practical shoot plan from the
        application evidence. Rules:
        1. Use only the evidence. Never invent equipment, specifications, weights, weather, sun times, measurements,
           calculation results, locations, dates or history. If something needed is missing, say so in limitations,
           and ask in questions only when it materially affects feasibility.
        2. Equipment: choose exactly one candidate loadout by its label (L1, L2, ...), or "none" when no candidate is listed.
           Candidates were already verified by deterministic compatibility rules. Prefer fully suitable candidates and
           respect every fixed constraint (camera bodies, lens changes, weight, mandatory capability needs). mounts assigns
           lenses to camera bodies using gear labels (G1, G2, ...) of the chosen candidate only. When lens changes are to be
           avoided, mount every lens. Never mention equipment that is not in the chosen candidate.
        3. Fixed constraints are final. Never relax or reinterpret them; if they cannot all be met, explain in limitations.
        4. Numbers: for depth of field, hyperfocal distance, field of view, exposure/ND, storage or battery estimates use the
           calculation tool instead of computing yourself, and quote only numbers from tool results or the calculations
           evidence. Before finishing, request DEPTH_OF_FIELD for the main telephoto focal length and aperture you recommend
           at a representative subject distance, and state that assumed distance (and any other assumption you pass to a
           tool, for example an estimated shot count) in limitations.
        5. settings: starting values or ranges with units (shutterSpeed like 1/1000-1/2000 s, aperture like f/5.6-f/8,
           iso like 100-3200, exposureCompensation like -1/3 EV, focalLength in mm). An aperture must not be wider than the
           mounted lens's maxAperture. camera is a camera gear label or "all". They are starting points to adjust on site,
           never universally optimal values.
        6. strategy: approach, technical considerations, autofocus, exposure and composition or subject tracking.
        7. schedule: only when the timing evidence has a zone and an availability. One to four shooting windows with local
           times YYYY-MM-DDTHH:MM in that zone; every window and arrival inside the availability; no overlaps. The sum of all
           window durations must not exceed timing.maxTotalWindowMinutes: choose the best part of the availability for the
           subject instead of filling it, and express arrival or setup as a window's arrival time, never as a window. Use
           light "mixed" unless the sun evidence supports the category. When environment evidence is present, cite its
           evidence ids for every window and quote only provider values. Without timing evidence return an empty schedule
           and explain why in limitations.
        8. preparation: practical preparation and precautions for the chosen equipment. checklist: only non-equipment items
           (clothing, tickets, water, hearing protection, ...); the application lists the equipment itself.
        9. shotPriorities: ordered, primary goals first, each with its trade-off.
        10. Keep it concise: at most 8 items per list, each under 300 characters. In text, name equipment by its name
            (for example "Sony A1"), never by a label such as G1 or L2.
        """;
    static final String REVISE = """
        You revise an approved PhotoPilot shoot plan (currentPlan in the application evidence) according to the user's change
        request. Revision rules:
        A. If the request is ambiguous, reply status "clarify" with up to three short questions and no changes.
        B. If it cannot be met without breaking a fixed constraint (camera bodies, lens changes, weight, mandatory capability
           needs, duration, goals), reply status "conflict", name that constraint in conflict and propose nothing weaker.
        C. Otherwise reply status "change" and list in changes only the sections that must change; every other decision stays
           exactly as in currentPlan. Give the complete new content of each listed section. A new loadout must be another
           candidate label (with mounts); never invent equipment. The DEPTH_OF_FIELD request in rule 4 below is needed only
           when the change affects lenses or apertures.
        D. summary: what changes and what stays the same.
        Every section you write follows the plan rules below.
        """ + "\n" + GENERATE.substring(GENERATE.indexOf("1. Use only the evidence"));

    /** A verified loadout offered to the model under a label, with its F07 evaluation. */
    record Offer(String label, Candidate candidate, String source) { }
    record Offers(List<Offer> offers, Map<UUID, String> gearLabels, List<LocalizedMessage> notes) {
        Optional<Offer> byLabel(String label) { return offers.stream().filter(o -> o.label().equalsIgnoreCase(Objects.toString(label, "").strip())).findFirst(); }
        Optional<Offer> byLoadout(UUID id) { return offers.stream().filter(o -> o.candidate().loadout().id().equals(id)).findFirst(); }
        Optional<UUID> gear(String label) {
            String wanted = Objects.toString(label, "").strip();
            return gearLabels.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(wanted)).map(Map.Entry::getKey).findFirst();
        }
        /** Text with the application's own gear labels (G1, G2, ...) replaced by the equipment names they stand for. */
        String named(String text) {
            var names = new HashMap<UUID, String>();
            offers.forEach(o -> o.candidate().loadout().gear().forEach(g -> names.put(g.sourceGearId(), CompatibilityValidator.name(g))));
            return java.util.regex.Pattern.compile("\\bG(\\d+)\\b").matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                gear(m.group()).map(names::get).orElse(m.group())));
        }
        List<String> named(List<String> texts) { return texts.stream().map(this::named).toList(); }
    }
    /** When the plan may schedule windows, and against which evidence. */
    record Timing(Optional<EnvironmentAssessment> environment, Optional<Availability> availability, Optional<ZoneId> zone, LocalDate date,
            Set<String> stale, String source, List<LocalizedMessage> notes) { }
    /** Converted plan plus conversion problems (blocking) and application notes (shown and recorded as limitations). */
    record Built(ShootPlan plan, List<LocalizedMessage> problems, List<LocalizedMessage> notes) { }

    private final GearMatchingService matching;
    private final TechnicalCalculator calculator;
    PlanAssembly(GearMatchingService matching, TechnicalCalculator calculator) { this.matching = matching; this.calculator = calculator; }

    /** F07 selection first (if still current), then the base plan's loadout, then the best verified candidates that meet the requirement's hard limits. */
    Offers offers(PhotographySession session, ShootRequirement req, EquipmentRequirementProfile profile, GearInventory gear, Optional<ShootPlan> base) {
        var notes = new ArrayList<LocalizedMessage>();
        var chosen = new LinkedHashMap<UUID, Offer>();
        session.selectedLoadout().ifPresent(selected -> {
            if (!matching.validator().availability(selected, gear).isEmpty() || !matching.validator().assess(selected).usable()) notes.add(LocalizedMessage.of("plan.note.selectionStale"));
            else if (!meetsLimits(selected, req)) notes.add(LocalizedMessage.of("plan.note.selectionLimits"));
            else put(chosen, matching.evaluate(selected, profile), "F07 selection");
        });
        base.flatMap(ShootPlan::loadout).ifPresent(current -> {
            if (matching.validator().availability(current, gear).isEmpty() && matching.validator().assess(current).usable() && meetsLimits(current, req))
                put(chosen, matching.evaluate(current, profile), "current plan");
        });
        var generated = matching.compatibleCandidates(gear);
        if (!generated.isSuccess()) notes.addAll(generated.messages());
        else {
            var ranked = generated.value().orElseThrow().stream().filter(l -> meetsLimits(l, req)).map(l -> matching.evaluate(l, profile))
                .sorted(Comparator.comparing((Candidate c) -> !c.fullySuitable()).thenComparingLong(Candidate::mandatoryUnmet)
                    .thenComparingDouble(c -> c.loadout().totalWeightGrams().orElse(Double.MAX_VALUE)).thenComparing(c -> c.loadout().id().toString()))
                .toList();
            for (var candidate : ranked) { if (chosen.size() >= MAX_OFFERS) break; put(chosen, candidate, "verified alternative"); }
            if (ranked.isEmpty()) notes.add(LocalizedMessage.of("plan.note.noCandidate"));
        }
        var offers = new ArrayList<Offer>(); int index = 0;
        for (var offer : chosen.values()) offers.add(new Offer("L" + (++index), offer.candidate(), offer.source()));
        var labels = new LinkedHashMap<UUID, String>();
        for (var offer : offers) for (var item : offer.candidate().loadout().gear()) labels.putIfAbsent(item.sourceGearId(), "G" + (labels.size() + 1));
        return new Offers(offers, labels, notes);
    }
    private static void put(Map<UUID, Offer> chosen, Candidate candidate, String source) { chosen.putIfAbsent(candidate.loadout().id(), new Offer("", candidate, source)); }
    /** Requirement hard limits a candidate must meet to be offered at all: bodies, lens changes, weight. */
    static boolean meetsLimits(Loadout loadout, ShootRequirement req) {
        int bodies = loadout.units(EquipmentType.CAMERA), lenses = loadout.units(EquipmentType.LENS);
        if (req.cameraCount().filter(n -> n != bodies).isPresent()) return false;
        if (req.lensChangesAllowed().filter(allowed -> !allowed).isPresent() && lenses > bodies) return false;
        return req.maxWeightGrams().map(limit -> loadout.totalWeightGrams().filter(w -> w <= limit).isPresent()).orElse(true);
    }

    /** F09 assessment for the plan date (fresh groups only), otherwise the requirement's own start time and duration. */
    Timing timing(PhotographySession session, ShootRequirement req, Optional<EnvironmentAssessment> assessment, Set<String> stale) {
        LocalDate date = req.date().orElse(session.date());
        var notes = new ArrayList<LocalizedMessage>();
        var usable = assessment;
        if (usable.isPresent()) {
            var assessed = usable.get();
            var envDate = assessed.request().map(EnvironmentRequest::date).or(() -> assessed.context().date());
            if (envDate.isPresent() && !envDate.get().equals(date)) { notes.add(LocalizedMessage.of("plan.env.dateMismatch", envDate.get().toString(), date.toString())); usable = Optional.empty(); }
            else if (assessed.context().zone().isEmpty() || assessed.availability().isEmpty()) { notes.add(LocalizedMessage.of("plan.env.zoneUnverified")); usable = Optional.empty(); }
            else {
                String place = assessed.context().place().map(GeoLocation::label).orElse("");
                notes.add(LocalizedMessage.of("plan.env.used", place, assessed.context().retrievedAt().toString()));
                if (!stale.isEmpty()) notes.add(LocalizedMessage.of("plan.env.stale", String.join(", ", new TreeSet<>(stale))));
            }
        }
        if (usable.isPresent()) {
            var assessed = usable.get();
            return new Timing(usable, assessed.availability(), assessed.context().zone(), date, Set.copyOf(stale), "F09", notes);
        }
        if (req.time().isPresent() && req.duration().isPresent()) {
            var start = req.time().get();
            notes.add(LocalizedMessage.of("plan.env.requirementTiming"));
            return new Timing(Optional.empty(), Optional.of(new Availability(start, start.plus(req.duration().get()))), Optional.of(start.getZone()), date, Set.of(), "requirement", notes);
        }
        notes.add(LocalizedMessage.of("plan.env.noTiming"));
        return new Timing(Optional.empty(), Optional.empty(), Optional.empty(), date, Set.of(), "none", notes);
    }

    /** Application evidence for the model. Locations travel as labels only; identifiers stay local. */
    String evidence(ShootRequirement req, EquipmentRequirementProfile profile, Offers offers, Timing timing, Optional<ShootPlan> current) {
        var root = new JsonObject();
        root.addProperty("today", LocalDate.now(timing.zone().orElse(ZoneOffset.UTC)).toString());
        var requirement = new JsonObject();
        requirement.addProperty("subject", req.subject());
        requirement.add("goals", strings(req.goals()));
        requirement.addProperty("date", timing.date().toString());
        req.time().ifPresent(t -> requirement.addProperty("startTime", t.toLocalDateTime().toString() + " (" + t.getZone() + ")"));
        if (!req.timeWindow().isBlank()) requirement.addProperty("timeWindow", req.timeWindow());
        req.location().ifPresent(place -> requirement.addProperty("location", place.label()));
        req.duration().ifPresent(d -> requirement.addProperty("durationMinutes", d.toMinutes()));
        req.cameraCount().ifPresent(n -> requirement.addProperty("cameraBodies", n));
        req.lensChangesAllowed().ifPresent(allowed -> requirement.addProperty("lensChanges", allowed ? "allowed" : "avoid"));
        req.maxWeightGrams().ifPresent(w -> requirement.addProperty("maxWeightGrams", w));
        if (!req.portability().isBlank()) requirement.addProperty("portability", req.portability());
        requirement.add("gearConstraints", strings(req.gearConstraints()));
        var priorities = new JsonObject(); req.priorities().forEach(priorities::addProperty); requirement.add("priorities", priorities);
        root.add("requirement", requirement);
        var fixed = new JsonObject(); PlanValidator.constraintsOf(req, Optional.ofNullable(profile)).forEach(fixed::addProperty); root.add("fixedConstraints", fixed);
        var needs = new JsonArray();
        for (var need : profile == null ? List.<CapabilityNeed>of() : profile.needs()) {
            var item = new JsonObject(); item.addProperty("capability", need.capability()); item.addProperty("unit", need.unit());
            need.minimum().ifPresent(v -> item.addProperty("minimum", v)); need.maximum().ifPresent(v -> item.addProperty("maximum", v));
            item.addProperty("priority", need.priority()); item.addProperty("mandatory", need.hardConstraint()); needs.add(item);
        }
        root.add("capabilityNeeds", needs);
        var list = new JsonArray();
        for (var offer : offers.offers()) list.add(offerJson(offer, offers));
        root.add("candidates", list);
        if (offers.offers().isEmpty()) root.addProperty("candidatesNote", "No verified owned loadout meets the confirmed limits.");
        var timingItem = timingJson(timing);
        req.duration().ifPresent(d -> timingItem.addProperty("maxTotalWindowMinutes", d.toMinutes()));
        root.add("timing", timingItem);
        timing.environment().ifPresent(e -> root.add("environment", environmentJson(e, timing)));
        current.ifPresent(plan -> root.add("currentPlan", planJson(plan, offers)));
        return root.toString();
    }
    private JsonObject offerJson(Offer offer, Offers offers) {
        var candidate = offer.candidate(); var loadout = candidate.loadout(); var item = new JsonObject();
        item.addProperty("label", offer.label()); item.addProperty("source", offer.source()); item.addProperty("fullySuitable", candidate.fullySuitable());
        loadout.totalWeightGrams().ifPresentOrElse(w -> item.addProperty("totalWeightGrams", w), () -> item.addProperty("totalWeightGrams", "unknown"));
        item.addProperty("cameraBodies", loadout.units(EquipmentType.CAMERA)); item.addProperty("lensUnits", loadout.units(EquipmentType.LENS));
        item.addProperty("focalCoverage", String.join("; ", CompatibilityValidator.focalCoverage(loadout).stream().map(CompatibilityValidator::range).toList()));
        var unmet = new JsonArray();
        CompatibilityValidator.english(candidate.needs().stream().filter(n -> n.status() != NeedStatus.MET).map(GearMatchReport.NeedResult::detail).toList()).forEach(unmet::add);
        CompatibilityValidator.english(candidate.essentials()).forEach(unmet::add);
        item.add("unmetNeeds", unmet);
        var gear = new JsonArray();
        for (var snapshot : loadout.gear()) {
            var g = new JsonObject(); g.addProperty("label", offers.gearLabels().get(snapshot.sourceGearId())); g.addProperty("type", snapshot.type());
            g.addProperty("name", CompatibilityValidator.name(snapshot)); g.addProperty("quantity", snapshot.selectedQuantity());
            var specs = new JsonObject(); new TreeMap<>(snapshot.specs()).forEach((k, v) -> { if (SPEC_KEYS.contains(k)) specs.addProperty(k, v); });
            g.add("specs", specs); gear.add(g);
        }
        item.add("gear", gear);
        var mounts = new JsonArray();
        new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) -> {
            var m = new JsonObject(); m.addProperty("camera", offers.gearLabels().get(camera)); m.addProperty("lens", offers.gearLabels().get(lens)); mounts.add(m);
        });
        item.add("defaultMounts", mounts);
        var calculations = new JsonArray(); baseline(loadout).forEach(c -> calculations.add(calculationJson(c)));
        item.add("calculations", calculations);
        return item;
    }
    private static JsonObject timingJson(Timing timing) {
        var item = new JsonObject(); item.addProperty("source", timing.source()); item.addProperty("date", timing.date().toString());
        timing.zone().ifPresent(z -> item.addProperty("timeZone", z.getId()));
        timing.availability().ifPresent(a -> {
            item.addProperty("availableFrom", a.start().toLocalDateTime().toString()); item.addProperty("availableUntil", a.end().toLocalDateTime().toString());
        });
        if (timing.availability().isEmpty()) item.addProperty("note", "No verified time zone and availability: return an empty schedule.");
        return item;
    }
    private static JsonObject environmentJson(EnvironmentAssessment assessment, Timing timing) {
        var context = assessment.context(); var zone = context.zone().orElse(ZoneOffset.UTC); var item = new JsonObject();
        item.addProperty("place", context.place().map(GeoLocation::label).orElse("")); item.addProperty("timeZone", zone.getId());
        item.addProperty("retrievedAt", context.retrievedAt().toString());
        boolean forecastStale = timing.stale().contains(EnvironmentContext.DAILY) || timing.stale().contains(EnvironmentContext.HOURLY);
        boolean sunStale = timing.stale().contains(EnvironmentContext.SUN);
        var ids = new JsonArray();
        if (!forecastStale && !context.weather().isEmpty()) {
            var daily = new JsonObject(); context.weather().forEach(daily::addProperty); item.add("daily", daily); ids.add("daily");
        }
        if (!forecastStale) {
            var hours = new JsonArray(); var available = assessment.availability().orElseThrow();
            for (var hour : context.hours(available.start().minusHours(1).toInstant(), available.end().plusHours(1).toInstant())) {
                if (hours.size() >= MAX_HOURS) break;
                var h = new JsonObject(); String id = EnvironmentAssessment.hourId(hour.time().atZone(zone)); h.addProperty("id", id);
                hour.values().forEach(h::addProperty); hours.add(h); ids.add(id);
            }
            item.add("hourly", hours);
        }
        if (!sunStale) {
            var sun = new JsonArray();
            var index = EnvironmentAssessment.evidenceIndex(context, assessment.lightPhases());
            index.forEach((id, value) -> {
                if (value instanceof EnvironmentContext.SunEvent event) {
                    var s = new JsonObject(); s.addProperty("id", id); s.addProperty("kind", event.kind()); s.addProperty("time", event.time().atZone(zone).toLocalDateTime().toString()); sun.add(s); ids.add(id);
                } else if (value instanceof EnvironmentAssessment.LightPhase phase) {
                    var s = new JsonObject(); s.addProperty("id", id); s.addProperty("kind", phase.kind() + " (computed estimate)");
                    s.addProperty("start", phase.start().toLocalDateTime().toString()); s.addProperty("end", phase.end().toLocalDateTime().toString()); sun.add(s); ids.add(id);
                }
            });
            item.add("sun", sun);
        }
        var f09 = new JsonArray();
        for (var window : assessment.windows()) {
            var w = new JsonObject(); w.addProperty("label", window.label()); w.addProperty("start", window.start().toLocalDateTime().toString());
            w.addProperty("end", window.end().toLocalDateTime().toString()); w.addProperty("light", window.light()); w.add("evidence", strings(window.evidence())); f09.add(w);
        }
        item.add("validatedF09Windows", f09);
        item.add("evidenceIds", ids);
        item.add("unavailable", strings(new ArrayList<>(context.unavailableFields())));
        item.add("staleOmitted", strings(new ArrayList<>(new TreeSet<>(timing.stale()))));
        return item;
    }
    private static JsonObject planJson(ShootPlan plan, Offers offers) {
        var item = new JsonObject(); item.addProperty("revision", plan.revision()); item.addProperty("summary", plan.summary());
        plan.loadout().flatMap(l -> offers.byLoadout(l.id())).ifPresent(o -> item.addProperty("loadout", o.label()));
        plan.loadout().ifPresent(l -> {
            var mounts = new JsonArray();
            new TreeMap<>(l.cameraLensAssignments()).forEach((c, lens) -> { var m = new JsonObject(); m.addProperty("camera", offers.gearLabels().get(c)); m.addProperty("lens", offers.gearLabels().get(lens)); mounts.add(m); });
            item.add("mounts", mounts);
        });
        var settings = new JsonArray();
        new TreeMap<>(plan.settings()).forEach((key, value) -> {
            var s = new JsonObject(); s.addProperty("setting", ShootPlan.settingName(key)); s.addProperty("value", value);
            s.addProperty("camera", ShootPlan.settingCamera(key).map(id -> offers.gearLabels().getOrDefault(id, "all")).orElse("all")); settings.add(s);
        });
        item.add("settings", settings);
        var strategy = new JsonObject(); new TreeMap<>(plan.strategy()).forEach(strategy::addProperty); item.add("strategy", strategy);
        var schedule = new JsonArray();
        for (var w : plan.windows()) {
            var s = new JsonObject(); s.addProperty("start", w.start().toLocalDateTime().toString()); s.addProperty("end", w.end().toLocalDateTime().toString());
            s.addProperty("arrival", w.arrival().toLocalDateTime().toString()); s.addProperty("conditions", w.conditions()); schedule.add(s);
        }
        item.add("schedule", schedule);
        item.add("preparation", strings(plan.preparation())); item.add("checklist", strings(plan.checklist()));
        item.add("shotPriorities", strings(plan.shotPriorities())); item.add("tradeOffs", strings(plan.tradeOffs()));
        return item;
    }

    /** Converts a generation reply. Unknown labels and unreadable windows become blocking problems. */
    Built plan(PlanGeneration out, Offers offers, Timing timing, PhotographySession session, ShootRequirement req, EquipmentRequirementProfile profile,
            UUID id, int revision, List<AgentContext.ToolExchange> tools) {
        var problems = new ArrayList<LocalizedMessage>(); var notes = new ArrayList<LocalizedMessage>(offers.notes()); notes.addAll(timing.notes());
        Optional<Loadout> loadout = Optional.empty();
        var equipmentNotes = new ArrayList<String>(offers.named(texts(out.equipmentNotes())));
        String label = Objects.toString(out.loadout(), "").strip();
        if (!label.isEmpty() && !label.equalsIgnoreCase("none")) {
            var offer = offers.byLabel(label);
            if (offer.isEmpty()) problems.add(LocalizedMessage.of("plan.problem.unknownCandidate", label));
            else loadout = mounted(offer.get(), out.mounts(), offers, profile, problems, equipmentNotes);
        } else if (!offers.offers().isEmpty()) notes.add(LocalizedMessage.of("plan.note.noneChosen"));
        var provisional = new ArrayList<String>();
        if (loadout.isEmpty()) provisional.add(EnglishMessages.text("plan.provisional.noLoadout"));
        var settings = settings(out.settings(), loadout, offers, problems);
        var windows = windows(out.schedule(), timing, problems);
        var checklist = checklist(out.checklist(), notes);
        var priorities = new ArrayList<String>(); var tradeOffs = new ArrayList<String>();
        priorities(out.shotPriorities(), priorities, tradeOffs);
        var limitations = new ArrayList<String>(offers.named(texts(out.limitations())));
        notes.forEach(note -> limitations.add(EnglishMessages.text(note.key(), note.arguments().toArray())));
        var strategy = new LinkedHashMap<String, String>(); strategy(out.strategy()).forEach((k, v) -> strategy.put(k, offers.named(v)));
        var plan = new ShootPlan(id, revision, false, provisional, loadout, settings, windows, checklist, offers.named(texts(out.preparation())),
            offers.named(priorities), PlanValidator.constraintsOf(req, Optional.ofNullable(profile)), calculations(loadout, tools),
            offers.named(Objects.toString(out.summary(), "").strip()), sessionInfo(session, req, timing), strategy, equipmentNotes, offers.named(tradeOffs), limitations, "");
        return new Built(plan, problems, notes);
    }

    /** Converts a change interpretation into commands for the named sections only. */
    Result<List<PlanCommand>> commands(PlanChangeInterpretation change, ShootPlan base, Offers offers, Timing timing, EquipmentRequirementProfile profile) {
        var sections = new LinkedHashSet<String>();
        for (String section : change.changes() == null ? List.<String>of() : change.changes()) {
            if (!PlanChangeInterpretation.SECTIONS.contains(section)) return Result.failureKey("plan.problem.section", section);
            sections.add(section);
        }
        if (sections.isEmpty()) return Result.failureKey("plan.error.noChange");
        var problems = new ArrayList<LocalizedMessage>(); var commands = new ArrayList<PlanCommand>();
        Optional<Loadout> loadout = base.loadout();
        if (sections.contains("loadout")) {
            var offer = offers.byLabel(change.loadout());
            if (offer.isEmpty()) problems.add(LocalizedMessage.of("plan.problem.unknownCandidate", Objects.toString(change.loadout(), "")));
            else {
                var notes = new ArrayList<String>(offers.named(texts(change.equipmentNotes())));
                loadout = mounted(offer.get(), change.mounts(), offers, profile, problems, notes);
                loadout.ifPresent(l -> commands.add(new ReplaceLoadoutCommand(l, notes)));
            }
        }
        if (sections.contains("schedule")) commands.add(new ReviseScheduleCommand(windows(change.schedule(), timing, problems)));
        var strategySections = new LinkedHashSet<String>();
        for (String section : List.of("settings", "strategy", "shotPriorities", "preparation", "checklist")) if (sections.contains(section)) strategySections.add(section);
        if (!strategySections.isEmpty()) {
            var priorities = new ArrayList<String>(); var tradeOffs = new ArrayList<String>();
            priorities(change.shotPriorities(), priorities, tradeOffs);
            if (strategySections.contains("shotPriorities")) strategySections.add("tradeOffs");
            var dropped = new ArrayList<LocalizedMessage>();
            var checklist = checklist(change.checklist(), dropped);
            problems.addAll(dropped.stream().map(m -> LocalizedMessage.of("plan.violation.checklistGear", m.arguments().getFirst())).toList());
            var strategy = new LinkedHashMap<String, String>(); strategy(change.strategy()).forEach((k, v) -> strategy.put(k, offers.named(v)));
            commands.add(new ReviseStrategyCommand(settings(change.settings(), loadout, offers, problems), strategy, offers.named(priorities), offers.named(tradeOffs),
                offers.named(texts(change.preparation())), checklist, strategySections));
        }
        return problems.isEmpty() ? Result.success(commands) : Result.failures(problems);
    }

    /** The offered loadout with the model's mount assignments; assignments must pair this candidate's own bodies and lenses. */
    private Optional<Loadout> mounted(Offer offer, List<PlanGeneration.Mount> mounts, Offers offers, EquipmentRequirementProfile profile,
            List<LocalizedMessage> problems, List<String> equipmentNotes) {
        var loadout = offer.candidate().loadout();
        if (mounts == null || mounts.isEmpty()) return Optional.of(loadout);
        var byId = new HashMap<UUID, GearSnapshot>(); loadout.gear().forEach(g -> byId.put(g.sourceGearId(), g));
        var assignments = new LinkedHashMap<UUID, UUID>();
        for (var mount : mounts) {
            if (mount == null) continue;
            var camera = offers.gear(mount.camera()).map(byId::get).filter(g -> g.type().equals("CAMERA"));
            var lens = offers.gear(mount.lens()).map(byId::get).filter(g -> g.type().equals("LENS"));
            if (camera.isEmpty() || lens.isEmpty()) { problems.add(LocalizedMessage.of("plan.problem.mount", Objects.toString(mount.camera(), ""), Objects.toString(mount.lens(), ""))); continue; }
            if (assignments.putIfAbsent(camera.get().sourceGearId(), lens.get().sourceGearId()) != null) { problems.add(LocalizedMessage.of("plan.problem.mountTwice", CompatibilityValidator.name(camera.get()))); continue; }
            String role = Objects.toString(mount.role(), "").strip();
            if (!role.isEmpty()) equipmentNotes.add(CompatibilityValidator.name(lens.get()) + " on " + CompatibilityValidator.name(camera.get()) + ": " + role);
        }
        var custom = new Loadout(loadout.id(), loadout.gear(), assignments, loadout.compatibility(), loadout.tradeOffs(), loadout.unmetNeeds());
        return Optional.of(matching.evaluate(custom, profile).loadout());
    }
    private static Map<String, String> settings(List<PlanGeneration.Setting> proposed, Optional<Loadout> loadout, Offers offers, List<LocalizedMessage> problems) {
        var settings = new LinkedHashMap<String, String>();
        var cameras = new HashSet<UUID>(); loadout.ifPresent(l -> l.ofType(EquipmentType.CAMERA).forEach(c -> cameras.add(c.sourceGearId())));
        for (var setting : proposed == null ? List.<PlanGeneration.Setting>of() : proposed) {
            if (setting == null) continue;
            String name = Objects.toString(setting.setting(), "").strip(), camera = Objects.toString(setting.camera(), "").strip();
            if (!ShootPlan.SETTINGS.contains(name)) { problems.add(LocalizedMessage.of("plan.violation.settingName", name)); continue; }
            Optional<UUID> body = Optional.empty();
            if (!camera.isEmpty() && !camera.equalsIgnoreCase("all")) {
                body = offers.gear(camera).filter(cameras::contains);
                if (body.isEmpty()) { problems.add(LocalizedMessage.of("plan.violation.settingCamera", name)); continue; }
            }
            if (settings.putIfAbsent(ShootPlan.settingKey(name, body), Objects.toString(setting.value(), "").strip()) != null)
                problems.add(LocalizedMessage.of("plan.problem.duplicateSetting", name));
        }
        return settings;
    }
    private static List<ShootingWindow> windows(List<PlanGeneration.Window> proposed, Timing timing, List<LocalizedMessage> problems) {
        var windows = new ArrayList<ShootingWindow>();
        if (proposed == null || proposed.isEmpty()) return windows;
        if (timing.zone().isEmpty()) { problems.add(LocalizedMessage.of("plan.violation.noTiming")); return windows; }
        ZoneId zone = timing.zone().get();
        for (var w : proposed) {
            if (w == null) continue;
            String label = Objects.toString(w.label(), "").strip();
            var start = FeasibilityValidator.parseProposed(w.start(), zone); var end = FeasibilityValidator.parseProposed(w.end(), zone);
            var arrival = Objects.toString(w.arrival(), "").isBlank() ? start : FeasibilityValidator.parseProposed(w.arrival(), zone);
            for (var parsed : List.of(start, end, arrival)) if (!parsed.isSuccess()) parsed.messages().forEach(m -> problems.add(LocalizedMessage.of("plan.violation.window", label, m)));
            if (!start.isSuccess() || !end.isSuccess() || !arrival.isSuccess()) continue;
            String light = Objects.toString(w.light(), "mixed").strip(), conditions = Objects.toString(w.conditions(), "").strip();
            boolean estimated = timing.environment().isEmpty() || light.equals("goldenHour") || light.equals("blueHour");
            try {
                windows.add(new ShootingWindow(start.value().orElseThrow(), end.value().orElseThrow(), arrival.value().orElseThrow(),
                    label.isEmpty() ? conditions : conditions.isEmpty() ? label : label + ": " + conditions, estimated, label, light, "",
                    w.evidence() == null ? List.of() : w.evidence().stream().filter(Objects::nonNull).map(String::strip).toList(), List.of()));
            } catch (IllegalArgumentException chronology) { problems.add(LocalizedMessage.of("plan.violation.window", label, LocalizedMessage.of("env.reject.order"))); }
        }
        return windows;
    }
    /** Equipment lines belong to the loadout; checklist items that name equipment are left out and reported. */
    private static List<String> checklist(List<String> proposed, List<LocalizedMessage> notes) {
        var kept = new ArrayList<String>();
        for (String item : texts(proposed)) {
            if (PlanValidator.gearMentions(item).isEmpty()) kept.add(item);
            else notes.add(LocalizedMessage.of("plan.note.checklistRemoved", item));
        }
        return kept;
    }
    private static void priorities(List<PlanGeneration.Priority> proposed, List<String> priorities, List<String> tradeOffs) {
        if (proposed == null) return;
        var ordered = new ArrayList<PlanGeneration.Priority>();
        proposed.stream().filter(Objects::nonNull).filter(p -> "primary".equals(p.priority())).forEach(ordered::add);
        proposed.stream().filter(Objects::nonNull).filter(p -> !"primary".equals(p.priority())).forEach(ordered::add);
        for (var p : ordered) {
            String description = Objects.toString(p.description(), "").strip();
            if (description.isEmpty()) continue;
            priorities.add(("primary".equals(p.priority()) ? "primary" : "secondary") + ": " + description);
            String trade = Objects.toString(p.tradeOff(), "").strip();
            if (!trade.isEmpty()) tradeOffs.add(description + " - " + trade);
        }
    }
    private static Map<String, String> strategy(PlanGeneration.Strategy strategy) {
        var map = new LinkedHashMap<String, String>();
        if (strategy == null) return map;
        put(map, "approach", strategy.approach()); put(map, "technical", strategy.technical()); put(map, "autofocus", strategy.autofocus());
        put(map, "exposure", strategy.exposure()); put(map, "composition", strategy.composition());
        return map;
    }
    private static void put(Map<String, String> map, String key, String value) { if (value != null && !value.isBlank()) map.put(key, value.strip()); }
    private static List<String> texts(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }

    /** Session facts copied deterministically from the confirmed requirement and session, never from the model. */
    static Map<String, String> sessionInfo(PhotographySession session, ShootRequirement req, Timing timing) {
        var info = new LinkedHashMap<String, String>();
        info.put("subject", req.subject());
        if (!req.goals().isEmpty()) info.put("goals", String.join("; ", req.goals()));
        var place = req.location().map(GeoLocation::label).or(() -> timing.environment().flatMap(e -> e.context().place()).map(GeoLocation::label))
            .or(() -> session.location().map(GeoLocation::label));
        place.ifPresent(label -> info.put("location", label));
        info.put("date", timing.date().toString());
        var time = new ArrayList<String>();
        req.time().ifPresent(t -> time.add(t.toLocalTime() + " " + t.getZone()));
        if (!req.timeWindow().isBlank()) time.add(req.timeWindow());
        timing.availability().ifPresent(a -> time.add(a.start().toLocalTime() + "-" + a.end().toLocalTime() + " " + a.start().getZone()));
        if (!time.isEmpty()) info.put("time", String.join("; ", time));
        req.duration().ifPresent(d -> info.put("duration", d.toMinutes() + " min"));
        var constraints = new ArrayList<String>();
        req.cameraCount().ifPresent(n -> constraints.add(n + " camera bodies"));
        req.lensChangesAllowed().ifPresent(allowed -> constraints.add(allowed ? "lens changes allowed" : "avoid lens changes"));
        req.maxWeightGrams().ifPresent(w -> constraints.add("max " + CompatibilityValidator.num(w) + " g"));
        if (!req.portability().isBlank()) constraints.add(req.portability());
        constraints.addAll(req.gearConstraints());
        req.fixedConstraints().forEach((k, v) -> constraints.add(k + ": " + v));
        if (!constraints.isEmpty()) info.put("constraints", String.join("; ", constraints));
        return info;
    }

    /** Field of view of each mounted lens (wide and long end) on its body, from recorded sensor and focal data only. */
    List<CalculationResult> baseline(Loadout loadout) {
        var results = new ArrayList<CalculationResult>();
        var byId = new HashMap<UUID, GearSnapshot>(); loadout.gear().forEach(g -> byId.put(g.sourceGearId(), g));
        new TreeMap<>(loadout.cameraLensAssignments()).forEach((cameraId, lensId) -> {
            var camera = byId.get(cameraId); var lens = byId.get(lensId);
            var width = number(camera, "sensorWidthMm"); var height = number(camera, "sensorHeightMm");
            if (width.isEmpty() || height.isEmpty()) return;
            var focals = new LinkedHashSet<Double>(); number(lens, "minFocalMm").ifPresent(focals::add); number(lens, "maxFocalMm").ifPresent(focals::add);
            for (double focal : focals) {
                var input = new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", focal, "sensorWidth", width.get(), "sensorHeight", height.get()),
                    Map.of("focalLength", "mm", "sensorWidth", "mm", "sensorHeight", "mm"));
                calculator.calculate(input).value().ifPresent(results::add);
            }
        });
        return results;
    }
    /** Baseline for the chosen loadout plus every successful calculation-tool result, recomputed by F08 (never copied from AI text). */
    List<CalculationResult> calculations(Optional<Loadout> loadout, List<AgentContext.ToolExchange> tools) {
        var results = new ArrayList<CalculationResult>(loadout.map(this::baseline).orElse(List.of()));
        var seen = new HashSet<String>(); results.forEach(r -> seen.add(key(r.input())));
        for (var exchange : tools) {
            if (!exchange.succeeded() || !exchange.tool().equals("calculation")) continue;
            var input = CalculationTool.input(exchange.arguments());
            if (!input.isSuccess() || !seen.add(key(input.value().orElseThrow()))) continue;
            calculator.calculate(input.value().orElseThrow()).value().ifPresent(results::add);
        }
        return results;
    }
    private static String key(CalculationInput input) { return input.operation() + new TreeMap<>(input.parameters()).toString() + new TreeMap<>(input.units()); }
    private static JsonObject calculationJson(CalculationResult result) {
        var item = new JsonObject(); item.addProperty("operation", result.input().operation().name());
        var inputs = new JsonObject(); new TreeMap<>(result.input().parameters()).forEach((k, v) -> inputs.addProperty(k, v + " " + result.input().units().getOrDefault(k, ""))); item.add("inputs", inputs);
        var values = new JsonObject(); new TreeMap<>(result.values()).forEach((k, v) -> values.addProperty(k, (v.isInfinite() ? "Infinity" : CompatibilityValidator.num(v)) + " " + result.units().getOrDefault(k, "")));
        item.add("values", values); item.addProperty("estimated", result.estimated());
        return item;
    }
    private static Optional<Double> number(GearSnapshot item, String spec) {
        try {
            double value = Double.parseDouble(Objects.toString(item == null ? null : item.specs().get(spec), "").strip());
            return Double.isFinite(value) && value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException missing) { return Optional.empty(); }
    }
    private static JsonArray strings(List<String> values) { var array = new JsonArray(); values.forEach(array::add); return array; }
}
