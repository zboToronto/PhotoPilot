package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.validation.CompatibilityValidator;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared localized F02 text for GUI cards and CLI output. Deterministic findings, provider facts and AI text are
 * labelled separately; feasible, unverified and infeasible ideas are never presented alike.
 */
public final class DiscoveryPresentation {
    private DiscoveryPresentation() { }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** Inventory review before a request: what PhotoPilot will treat as owned equipment (F05, read only). */
    public static List<String> gear(GearInventory inventory, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (inventory.items().isEmpty()) { lines.add(l.text("discover.gear.empty")); return lines; }
        lines.add(l.text("discover.gear.count", inventory.items().size()));
        for (var item : inventory.items())
            lines.add("  " + l.text("discover.gear.item", l.text("gear.type." + item.type().name()), item.brand() + " " + item.model(), item.quantity()));
        lines.add(l.text("discover.gear.readOnly"));
        return lines;
    }

    /** The context PhotoPilot actually used, with sources, timestamps and gaps. */
    public static List<String> context(DiscoveryContext c, LocalizationService l) { return context(c, l, false); }

    /**
     * The same context as the Guided Discovery views show it: line for line {@link #context}, except that the inventory
     * is counted in records (the unit the equipment summary uses) and missing provider fields are named by their labels
     * ({@link #gaps}). The legacy {@code discover} output keeps {@link #context}.
     */
    public static List<String> guidedContext(DiscoveryContext c, LocalizationService l) { return context(c, l, true); }

    private static List<String> context(DiscoveryContext c, LocalizationService l, boolean guided) {
        var lines = new ArrayList<String>();
        var place = c.location().orElseThrow();
        lines.add(c.hasCoordinates()
            ? l.text(place.approximate() ? "discover.context.placeApproximate" : "discover.context.place", place.label(),
                EnvironmentForm.coordinate(place.latitude().orElseThrow()), EnvironmentForm.coordinate(place.longitude().orElseThrow()))
            : l.text("discover.context.placeLabel", place.label()));
        lines.add(l.text("discover.context.zone." + c.zoneSource().name(), c.start().getZone().getId()));
        lines.add(l.text("discover.context.availability", time(c.start()), time(c.end()), c.availableTime().toMinutes()));
        lines.add(c.maxActivityStated() ? l.text("discover.context.maxActivity", c.maxActivity().toMinutes()) : l.text("discover.context.maxActivityWindow"));
        lines.add(l.text("discover.context.distance", CompatibilityValidator.num(c.maxDistanceKm())));
        lines.add(c.preferences().isEmpty() ? l.text("discover.context.noInterests") : l.text("discover.context.interests", String.join("; ", c.preferences())));
        if (!c.avoid().isEmpty()) lines.add(l.text("discover.context.avoid", String.join("; ", c.avoid())));
        if (!c.portability().isBlank()) lines.add(l.text("discover.context.portability", c.portability()));
        if (!c.notes().isBlank()) lines.add(l.text("discover.context.notes", c.notes()));
        lines.add(!c.inventoryReadable() ? l.text("discover.context.gearUnreadable")
            : l.text(guided ? "discover.context.gearRecords" : "discover.context.gear", c.gear().items().size(), c.usableLoadouts().size()));
        lines.add(l.text("discover.context.history", c.history().size()));
        if (c.environment().isEmpty()) lines.add(l.text(c.hasCoordinates() ? "discover.context.envUnavailable" : "discover.context.envNoCoordinates"));
        else {
            var env = c.environment().get();
            lines.addAll(EnvironmentPresentation.sources(env, l));
            lines.add(l.text("discover.context.retrieved", env.retrievedAt().atZone(c.start().getZone()).format(TIME) + " " + c.start().getZone().getId()));
            if (!env.unavailableFields().isEmpty()) lines.add(l.text("discover.context.unavailable",
                guided ? missing(env.unavailableFields(), l) : String.join(", ", new TreeSet<>(env.unavailableFields()))));
            if (!c.staleFields().isEmpty()) lines.add(l.text("discover.context.stale", String.join(", ", c.staleFields())));
        }
        return lines;
    }

    public static String heading(ActivitySuggestion idea, int number, LocalizationService l) {
        return l.text("discover.card.heading", number, l.text("discover.status." + idea.feasibility().name()), idea.subject());
    }

    /** One idea: what/where/when, every deterministic check, then labelled AI rationale and limitations. */
    public static List<String> idea(ActivitySuggestion idea, LocalizationService l) { return view(idea, l).lines(); }

