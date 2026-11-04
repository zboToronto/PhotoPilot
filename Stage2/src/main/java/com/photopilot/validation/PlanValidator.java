package com.photopilot.validation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.GearMatchReport.NeedStatus;
import com.photopilot.service.TechnicalCalculator;

/**
 * Stage 1 UML element 74 (zero-based): {deterministic; recompute numbers, reject fixed-constraint changes}
 * F10 gate between an AI proposal and approval. {@link #assess} checks a plan against the confirmed requirement and
 * the evidence it was built from: fixed constraints, UC14 compatibility and current ownership of the loadout,
 * camera-count / lens-change / weight limits and mandatory capability needs, numerical settings against recorded
 * lens data, recomputed F08 calculations, and schedule windows against availability and F09 evidence. Violations
 * and missing mandatory inputs block approval; unverified assumptions and limitations are reported, never hidden.
 * {@link #validateChange} enforces revision binding, fixed-constraint preservation and that only declared
 * sections changed. A schema-valid model reply alone is never accepted as a valid plan.
 */
public class PlanValidator {
    public static final int MAX_ITEMS = 12, MAX_TEXT = 600, MAX_SUMMARY = 1500, MAX_WINDOWS = 6, MAX_SETTING = 120;
    private CompatibilityValidator compatibility;
    private TechnicalCalculator calculator;
    private final FeasibilityValidator feasibility;

