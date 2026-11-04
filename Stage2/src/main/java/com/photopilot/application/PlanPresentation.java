package com.photopilot.application;

import java.time.format.DateTimeFormatter;
import java.util.*;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.service.PlanInputs;
import com.photopilot.service.PlanPreview;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.PlanValidator;

/**
 * Shared localized F10 text for GUI cards and CLI output. Every statement comes from the plan, its validation report
 * or the planning inputs; nothing is computed or decided here.
 */
public final class PlanPresentation {
    private PlanPresentation() { }
    /** Plan sections in display order; each maps to one GUI card and one CLI block. */
    public static final List<String> SECTIONS = List.of("sessionInfo", "equipment", "strategy", "settings", "schedule", "preparation",
        "checklist", "shotPriorities", "calculations", "limitations");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** What the planner would use, and what blocks generation. */
    public static List<String> inputs(PlanInputs inputs, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(inputs.requirement().map(r -> l.text("plan.inputs.requirement", r.subject())).orElse(l.text("plan.missing.requirement")));
        lines.add(inputs.profile().map(p -> l.text("plan.inputs.profile", p.needs().size())).orElse(l.text("plan.missing.profile")));
        if (inputs.requirement().isPresent() && inputs.profile().isPresent()) lines.add(l.text("plan.inputs.candidates", inputs.candidates()));
        lines.add(inputs.session().selectedLoadout().isPresent() ? l.text("plan.inputs.selection") : l.text("plan.inputs.noSelection"));
        lines.add(inputs.environment().map(e -> l.text("plan.inputs.environment", e.context().place().map(GeoLocation::label).orElse(""),
            e.context().retrievedAt().toString())).orElse(l.text("plan.inputs.noEnvironment")));
        inputs.notes().forEach(note -> lines.add("- " + note.render(l)));
        lines.add(inputs.current().map(p -> l.text("plan.inputs.saved", p.revision())).orElse(l.text("plan.inputs.noSaved")));
        if (!inputs.ready()) { lines.add(l.text("plan.inputs.blocked")); inputs.blockers().forEach(b -> lines.add("- " + b.render(l))); }
        return lines;
    }

    public static String heading(ShootPlan plan, LocalizationService l) {
        return l.text(plan.approved() ? "plan.heading.approved" : "plan.heading.preview", plan.revision());
    }