    /**
     * Findings that stay visible even when their check passes: the adverse-forecast warnings (the ones
     * {@code DiscoveryService} ranks by) and the travel limitations (straight-line distance only, travel time not
     * included). Every finding of a check that did not pass is always visible anyway.
     */
    public static boolean caution(LocalizedMessage finding) {
        String key = finding.key();
        return key.startsWith("discover.check.env.warn.") || key.equals("discover.check.time.travelExcluded") || key.equals("discover.check.travel.straightLine");
    }

    /**
     * One deterministic check of an idea: its criterion, status, the "Criterion: Status" line and its findings
     * ({@code findings}, in order). {@code shown} are the findings a structured view must keep visible (all of them when
     * the check did not pass, else its {@link #caution cautions}); {@code details} are the rest (evidence of a passing
     * check), which a view may collapse.
     */
    public record CheckView(String criterion, Feasibility status, String name, String statusText, String line, List<String> findings,
            List<String> shown, List<String> details) {
        public CheckView { findings = List.copyOf(findings); shown = List.copyOf(shown); details = List.copyOf(details); }
    }
    /**
     * The localized parts of one idea, so structured views (GUI cards, the CLI wizard) show exactly the text of
     * {@link #idea}: {@link #lines()} is that text. AI statements (where, rationale, AI limitations) and the
     * deterministic checks stay separate parts.
     */
    public record IdeaView(ActivitySuggestion idea, String status, String statusDetail, Optional<String> genre, Optional<String> setting,
            String where, Optional<String> distance, String when, Optional<String> light, Optional<String> equipment, String checksTitle,
            List<CheckView> checks, String rationale, Optional<String> limitationsTitle, List<String> limitations, List<String> notes,
            Optional<String> rank) {
        public IdeaView { checks = List.copyOf(checks); limitations = List.copyOf(limitations); notes = List.copyOf(notes); }
        public long count(Feasibility status) { return checks.stream().filter(c -> c.status() == status).count(); }
        /** Checks that did not pass, whose findings are warnings that stay visible. */
        public List<CheckView> open() { return checks.stream().filter(c -> c.status() != Feasibility.FEASIBLE).toList(); }
        public List<String> lines() {
            var lines = new ArrayList<String>();
            lines.add(statusDetail);
            genre.ifPresent(lines::add); setting.ifPresent(lines::add);
            lines.add(where); distance.ifPresent(lines::add); lines.add(when); light.ifPresent(lines::add); equipment.ifPresent(lines::add);
            if (!checks.isEmpty()) {
                lines.add(checksTitle);
                for (var check : checks) {
                    lines.add("  " + check.line());
                    for (var finding : check.findings()) lines.add("    - " + finding);
                }
            }
            lines.add(rationale);
            limitationsTitle.ifPresent(title -> { lines.add(title); limitations.forEach(item -> lines.add("  - " + item)); });
            lines.addAll(notes);
            rank.ifPresent(lines::add);
            return lines;
        }
    }
    public static IdeaView view(ActivitySuggestion idea, LocalizationService l) {
        var checks = idea.checks().stream().map(check -> {
            String name = l.text("discover.criterion." + check.criterion()), status = l.text("discover.status." + check.status().name());
            var all = new ArrayList<String>(); var shown = new ArrayList<String>(); var details = new ArrayList<String>();
            for (var finding : check.findings()) {
                String text = finding.render(l);
                all.add(text);
                (check.status() != Feasibility.FEASIBLE || caution(finding) ? shown : details).add(text);
            }
            return new CheckView(check.criterion(), check.status(), name, status, l.text("discover.card.check", name, status), all, shown, details);
        }).toList();
        return new IdeaView(idea, status(idea.feasibility(), l), l.text("discover.card.status." + idea.feasibility().name()),
            idea.genre().isBlank() ? Optional.empty() : Optional.of(l.text("discover.card.genre", idea.genre())),
            idea.setting().isBlank() ? Optional.empty() : Optional.of(l.text("discover.card.setting", idea.setting())),
            idea.atUserLocation() ? l.text("discover.card.atLocation", idea.place())
                : idea.place().isBlank() ? l.text("discover.card.noPlace") : l.text("discover.card.destination", idea.place()),
            idea.estimatedTravelKm().map(km -> l.text("discover.card.straightLine", CompatibilityValidator.num(km))),
            idea.start().map(start -> l.text("discover.card.when", time(start), time(idea.end().orElseThrow()), idea.estimatedDuration().toMinutes()))
                .orElseGet(() -> l.text("discover.card.duration", idea.estimatedDuration().toMinutes())),
            idea.light().isBlank() ? Optional.empty() : Optional.of(l.text("discover.card.light", l.text("discover.light." + idea.light()))),
            idea.gear().isEmpty() ? Optional.empty() : Optional.of(l.text("discover.card.gear", String.join("; ", idea.gear()))),
            l.text("discover.card.checks"), checks,
            idea.rationale().isBlank() ? l.text("discover.card.noRationale") : l.text("discover.card.rationale", idea.rationale()),
            idea.limitations().isEmpty() ? Optional.empty()
                : Optional.of(l.text(idea.suggestedAt().isPresent() && !idea.checks().isEmpty() ? "discover.card.aiLimitations" : "discover.card.limitations")),
            idea.limitations(), idea.notes().stream().map(note -> note.render(l)).toList(),
            idea.rankReasons().isEmpty() ? Optional.empty()
                : Optional.of(l.text("discover.card.rank", String.join("; ", idea.rankReasons().stream().map(r -> r.render(l)).toList()))));
    }
    /** "n passed, n unverified, n conflict(s)": how much of an idea the deterministic checks established. */
    public static String verification(IdeaView view, LocalizationService l) {
        if (view.checks().isEmpty()) return l.text("discover.verify.none");
        return l.text("discover.verify.counts", view.count(Feasibility.FEASIBLE), view.count(Feasibility.UNVERIFIED), view.count(Feasibility.INFEASIBLE), view.checks().size());
    }