    /** Evidence a plan is validated against; every part is optional except what the requirement itself supplies. */
    public record Context(Optional<EquipmentRequirementProfile> profile, Optional<GearInventory> inventory, Optional<EnvironmentAssessment> environment,
            Optional<Availability> availability, Optional<ZoneId> zone, Optional<LocalDate> date, Optional<ShootPlan> base) {
        public Context {
            Objects.requireNonNull(profile); Objects.requireNonNull(inventory); Objects.requireNonNull(environment);
            Objects.requireNonNull(availability); Objects.requireNonNull(zone); Objects.requireNonNull(date); Objects.requireNonNull(base);
        }
        public static Context none() {
            return new Context(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
    /** Locale-neutral validation outcome. */
    public record Report(List<LocalizedMessage> violations, List<LocalizedMessage> missing, List<LocalizedMessage> unverified, List<LocalizedMessage> limitations) {
        public Report { violations = List.copyOf(violations); missing = List.copyOf(missing); unverified = List.copyOf(unverified); limitations = List.copyOf(limitations); }
        /** No constraint violation and no missing mandatory input; unverified items and limitations do not block. */
        public boolean valid() { return violations.isEmpty() && missing.isEmpty(); }
        public List<LocalizedMessage> blocking() { var all = new ArrayList<>(violations); all.addAll(missing); return all; }
    }

    public PlanValidator(CompatibilityValidator compatibility, TechnicalCalculator calculator) {
        this(compatibility, calculator, new FeasibilityValidator());
    }

    public PlanValidator(CompatibilityValidator compatibility, TechnicalCalculator calculator, FeasibilityValidator feasibility) {
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.feasibility = Objects.requireNonNull(feasibility, "feasibility");
    }

    public CompatibilityValidator compatibility() { return compatibility; }

    public Result<ShootPlan> validate(ShootPlan plan, ShootRequirement req) {
        return validate(plan, req, Context.none());
    }

    public Result<ShootPlan> validate(ShootPlan plan, ShootRequirement req, Context context) {
        var report = assess(plan, req, context);
        return report.valid() ? Result.success(plan) : Result.failures(report.blocking());
    }

    /** Fixed constraints a plan must carry unchanged: the confirmed requirement's limits and the profile's mandatory needs. */
    public static Map<String, String> constraintsOf(ShootRequirement req, Optional<EquipmentRequirementProfile> profile) {
        var map = new LinkedHashMap<String, String>();
        if (req == null) return map;
        map.put("subject", req.subject());
        if (!req.goals().isEmpty()) map.put("goals", String.join("; ", req.goals()));
        req.cameraCount().ifPresent(n -> map.put("cameraCount", n.toString()));
        req.lensChangesAllowed().ifPresent(allowed -> map.put("lensChanges", allowed ? "allowed" : "avoid"));
        req.maxWeightGrams().ifPresent(w -> map.put("maxWeightGrams", CompatibilityValidator.num(w)));
        req.duration().ifPresent(d -> map.put("duration", d.toString()));
        if (!req.portability().isBlank()) map.put("portability", req.portability());
        for (int i = 0; i < req.gearConstraints().size(); i++) map.put("gear." + (i + 1), req.gearConstraints().get(i));
        req.fixedConstraints().forEach((key, value) -> map.put("constraint." + key, value));
        profile.ifPresent(p -> p.needs().stream().filter(CapabilityNeed::hardConstraint).forEach(need -> map.put("need." + need.capability(),
            need.minimum().map(CompatibilityValidator::num).orElse("") + ".." + need.maximum().map(CompatibilityValidator::num).orElse("") + " " + need.unit())));
        return map;
    }

    public Report assess(ShootPlan plan, ShootRequirement req, Context context) {
        var violations = new ArrayList<LocalizedMessage>(); var missing = new ArrayList<LocalizedMessage>();
        var unverified = new ArrayList<LocalizedMessage>(); var limitations = new ArrayList<LocalizedMessage>();
        if (plan == null) return new Report(List.of(LocalizedMessage.of("plan.error.noDraft")), List.of(), List.of(), List.of());
        if (req == null || !req.confirmed()) missing.add(LocalizedMessage.of("plan.missing.requirement"));
        else if (!plan.fixedConstraints().equals(constraintsOf(req, context.profile()))) violations.add(LocalizedMessage.of("plan.violation.constraintsChanged"));
        if (context.profile().filter(EquipmentRequirementProfile::reviewed).isEmpty()) missing.add(LocalizedMessage.of("plan.missing.profile"));
        loadout(plan, req, context, violations, missing, limitations);
        settings(plan, violations, unverified);
        violations.addAll(calculationProblems(plan));
        schedule(plan, req, context, violations, unverified, limitations);
        text(plan, violations, unverified);
        if (!plan.provisionalReasons().isEmpty()) plan.provisionalReasons().forEach(reason -> missing.add(LocalizedMessage.of("plan.missing.provisional", reason)));
        return new Report(distinct(violations), distinct(missing), distinct(unverified), distinct(limitations));
    }

    /**
     * Revision gate: same plan id, next revision of an approved base, fixed constraints preserved, only sections the
     * commands declared have changed (a regeneration after re-confirmed requirements may change all decisions), UC14
     * re-run on a changed loadout and calculations recomputed.
     */
    public Result<ShootPlan> validateChange(ShootPlan base, ShootPlan next) {
        if (base == null) return Result.failureKey("plan.error.noSavedPlan");
        if (next == null) return Result.failureKey("plan.error.noDraft");
        var found = new ArrayList<LocalizedMessage>();
        if (!base.approved()) found.add(LocalizedMessage.of("plan.violation.baseNotApproved"));
        if (next.approved()) found.add(LocalizedMessage.of("plan.error.immutable"));
        if (!next.id().equals(base.id())) found.add(LocalizedMessage.of("plan.violation.foreign"));
        if (next.revision() != base.revision() + 1) found.add(LocalizedMessage.of("plan.violation.revisionOrder", base.revision() + 1, next.revision()));
        if (!next.regeneration() && !next.fixedConstraints().equals(base.fixedConstraints())) found.add(LocalizedMessage.of("plan.violation.fixedChanged"));
        var changed = next.differingSections(base).stream().filter(ShootPlan.DECISIONS::contains).toList();
        if (!next.regeneration()) {
            for (String section : changed) if (!next.revisedSections().contains(section)) found.add(LocalizedMessage.of("plan.violation.undeclared", LocalizedMessage.of("plan.section." + section)));
            if (changed.isEmpty() && found.isEmpty()) return Result.failureKey("plan.error.noChange");
        }
        if (changed.contains("loadout")) {
            if (next.loadout().isEmpty()) found.add(LocalizedMessage.of("plan.missing.loadout"));
            else {
                var check = compatibility.assess(next.loadout().get());
                found.addAll(check.violations()); found.addAll(check.unverified());
            }
        }
        found.addAll(calculationProblems(next));
        return found.isEmpty() ? Result.success(next) : Result.failures(distinct(found));
    }

    private void loadout(ShootPlan plan, ShootRequirement req, Context context, List<LocalizedMessage> violations, List<LocalizedMessage> missing, List<LocalizedMessage> limitations) {
        if (plan.loadout().isEmpty()) { missing.add(LocalizedMessage.of("plan.missing.loadout")); return; }
        var loadout = plan.loadout().get();
        var check = compatibility.assess(loadout);
        violations.addAll(check.violations());
        violations.addAll(check.unverified()); // UC14: unverified is never usable
        limitations.addAll(check.essentials());
        context.inventory().ifPresent(inventory -> violations.addAll(compatibility.availability(loadout, inventory)));
        if (req == null) return;
        int bodies = loadout.units(EquipmentType.CAMERA), lenses = loadout.units(EquipmentType.LENS);
        req.cameraCount().ifPresent(count -> { if (bodies != count) violations.add(LocalizedMessage.of("plan.violation.cameraCount", count, bodies)); });
        if (req.lensChangesAllowed().filter(allowed -> !allowed).isPresent() && lenses > bodies)
            violations.add(LocalizedMessage.of("plan.violation.lensChanges", lenses, bodies));
        req.maxWeightGrams().ifPresent(limit -> {
            var weight = loadout.totalWeightGrams();
            if (weight.isEmpty()) violations.add(LocalizedMessage.of("plan.violation.weightUnknown", CompatibilityValidator.num(limit)));
            else if (weight.get() > limit) violations.add(LocalizedMessage.of("plan.violation.weight", CompatibilityValidator.num(weight.get()), CompatibilityValidator.num(limit)));
        });
        context.profile().ifPresent(profile -> {
            for (var result : CompatibilityValidator.evaluateNeeds(loadout, profile)) {
                if (result.status() == NeedStatus.MET) continue;
                var capability = LocalizedMessage.of("needs.capability." + result.need().capability());
                if (result.need().hardConstraint()) violations.add(LocalizedMessage.of("plan.violation.need", capability, result.detail()));
                else limitations.add(LocalizedMessage.of("plan.limit.need", capability, result.detail()));
            }
        });
    }

    private void settings(ShootPlan plan, List<LocalizedMessage> violations, List<LocalizedMessage> unverified) {
        var loadout = plan.loadout();
        var gear = new HashMap<UUID, GearSnapshot>();
        loadout.ifPresent(l -> l.gear().forEach(g -> gear.put(g.sourceGearId(), g)));
        for (var entry : new TreeMap<>(plan.settings()).entrySet()) {
            String key = entry.getKey(), value = Objects.toString(entry.getValue(), "").strip(), name = ShootPlan.settingName(key);
            if (!ShootPlan.SETTINGS.contains(name)) { violations.add(LocalizedMessage.of("plan.violation.settingName", name)); continue; }
            if (value.isEmpty() || value.length() > MAX_SETTING) { violations.add(LocalizedMessage.of("plan.violation.settingFormat", name, value)); continue; }
            Optional<UUID> camera = ShootPlan.settingCamera(key);
            if (key.contains("@") && (camera.isEmpty() || gear.get(camera.get()) == null || !gear.get(camera.get()).type().equals("CAMERA"))) {
                violations.add(LocalizedMessage.of("plan.violation.settingCamera", name)); continue;
            }
            List<GearSnapshot> lenses = relevantLenses(loadout, camera);
            switch (name) {
                case "shutterSpeed" -> range(name, value, numbers(value, true, value.toLowerCase(Locale.ROOT).contains("ms") ? 0.001 : 1), 1.0 / 64000, 3600, violations, unverified);
                case "iso" -> range(name, value, numbers(value, false, 1), 25, 819200, violations, unverified);
                case "exposureCompensation" -> range(name, value, signed(value), -10, 10, violations, unverified);
                case "aperture" -> {
                    var values = numbers(value, false, 1);
                    if (range(name, value, values, 0.7, 128, violations, unverified)) {
                        double widest = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                        for (GearSnapshot lens : lenses) {
                            var max = number(lens, "maxAperture");
                            if (max.isEmpty()) unverified.add(LocalizedMessage.of("plan.unverified.aperture", CompatibilityValidator.name(lens)));
                            else if (widest < max.get() - 1e-9) violations.add(LocalizedMessage.of("plan.violation.aperture", value, CompatibilityValidator.name(lens), CompatibilityValidator.num(max.get())));
                        }
                    }
                }
                case "focalLength" -> {
                    var values = numbers(value, false, value.toLowerCase(Locale.ROOT).contains("cm") ? 10 : 1);
                    if (range(name, value, values, 1, 2000, violations, unverified) && !lenses.isEmpty()) {
                        for (double focal : values) {
                            boolean covered = lenses.stream().anyMatch(l -> number(l, "minFocalMm").filter(min -> min <= focal + 1e-9).isPresent()
                                && number(l, "maxFocalMm").filter(max -> max >= focal - 1e-9).isPresent());
                            if (!covered) violations.add(LocalizedMessage.of("plan.violation.focal", value));
                        }
                    }
                }
                default -> { }
            }
        }
    }
    /** Mounted lens of the bound body, otherwise every mounted lens, otherwise every lens carried. */
    private static List<GearSnapshot> relevantLenses(Optional<Loadout> loadout, Optional<UUID> camera) {
        if (loadout.isEmpty()) return List.of();
        var byId = new HashMap<UUID, GearSnapshot>(); loadout.get().gear().forEach(g -> byId.put(g.sourceGearId(), g));
        var assignments = loadout.get().cameraLensAssignments();
        if (camera.isPresent() && assignments.containsKey(camera.get())) return List.of(byId.get(assignments.get(camera.get())));
        var mounted = assignments.values().stream().distinct().map(byId::get).filter(Objects::nonNull).toList();
        return mounted.isEmpty() ? loadout.get().ofType(EquipmentType.LENS) : mounted;
    }
    /** One value or a two-value range; a descriptive value (e.g. "auto") is reported as unverified, not guessed. */
    private static boolean range(String name, String value, List<Double> values, double low, double high, List<LocalizedMessage> violations, List<LocalizedMessage> unverified) {
        if (values.isEmpty() || values.size() > 2) { unverified.add(LocalizedMessage.of("plan.unverified.setting", name, value)); return false; }
        for (double v : values) if (!Double.isFinite(v) || v < low - 1e-12 || v > high + 1e-9) {
            violations.add(LocalizedMessage.of("plan.violation.settingRange", name, value)); return false;
        }
        return true;
    }
    private static final Pattern FRACTION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)");
    private static final Pattern SIGNED = Pattern.compile("([+\\-−]?)\\s*(\\d+(?:\\.\\d+)?)(?:\\s*/\\s*(\\d+))?");
    private static List<Double> numbers(String value, boolean fractions, double scale) {
        var found = new ArrayList<Double>();
        Matcher m = FRACTION.matcher(value.replace("f/", " ").replace("F/", " "));
        while (m.find()) {
            if (m.group(1) != null) {
                if (!fractions) { found.add(Double.parseDouble(m.group(1))); found.add(Double.parseDouble(m.group(2))); continue; }
                double denominator = Double.parseDouble(m.group(2));
                found.add(denominator == 0 ? Double.NaN : Double.parseDouble(m.group(1)) / denominator * scale);
            } else found.add(Double.parseDouble(m.group(3)) * scale);
        }
        return found;
    }
    private static List<Double> signed(String value) {
        var found = new ArrayList<Double>();
        Matcher m = SIGNED.matcher(value);
        while (m.find()) {
            double number = Double.parseDouble(m.group(2));
            if (m.group(3) != null) number = Double.parseDouble(m.group(3)) == 0 ? Double.NaN : number / Double.parseDouble(m.group(3));
            found.add(m.group(1).equals("-") || m.group(1).equals("−") ? -number : number);
        }
        return found;
    }

    /** Every stored calculation must be reproduced exactly by F08 from its retained input. */
    private List<LocalizedMessage> calculationProblems(ShootPlan plan) {
        var found = new ArrayList<LocalizedMessage>();
        for (CalculationResult stored : plan.calculations()) {
            var fresh = calculator.calculate(stored.input());
            String operation = stored.input().operation().name();
            if (!fresh.isSuccess()) { found.add(LocalizedMessage.of("plan.violation.calculation", operation)); continue; }
            var values = fresh.value().orElseThrow().values();
            boolean same = values.keySet().equals(stored.values().keySet()) && values.entrySet().stream().allMatch(e -> {
                double a = e.getValue(), b = stored.values().get(e.getKey());
                return a == b || Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a));
            });
            if (!same) found.add(LocalizedMessage.of("plan.violation.calculationMismatch", operation));
        }
        return found;
    }

