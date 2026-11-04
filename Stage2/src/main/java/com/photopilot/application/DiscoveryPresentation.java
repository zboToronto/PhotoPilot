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
    public static List<String> context(DiscoveryContext c, LocalizationService l) {
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
            : l.text("discover.context.gear", c.gear().items().size(), c.usableLoadouts().size()));
        lines.add(l.text("discover.context.history", c.history().size()));
        if (c.environment().isEmpty()) lines.add(l.text(c.hasCoordinates() ? "discover.context.envUnavailable" : "discover.context.envNoCoordinates"));
        else {
            var env = c.environment().get();
            lines.addAll(EnvironmentPresentation.sources(env, l));
            lines.add(l.text("discover.context.retrieved", env.retrievedAt().atZone(c.start().getZone()).format(TIME) + " " + c.start().getZone().getId()));
            if (!env.unavailableFields().isEmpty()) lines.add(l.text("discover.context.unavailable", String.join(", ", new TreeSet<>(env.unavailableFields()))));
            if (!c.staleFields().isEmpty()) lines.add(l.text("discover.context.stale", String.join(", ", c.staleFields())));
        }
        return lines;
    }

    public static String heading(ActivitySuggestion idea, int number, LocalizationService l) {
        return l.text("discover.card.heading", number, l.text("discover.status." + idea.feasibility().name()), idea.subject());
    }

    /** One idea: what/where/when, every deterministic check, then labelled AI rationale and limitations. */
    public static List<String> idea(ActivitySuggestion idea, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("discover.card.status." + idea.feasibility().name()));
        if (!idea.genre().isBlank()) lines.add(l.text("discover.card.genre", idea.genre()));
        if (!idea.setting().isBlank()) lines.add(l.text("discover.card.setting", idea.setting()));
        lines.add(idea.atUserLocation() ? l.text("discover.card.atLocation", idea.place())
            : idea.place().isBlank() ? l.text("discover.card.noPlace") : l.text("discover.card.destination", idea.place()));
        idea.estimatedTravelKm().ifPresent(km -> lines.add(l.text("discover.card.straightLine", CompatibilityValidator.num(km))));
        idea.start().ifPresentOrElse(start -> lines.add(l.text("discover.card.when", time(start), time(idea.end().orElseThrow()), idea.estimatedDuration().toMinutes())),
            () -> lines.add(l.text("discover.card.duration", idea.estimatedDuration().toMinutes())));
        if (!idea.light().isBlank()) lines.add(l.text("discover.card.light", l.text("discover.light." + idea.light())));
        if (!idea.gear().isEmpty()) lines.add(l.text("discover.card.gear", String.join("; ", idea.gear())));
        if (!idea.checks().isEmpty()) {
            lines.add(l.text("discover.card.checks"));
            for (var check : idea.checks()) {
                lines.add("  " + l.text("discover.card.check", l.text("discover.criterion." + check.criterion()), l.text("discover.status." + check.status().name())));
                for (var finding : check.findings()) lines.add("    - " + finding.render(l));
            }
        }
        lines.add(idea.rationale().isBlank() ? l.text("discover.card.noRationale") : l.text("discover.card.rationale", idea.rationale()));
        if (!idea.limitations().isEmpty()) {
            lines.add(l.text(idea.suggestedAt().isPresent() && !idea.checks().isEmpty() ? "discover.card.aiLimitations" : "discover.card.limitations"));
            idea.limitations().forEach(item -> lines.add("  - " + item));
        }
        idea.notes().forEach(note -> lines.add(note.render(l)));
        if (!idea.rankReasons().isEmpty()) lines.add(l.text("discover.card.rank", String.join("; ", idea.rankReasons().stream().map(r -> r.render(l)).toList())));
        return lines;
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