    /** One plan section as display lines. */
    public static List<String> section(ShootPlan plan, String section, LocalizationService l) {
        var lines = new ArrayList<String>();
        switch (section) {
            case "sessionInfo" -> {
                for (String key : ShootPlan.SESSION_INFO) if (plan.sessionInfo().containsKey(key)) lines.add(l.text("plan.info." + key, plan.sessionInfo().get(key)));
                if (!plan.summary().isBlank()) lines.add(l.text("plan.summary", plan.summary()));
                if (!plan.changeSummary().isBlank()) lines.add(l.text("plan.changeSummary", plan.changeSummary()));
            }
            case "equipment" -> equipment(plan, lines, l);
            case "strategy" -> {
                for (String key : ShootPlan.STRATEGY) if (plan.strategy().containsKey(key)) lines.add(l.text("plan.strategy." + key) + ": " + plan.strategy().get(key));
                lines.add(l.text("plan.strategy.note"));
            }
            case "settings" -> {
                var names = names(plan);
                var keys = new ArrayList<>(plan.settings().keySet());
                keys.sort(Comparator.comparingInt((String k) -> ShootPlan.SETTINGS.indexOf(ShootPlan.settingName(k))).thenComparing(k -> k));
                for (String key : keys) lines.add(l.text("plan.settingLine", l.text("plan.setting." + ShootPlan.settingName(key)), plan.settings().get(key),
                    ShootPlan.settingCamera(key).map(id -> names.getOrDefault(id, "?")).orElse(l.text("plan.allCameras"))));
                if (!keys.isEmpty()) lines.add(l.text("plan.settings.note"));
            }
            case "schedule" -> {
                for (ShootingWindow w : plan.windows()) {
                    lines.add(l.text("plan.window", w.start().toLocalDate().toString(), w.start().format(TIME), w.end().format(TIME), w.start().getZone().getId(), w.conditions()));
                    if (w.arrival().isBefore(w.start())) lines.add("  " + l.text("plan.arrival", w.arrival().format(TIME)));
                    if (w.estimated()) lines.add("  " + l.text("plan.window.estimated"));
                }
            }
            case "preparation" -> plan.preparation().forEach(item -> lines.add("- " + item));
            case "checklist" -> {
                plan.loadout().ifPresent(loadout -> {
                    lines.add(l.text("plan.pack.gear"));
                    loadout.gear().forEach(item -> lines.add("  [ ] " + LoadoutPresentation.gear(item, l)));
                });
                if (!plan.checklist().isEmpty()) lines.add(l.text("plan.pack.other"));
                plan.checklist().forEach(item -> lines.add("  [ ] " + item));
            }
            case "shotPriorities" -> {
                int rank = 0;
                for (String item : plan.shotPriorities()) {
                    int colon = item.indexOf(": ");
                    String kind = colon > 0 ? item.substring(0, colon) : "secondary", text = colon > 0 ? item.substring(colon + 2) : item;
                    lines.add(l.text(kind.equals("primary") ? "plan.priority.primary" : "plan.priority.secondary", ++rank, text));
                }
                if (!plan.tradeOffs().isEmpty()) lines.add(l.text("plan.tradeOffs"));
                plan.tradeOffs().forEach(item -> lines.add("  - " + item));
            }
            case "calculations" -> {
                for (CalculationResult result : plan.calculations()) {
                    var inputs = new TreeMap<>(result.input().parameters()).entrySet().stream()
                        .map(e -> e.getKey() + "=" + CompatibilityValidator.num(e.getValue()) + " " + result.input().units().getOrDefault(e.getKey(), "")).toList();
                    var values = new TreeMap<>(result.values()).entrySet().stream()
                        .map(e -> e.getKey() + " " + (e.getValue().isInfinite() ? "∞" : CompatibilityValidator.num(round(e.getValue()))) + " " + result.units().getOrDefault(e.getKey(), "")).toList();
                    lines.add(l.text("plan.calculation", l.text("plan.calc." + result.input().operation().name()), String.join(", ", inputs), String.join("; ", values)));
                }
                if (!plan.calculations().isEmpty()) lines.add(l.text("plan.calc.note"));
            }
            case "limitations" -> {
                plan.provisionalReasons().forEach(reason -> lines.add("! " + reason));
                plan.limitations().forEach(item -> lines.add("- " + item));
            }
            default -> { }
        }
        if (lines.isEmpty()) lines.add(l.text("plan.section.empty"));
        return lines;
    }
    private static double round(double value) { return Math.round(value * 100) / 100.0; }