    private void schedule(ShootPlan plan, ShootRequirement req, Context context, List<LocalizedMessage> violations, List<LocalizedMessage> unverified, List<LocalizedMessage> limitations) {
        var windows = plan.windows();
        if (windows.isEmpty()) return;
        if (windows.size() > MAX_WINDOWS) violations.add(LocalizedMessage.of("plan.violation.tooMany", LocalizedMessage.of("plan.section.schedule"), MAX_WINDOWS));
        var carried = new HashSet<String>();
        context.base().ifPresent(base -> base.windows().forEach(w -> carried.add(signature(w))));
        var fresh = new ArrayList<ShootingWindow>();
        for (ShootingWindow window : windows) {
            var structural = window.validate();
            if (!structural.isSuccess()) { violations.addAll(structural.messages()); continue; }
            if (carried.contains(signature(window))) continue; // approved earlier against its own evidence
            fresh.add(window);
        }
        if (!fresh.isEmpty()) {
            if (context.availability().isEmpty() || context.zone().isEmpty()) violations.add(LocalizedMessage.of("plan.violation.noTiming"));
            else if (context.environment().isPresent() && context.environment().get().availability().isPresent()) {
                var checked = feasibility.checkTiming(context.environment().get().validated(fresh, List.of(), List.of()));
                checked.value().ifPresent(assessment -> assessment.rejected().forEach(rejected -> rejected.reasons().forEach(reason ->
                    violations.add(LocalizedMessage.of("plan.violation.window", rejected.label(), reason)))));
            } else {
                var available = context.availability().get();
                for (ShootingWindow window : fresh) {
                    if (!window.start().getZone().equals(context.zone().get())) violations.add(LocalizedMessage.of("env.reject.zone"));
                    if (!available.contains(window.start(), window.end())) violations.add(LocalizedMessage.of("plan.violation.availability", window.label().isBlank() ? window.conditions() : window.label()));
                    if (window.arrival().isBefore(available.start())) violations.add(LocalizedMessage.of("env.reject.arrival"));
                    if (window.duration().compareTo(FeasibilityValidator.MIN_WINDOW) < 0) violations.add(LocalizedMessage.of("env.reject.short", FeasibilityValidator.MIN_WINDOW.toMinutes()));
                    if (!window.light().equals("mixed")) unverified.add(LocalizedMessage.of("plan.unverified.light", LocalizedMessage.of("plan.light." + window.light())));
                }
                limitations.add(LocalizedMessage.of("plan.limit.noEnvironment"));
            }
        }
        var ordered = windows.stream().sorted(Comparator.comparing(ShootingWindow::start)).toList();
        for (int i = 1; i < ordered.size(); i++)
            if (ordered.get(i).start().isBefore(ordered.get(i - 1).end())) { violations.add(LocalizedMessage.of("plan.violation.overlap")); break; }
        if (req != null) req.duration().ifPresent(limit -> {
            Duration total = windows.stream().map(ShootingWindow::duration).reduce(Duration.ZERO, Duration::plus);
            if (total.compareTo(limit) > 0) violations.add(LocalizedMessage.of("plan.violation.duration", total.toMinutes(), limit.toMinutes()));
        });
    }
    private static String signature(ShootingWindow w) { return w.start() + "|" + w.end() + "|" + w.arrival() + "|" + w.conditions(); }