    /** Inventory counts per equipment type for the Guided Discovery summary: records (inventory entries) and units (summed quantities). */
    public record GearCount(EquipmentType type, int records, int units) { }
    /** Summary order; filters (and any later type) are shown only when recorded. */
    public static final List<EquipmentType> GEAR_ORDER = List.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.BATTERY, EquipmentType.STORAGEMEDIA, EquipmentType.FILTER);
    public static final Set<EquipmentType> GEAR_ALWAYS = EnumSet.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.BATTERY, EquipmentType.STORAGEMEDIA);
    public static List<GearCount> gearCounts(GearInventory inventory) {
        var counts = new ArrayList<GearCount>();
        var order = new ArrayList<>(GEAR_ORDER);
        for (var type : EquipmentType.values()) if (!order.contains(type)) order.add(type);
        for (var type : order) {
            var items = inventory.items().stream().filter(item -> item.type() == type).toList();
            if (items.isEmpty() && !GEAR_ALWAYS.contains(type)) continue;
            counts.add(new GearCount(type, items.size(), items.stream().mapToInt(Equipment::quantity).sum()));
        }
        return counts;
    }
    public static String gearCategory(EquipmentType type, LocalizationService l) { return l.text("discover.gear.category." + type.name()); }
    public static String gearUnits(GearCount count, LocalizationService l) { return l.text("discover.gear.units", count.units()); }
    public static String gearRecords(GearCount count, LocalizationService l) { return l.text("discover.gear.records", count.records()); }
    /** One line: units per category and the record total, or the empty-inventory notice. */
    public static String gearOverview(GearInventory inventory, LocalizationService l) {
        if (inventory.items().isEmpty()) return l.text("discover.gear.empty");
        var parts = gearCounts(inventory).stream().map(c -> l.text("discover.gear.overviewPart", gearCategory(c.type(), l), c.units())).toList();
        return l.text("discover.gear.overview", String.join(" · ", parts), inventory.items().size(),
            inventory.items().stream().mapToInt(Equipment::quantity).sum());
    }
    /** The complete inventory grouped by category (summary order), one "brand model ×quantity" line per record. */
    public static Map<EquipmentType, List<String>> gearGroups(GearInventory inventory, LocalizationService l) {
        var groups = new LinkedHashMap<EquipmentType, List<String>>();
        for (var count : gearCounts(inventory)) {
            var lines = inventory.items().stream().filter(item -> item.type() == count.type())
                .map(item -> l.text("discover.gear.groupItem", (item.brand() + " " + item.model()).strip(), item.quantity())).toList();
            if (!lines.isEmpty()) groups.put(count.type(), lines);
        }
        return groups;
    }

    /** One reviewed condition of a request: a stable key, its label and its value, for request summaries. */
    public record Condition(String key, String label, String value) { }
    /** The conditions a request was (or will be) made with, from the request itself, never from later edits. */
    public static List<Condition> conditions(DiscoveryRequest r, LocalizationService l) {
        var rows = new ArrayList<Condition>();
        var place = r.place();
        rows.add(condition("location", l, r.hasCoordinates()
            ? l.text("discover.request.placeCoordinates", place.label(), EnvironmentForm.coordinate(place.latitude().orElseThrow()), EnvironmentForm.coordinate(place.longitude().orElseThrow()))
            : l.text("discover.request.placeName", place.label())));
        rows.add(condition("date", l, r.date().toString()));
        rows.add(condition("time", l, l.text(r.crossesMidnight() ? "discover.request.timeOvernight" : "discover.request.time",
            r.availableFrom().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)), r.availableUntil().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)), r.window().toMinutes())));
        r.maxActivity().ifPresent(limit -> rows.add(condition("duration", l, l.text("discover.request.duration", limit.toMinutes()))));
        rows.add(condition("distance", l, l.text("discover.request.distance", CompatibilityValidator.num(r.maxDistanceKm()))));
        rows.add(condition("interests", l, r.interests().isEmpty() ? l.text("discover.request.anyInterest") : String.join("; ", interests(r.interests(), l))));
        if (!r.avoid().isEmpty()) rows.add(condition("avoid", l, String.join("; ", r.avoid())));
        if (!r.portability().isBlank()) rows.add(condition("portability", l, r.portability()));
        if (!r.notes().isBlank()) rows.add(condition("notes", l, r.notes()));
        rows.add(condition("history", l, l.text(r.useHistory() ? "discover.request.historyOn" : "discover.request.historyOff")));
        return rows;
    }
    private static Condition condition(String key, LocalizationService l, String value) { return new Condition(key, l.text("discover.request.label." + key), value); }
    /** Interest entries for display: a preset category's request term shown as its localized label, other entries as typed. */
    public static List<String> interests(List<String> entries, LocalizationService l) {
        return entries.stream().map(entry -> DiscoveryInterests.byTerm(entry).map(c -> l.text(c.labelKey())).orElse(entry)).toList();
    }
    /** Live note under the time fields: the window length and whether it ends the next day. */
    public static String window(DiscoveryGuide.Window window, LocalizationService l) {
        return l.text(window.overnight() ? "discover.window.overnight" : "discover.window.length", window.length().toMinutes());
    }

    /** Result-level overview: counts, application notices and (when nothing is feasible) the limiting factors. */
    public static List<String> summary(DiscoveryResult r, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("discover.summary.counts", r.feasible().size(), r.unverified().size(), r.infeasible().size(), r.rejected().size()));
        if (r.feasible().isEmpty()) lines.add(l.text(r.usable().isEmpty() ? "discover.summary.none" : "discover.summary.noneFeasible"));
        lines.add(l.text("discover.summary.order"));
        r.notices().forEach(n -> lines.add("- " + n.render(l)));
        return lines;
    }
    /**
     * Evidence gaps that must stay visible next to the results: the forecast fields the providers did not supply (the
     * same line as in {@link #context}), so "no warning" is never read as "no risk". Stale data and missing coordinates
     * or forecasts are already result notices.
     */
    public static List<String> gaps(DiscoveryResult r, LocalizationService l) {
        return r.context().environment().filter(env -> !env.unavailableFields().isEmpty())
            .map(env -> List.of(l.text("discover.context.unavailable", missing(env.unavailableFields(), l)))).orElse(List.of());
    }

    /** Hourly provider fields that have a display label ({@code env.metric.*}); {@code weatherCode} is the condition. */
    private static final Map<String, String> METRIC_LABELS = Map.of("temperatureC", "env.metric.temperatureC",
        "precipitationProbabilityPct", "env.metric.precipitationProbabilityPct", "precipitationMm", "env.metric.precipitationMm",
        "cloudCoverPct", "env.metric.cloudCoverPct", "windSpeedKmh", "env.metric.windSpeedKmh", "windGustsKmh", "env.metric.windGustsKmh",
        "visibilityM", "env.metric.visibilityM", "weatherCode", "env.metric.condition");

    /**
     * Missing provider fields as a reader can understand them, sorted by field and without repeats: the hourly forecast,
     * daily forecast and sun data as a whole, an hourly measurement or a sun event by its label, and "label on date" for
     * a field missing on one date. A field without a label (a daily statistic) keeps its provider name.
     */
    static String missing(Collection<String> fields, LocalizationService l) {
        var names = new LinkedHashSet<String>();
        for (String field : new TreeSet<>(fields)) names.add(field(field, l));
        return String.join(", ", names);
    }
    private static String field(String field, LocalizationService l) {
        int at = field.indexOf('@');
        String name = at < 0 ? field : field.substring(0, at);
        String label;
        if (name.equals(EnvironmentContext.HOURLY)) label = l.text("discover.gap.hourly");
        else if (name.equals(EnvironmentContext.DAILY)) label = l.text("discover.gap.daily");
        else if (name.equals(EnvironmentContext.SUN)) label = l.text("discover.gap.sun");
        else if (name.startsWith("sun.") && EnvironmentContext.SUN_KINDS.contains(name.substring(4))) label = l.text("env.sun." + name.substring(4));
        else if (name.startsWith("hourly.") && METRIC_LABELS.containsKey(name.substring(7))) label = l.text(METRIC_LABELS.get(name.substring(7)));
        else return field;
        return at < 0 ? label : l.text("discover.gap.on", label, field.substring(at + 1));
    }

    /** Result notices about missing, unreadable, assumed, approximate or outdated evidence: views mark them as warnings. */
    private static final Set<String> WARNING_NOTICES = Set.of("discover.notice.noCoordinates", "discover.notice.environmentUnavailable",
        "discover.notice.zoneComputer", "discover.notice.noForecast", "discover.notice.inventoryUnavailable", "discover.notice.inventoryUnreadable",
        "discover.notice.noGear", "discover.notice.noLoadout", "discover.notice.historyUnavailable", "discover.notice.approximate",
        "env.notice.beyondHorizon", "env.notice.nextDayBeyondHorizon", "env.notice.weatherUnavailable", "env.notice.sunUnavailable",
        "env.notice.sunFallback", "env.notice.zoneUnverified", "env.notice.zoneMismatch", "env.notice.zoneFromLookup", "env.notice.partialSeries",
        "env.notice.implausible", "env.notice.sunMismatch", "env.notice.partlyPast", "env.notice.stale", "env.notice.longRange");

    /**
     * Whether a result notice reports a limit of the evidence (missing, unreadable, assumed, approximate or outdated),
     * as opposed to plain information such as how history was used. Views mark these as warnings.
     */
    public static boolean warning(LocalizedMessage notice) { return WARNING_NOTICES.contains(notice.key()); }

    /** The result notices with whether each is a {@link #warning}, in order, rendered. */
    public record Notice(String text, boolean warning) { }
    public static List<Notice> notices(DiscoveryResult r, LocalizationService l) {
        return r.notices().stream().map(n -> new Notice(n.render(l), warning(n))).toList();
    }
    public static List<String> limits(DiscoveryResult r, LocalizationService l) {
        return r.limitingFactors().stream().map(m -> "- " + m.render(l)).toList();
    }
    /** Gemini's notes about the whole request; advice, never facts. */
    public static List<String> ai(DiscoveryResult r, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("discover.ai.label"));
        if (!r.summary().isBlank()) lines.add(r.summary());
        if (!r.missingContext().isEmpty()) { lines.add(l.text("discover.ai.missing")); r.missingContext().forEach(s -> lines.add("  - " + s)); }
        if (!r.adjustments().isEmpty()) { lines.add(l.text("discover.ai.adjustments")); r.adjustments().forEach(s -> lines.add("  - " + s)); }
        if (lines.size() == 1) lines.add(l.text("discover.ai.none"));
        return lines;
    }
    public static List<String> rejected(DiscoveryResult r, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (r.rejected().isEmpty()) { lines.add(l.text("discover.rejected.none")); return lines; }
        for (var item : r.rejected()) {
            String name = item.subject().isBlank() ? l.text("discover.rejected.unnamed") : item.subject();
            lines.add("- " + l.text("discover.rejected.item", name, String.join(" ", item.reasons().stream().map(m -> m.render(l)).toList())));
        }
        return lines;
    }
    /** Saved F01 ideas: their stored status and caveats as recorded when saved. */
    public static List<String> saved(List<ActivitySuggestion> ideas, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (ideas.isEmpty()) { lines.add(l.text("discover.saved.none")); return lines; }
        for (int i = 0; i < ideas.size(); i++) {
            var idea = ideas.get(i);
            lines.add(l.text("discover.saved.item", i + 1, idea.subject(), l.text("discover.status." + idea.feasibility().name()),
                idea.suggestedAt().map(t -> t.toString().substring(0, 10)).orElse(l.text("discover.saved.unknownDate"))));
            if (!idea.place().isBlank()) lines.add("  " + l.text("discover.card.destination", idea.place()));
            idea.start().ifPresent(start -> lines.add("  " + l.text("discover.card.when", time(start), time(idea.end().orElseThrow()), idea.estimatedDuration().toMinutes())));
            idea.limitations().stream().limit(4).forEach(item -> lines.add("  - " + item));
        }
        return lines;
    }
    public static String status(Feasibility status, LocalizationService l) { return l.text("discover.status." + status.name()); }
    public static String render(List<LocalizedMessage> messages, LocalizationService l) { return String.join(" ", messages.stream().map(m -> m.render(l)).toList()); }
    private static String time(ZonedDateTime time) { return time.format(TIME) + " " + time.getZone().getId(); }
}