    private static void equipment(ShootPlan plan, List<String> lines, LocalizationService l) {
        if (plan.loadout().isEmpty()) { lines.add(l.text("plan.noLoadout")); return; }
        var loadout = plan.loadout().get(); var names = names(plan);
        lines.add(l.text(loadout.compatibility().isUsable() ? "plan.loadout.verified" : "plan.loadout.notVerified"));
        loadout.gear().forEach(item -> lines.add("  " + LoadoutPresentation.gear(item, l)));
        new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) -> lines.add(l.text("plan.mount", names.get(lens), names.get(camera))));
        lines.add(loadout.totalWeightGrams().map(w -> l.text("plan.weight", CompatibilityValidator.num(w))).orElse(l.text("plan.weightUnknown")));
        var coverage = CompatibilityValidator.focalCoverage(loadout);
        if (!coverage.isEmpty()) lines.add(l.text("plan.coverage", String.join("; ", coverage.stream().map(CompatibilityValidator::range).toList())));
        loadout.unmetNeeds().forEach(need -> lines.add("  ! " + need));
        plan.equipmentNotes().forEach(note -> lines.add("- " + note));
    }
    private static Map<UUID, String> names(ShootPlan plan) {
        var names = new HashMap<UUID, String>();
        plan.loadout().ifPresent(l -> l.gear().forEach(g -> names.put(g.sourceGearId(), CompatibilityValidator.name(g))));
        return names;
    }

    /** Deterministic validation outcome: whether approval is possible and every finding by category. */
    public static List<String> validation(PlanPreview preview, LocalizationService l) {
        var report = preview.validation(); var lines = new ArrayList<String>();
        lines.add(l.text(preview.approvable() ? "plan.validation.ok" : "plan.validation.blocked"));
        add(lines, "plan.validation.violations", report.violations(), l);
        add(lines, "plan.validation.missing", report.missing(), l);
        add(lines, "plan.validation.unverified", report.unverified(), l);
        add(lines, "plan.validation.limitations", report.limitations(), l);
        if (!preview.questions().isEmpty()) { lines.add(l.text("plan.validation.questions")); preview.questions().forEach(q -> lines.add("  ? " + q)); }
        return lines;
    }
    private static void add(List<String> lines, String title, List<LocalizedMessage> messages, LocalizationService l) {
        if (messages.isEmpty()) return;
        lines.add(l.text(title));
        messages.forEach(m -> lines.add("  - " + m.render(l)));
    }

    /** Inputs the proposal was built from and the deterministic tool calls the agent made. */
    public static List<String> evidence(PlanPreview preview, LocalizationService l) {
        var lines = new ArrayList<String>();
        preview.evidence().forEach(m -> lines.add("- " + m.render(l)));
        int step = 0;
        for (var exchange : preview.tools())
            lines.add(l.text(exchange.succeeded() ? "plan.tool.ok" : "plan.tool.failed", ++step, exchange.tool(), shorten(exchange.arguments()), shorten(exchange.result())));
        lines.add(l.text("plan.ai.notice"));
        return lines;
    }
    private static String shorten(String text) { String flat = text.replaceAll("\\s+", " ").strip(); return flat.length() <= 220 ? flat : flat.substring(0, 220) + "..."; }

    /** Changed and preserved decisions of a revision preview, with before/after content of each changed section. */
    public static List<String> comparison(PlanPreview preview, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (preview.base().isEmpty()) return lines;
        var base = preview.base().get(); var next = preview.plan();
        lines.add(l.text("plan.compare.title", base.revision(), next.revision()));
        if (!preview.request().isBlank()) lines.add(l.text("plan.compare.request", preview.request()));
        var changed = preview.changedSections();
        lines.add(l.text("plan.compare.changed", changed.isEmpty() ? l.text("match.none") : String.join(", ", changed.stream().map(s -> l.text("plan.section." + s)).toList())));
        var kept = ShootPlan.DECISIONS.stream().filter(s -> !changed.contains(s)).map(s -> l.text("plan.section." + s)).toList();
        lines.add(l.text("plan.compare.kept", String.join(", ", kept)));
        lines.add(l.text("plan.compare.fixed", base.fixedConstraints().equals(next.fixedConstraints()) ? l.text("plan.compare.fixedKept") : l.text("plan.compare.fixedChanged")));
        for (String section : changed) {
            String display = displaySection(section);
            lines.add(l.text("plan.compare.before", l.text("plan.section." + section)));
            section(base, display, l).forEach(line -> lines.add("    " + line));
            lines.add(l.text("plan.compare.after", l.text("plan.section." + section)));
            section(next, display, l).forEach(line -> lines.add("    " + line));
        }
        return lines;
    }
    private static String displaySection(String decision) {
        return switch (decision) {
            case "loadout", "equipmentNotes" -> "equipment";
            case "tradeOffs" -> "shotPriorities";
            default -> decision;
        };
    }

    /** Saved approved revisions, oldest first. */
    public static List<String> history(List<ShootPlan> plans, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (plans.isEmpty()) { lines.add(l.text("plan.inputs.noSaved")); return lines; }
        for (ShootPlan plan : plans)
            lines.add(l.text("plan.history.line", plan.revision(), plan.changeSummary().isBlank() ? l.text("plan.history.initial") : plan.changeSummary()));
        return lines;
    }
}