    /** Inventory categories and other equipment words; matching is case-insensitive and word-based. */
    static final Map<String, List<String>> GEAR_TERMS = gearTerms();
    private static Map<String, List<String>> gearTerms() {
        var terms = new LinkedHashMap<String, List<String>>();
        terms.put("CAMERA", List.of("camera", "cameras", "body", "bodies", "相机", "机身"));
        terms.put("LENS", List.of("lens", "lenses", "镜头"));
        terms.put("FILTER", List.of("filter", "filters", "cpl", "polarizer", "滤镜"));
        terms.put("BATTERY", List.of("battery", "batteries", "电池"));
        terms.put("STORAGEMEDIA", List.of("card", "cards", "sd", "cfexpress", "存储卡", "内存卡"));
        terms.put("OTHER", List.of("tripod", "monopod", "gimbal", "flash", "speedlight", "teleconverter", "extender", "drone", "adapter",
            "charger", "三脚架", "独脚架", "云台", "闪光灯", "增距镜", "无人机", "转接环", "充电器"));
        return Collections.unmodifiableMap(terms);
    }
    /** Equipment category words in {@code text}; OTHER means equipment the inventory model cannot record at all. */
    public static Set<String> gearMentions(String text) {
        var found = new LinkedHashSet<String>();
        String lower = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        GEAR_TERMS.forEach((category, words) -> {
            for (String word : words) {
                boolean cjk = word.codePoints().anyMatch(Character::isIdeographic);
                if (cjk ? lower.contains(word) : Pattern.compile("(?<![\\p{L}\\p{Nd}])" + Pattern.quote(word) + "(?![\\p{L}\\p{Nd}])").matcher(lower).find()) {
                    found.add(category + ":" + word); break;
                }
            }
        });
        return found;
    }

    private void text(ShootPlan plan, List<LocalizedMessage> violations, List<LocalizedMessage> unverified) {
        if (plan.summary().length() > MAX_SUMMARY || plan.changeSummary().length() > MAX_SUMMARY) violations.add(LocalizedMessage.of("plan.violation.text", LocalizedMessage.of("plan.section.summary")));
        var lists = new LinkedHashMap<String, List<String>>();
        lists.put("preparation", plan.preparation()); lists.put("checklist", plan.checklist()); lists.put("shotPriorities", plan.shotPriorities());
        lists.put("tradeOffs", plan.tradeOffs()); lists.put("equipmentNotes", plan.equipmentNotes()); lists.put("limitations", plan.limitations());
        lists.forEach((section, items) -> {
            if (items.size() > MAX_ITEMS + (section.equals("limitations") ? MAX_ITEMS : 0)) violations.add(LocalizedMessage.of("plan.violation.tooMany", LocalizedMessage.of("plan.section." + section), MAX_ITEMS));
            if (items.stream().anyMatch(item -> item.isBlank() || item.length() > MAX_TEXT)) violations.add(LocalizedMessage.of("plan.violation.text", LocalizedMessage.of("plan.section." + section)));
        });
        plan.strategy().forEach((aspect, value) -> {
            if (!ShootPlan.STRATEGY.contains(aspect) || value.isBlank() || value.length() > MAX_SUMMARY) violations.add(LocalizedMessage.of("plan.violation.text", LocalizedMessage.of("plan.section.strategy")));
        });
        if (plan.sessionInfo().keySet().stream().anyMatch(key -> !ShootPlan.SESSION_INFO.contains(key))) violations.add(LocalizedMessage.of("plan.violation.text", LocalizedMessage.of("plan.section.sessionInfo")));
        // Packing equipment comes from the loadout itself; the checklist may only hold other items.
        for (String item : plan.checklist()) if (!gearMentions(item).isEmpty()) violations.add(LocalizedMessage.of("plan.violation.checklistGear", item));
        var carried = new HashSet<String>();
        plan.loadout().ifPresent(l -> l.gear().forEach(g -> carried.add(g.type())));
        var free = new ArrayList<String>(plan.preparation()); free.addAll(plan.tradeOffs()); free.addAll(plan.equipmentNotes()); free.addAll(plan.strategy().values());
        var reported = new HashSet<String>();
        for (String item : free) for (String mention : gearMentions(item)) {
            String category = mention.substring(0, mention.indexOf(':'));
            if (!carried.contains(category) && reported.add(mention)) unverified.add(LocalizedMessage.of("plan.unverified.gearMention", mention.substring(mention.indexOf(':') + 1)));
        }
    }

    private static Optional<Double> number(GearSnapshot item, String spec) {
        try {
            double value = Double.parseDouble(Objects.toString(item.specs().get(spec), "").strip());
            return Double.isFinite(value) && value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException missing) { return Optional.empty(); }
    }
    private static List<LocalizedMessage> distinct(List<LocalizedMessage> messages) { return List.copyOf(new LinkedHashSet<>(messages)); }
}
