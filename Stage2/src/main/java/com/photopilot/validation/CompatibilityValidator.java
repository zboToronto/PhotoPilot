package com.photopilot.validation;

import java.math.BigDecimal;
import java.util.*;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CapabilityNeed;
import com.photopilot.model.CompatibilityReport;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearInventory;
import com.photopilot.model.GearMatchReport.NeedResult;
import com.photopilot.model.GearMatchReport.NeedStatus;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 58 (zero-based): {F07; deterministic mount/filter/battery/storage rules}
 * UC14: the final authority on physical compatibility, independent of any AI. It reads only recorded F05
 * specifications (via immutable snapshots). Missing data, including placeholder text such as "n/a" or "未知"
 * (SpecificationValues), is UNVERIFIED and never treated as compatible, even when both sides match; no
 * adapter, step ring or interchangeable battery is ever assumed, because the inventory model records none.
 */
public class CompatibilityValidator {
    public enum Status { COMPATIBLE, INCOMPATIBLE, UNVERIFIED }
    /** Outcome of one equipment relationship with the reason shown to the photographer. */
    public record Check(Status status, LocalizedMessage reason) { }
    /** Locale-neutral UC14 findings; essentials are missing power/storage for a body (a partial, not a conflict). */
    public record Assessment(List<LocalizedMessage> violations, List<LocalizedMessage> unverified, List<LocalizedMessage> essentials) {
        public boolean usable() { return violations.isEmpty() && unverified.isEmpty(); }
    }

    public CompatibilityValidator() {
    }

    public CompatibilityReport validate(Loadout loadout) {
        var found = assess(loadout);
        return new CompatibilityReport(found.usable(), english(found.unverified()), english(found.violations()), english(found.essentials()));
    }

    /** English descriptions of every capability need the loadout does not verifiably meet. */
    public List<String> matchNeeds(Loadout loadout, EquipmentRequirementProfile profile) {
        return english(evaluateNeeds(loadout, profile).stream().filter(result -> result.status() != NeedStatus.MET).map(NeedResult::detail).toList());
    }

    /** Relationship between two snapshots, or empty when the pair has no physical rule (e.g. lens and battery). */
    public Optional<Check> check(GearSnapshot first, GearSnapshot second) {
        var pair = new EnumMap<EquipmentType, GearSnapshot>(EquipmentType.class);
        pair.put(type(first), first); pair.put(type(second), second);
        if (pair.size() != 2) return Optional.empty();
        GearSnapshot camera = pair.get(EquipmentType.CAMERA), lens = pair.get(EquipmentType.LENS);
        if (camera != null && lens != null) return Optional.of(mount(camera, lens));
        if (lens != null && pair.containsKey(EquipmentType.FILTER)) return Optional.of(filter(lens, pair.get(EquipmentType.FILTER)));
        if (camera != null && pair.containsKey(EquipmentType.BATTERY))
            return Optional.of(listed(camera, pair.get(EquipmentType.BATTERY), "batteryTypes", "batteryType", "battery"));
        if (camera != null && pair.containsKey(EquipmentType.STORAGEMEDIA))
            return Optional.of(listed(camera, pair.get(EquipmentType.STORAGEMEDIA), "storageFormats", "format", "storage"));
        return Optional.empty();
    }

    /** Full UC14 check of one loadout's internal relationships. */
    public Assessment assess(Loadout loadout) {
        var violations = new ArrayList<LocalizedMessage>(); var unverified = new ArrayList<LocalizedMessage>(); var essentials = new ArrayList<LocalizedMessage>();
        var cameras = loadout.ofType(EquipmentType.CAMERA); var lenses = loadout.ofType(EquipmentType.LENS);
        if (cameras.isEmpty()) violations.add(LocalizedMessage.of("compat.noCamera"));
        if (lenses.isEmpty()) violations.add(LocalizedMessage.of("compat.noLens"));
        var byId = new HashMap<UUID, GearSnapshot>();
        loadout.gear().forEach(item -> byId.put(item.sourceGearId(), item));
        var mounted = new HashMap<UUID, Integer>();
        loadout.cameraLensAssignments().forEach((cameraId, lensId) -> {
            GearSnapshot camera = byId.get(cameraId), lens = byId.get(lensId);
            if (type(camera) != EquipmentType.CAMERA || type(lens) != EquipmentType.LENS) { violations.add(LocalizedMessage.of("compat.assignment")); return; }
            mounted.merge(lensId, 1, Integer::sum);
            var check = mount(camera, lens);
            if (check.status() == Status.INCOMPATIBLE) violations.add(check.reason());
            if (check.status() == Status.UNVERIFIED) unverified.add(check.reason());
        });
        mounted.forEach((lensId, count) -> {
            if (count > byId.get(lensId).selectedQuantity()) violations.add(LocalizedMessage.of("compat.lensMountedTwice", name(byId.get(lensId))));
        });
        // Every carried item must fit at least one partner in this loadout; otherwise it is a conflict or unverified.
        for (GearSnapshot lens : lenses) fits(lens, cameras, violations, unverified);
        for (GearSnapshot filter : loadout.ofType(EquipmentType.FILTER)) fits(filter, lenses, violations, unverified);
        for (GearSnapshot battery : loadout.ofType(EquipmentType.BATTERY)) fits(battery, cameras, violations, unverified);
        for (GearSnapshot storage : loadout.ofType(EquipmentType.STORAGEMEDIA)) fits(storage, cameras, violations, unverified);
        for (GearSnapshot camera : cameras) {
            if (loadout.ofType(EquipmentType.BATTERY).stream().noneMatch(b -> mountedStatus(camera, b) == Status.COMPATIBLE))
                essentials.add(LocalizedMessage.of("compat.noBattery", name(camera)));
            if (loadout.ofType(EquipmentType.STORAGEMEDIA).stream().noneMatch(s -> mountedStatus(camera, s) == Status.COMPATIBLE))
                essentials.add(LocalizedMessage.of("compat.noStorage", name(camera)));
        }
        return new Assessment(distinct(violations), distinct(unverified), distinct(essentials));
    }

    /** Current-inventory check before saving: every item still owned, unchanged, and not over-allocated. */
    public List<LocalizedMessage> availability(Loadout loadout, GearInventory inventory) {
        var found = new ArrayList<LocalizedMessage>();
        for (GearSnapshot item : loadout.gear()) {
            var current = inventory.find(item.sourceGearId());
            if (current.isEmpty()) { found.add(LocalizedMessage.of("compat.itemMissing", name(item))); continue; }
            var now = current.get().snapshot();
            if (!now.type().equals(item.type()) || !now.brand().equals(item.brand()) || !now.model().equals(item.model()) || !now.specs().equals(item.specs()))
                found.add(LocalizedMessage.of("compat.itemChanged", name(item)));
            else if (item.selectedQuantity() > now.selectedQuantity())
                found.add(LocalizedMessage.of("compat.quantity", name(item), item.selectedQuantity(), now.selectedQuantity()));
        }
        return found;
    }

    /** Compares the loadout's verified values with each capability need. Unknown specifications are never assumed. */
    public static List<NeedResult> evaluateNeeds(Loadout loadout, EquipmentRequirementProfile profile) {
        var results = new ArrayList<NeedResult>();
        if (profile == null) return results;
        for (CapabilityNeed need : profile.needs()) results.add(evaluate(loadout, need));
        return results;
    }

    private static NeedResult evaluate(Loadout loadout, CapabilityNeed need) {
        var cameras = loadout.ofType(EquipmentType.CAMERA); var lenses = loadout.ofType(EquipmentType.LENS);
        var storage = loadout.ofType(EquipmentType.STORAGEMEDIA);
        String key = "need." + need.capability();
        return switch (need.capability()) {
            case "focalLength" -> {
                var covered = focalCoverage(loadout);
                String text = covered.isEmpty() ? "-" : String.join("; ", covered.stream().map(CompatibilityValidator::range).toList());
                double low = need.minimum().orElse(need.maximum().orElse(0.0)), high = need.maximum().orElse(need.minimum().orElse(0.0));
                if (lenses.stream().anyMatch(l -> number(l, "minFocalMm").isEmpty() || number(l, "maxFocalMm").isEmpty()))
                    yield result(need, NeedStatus.UNKNOWN, key + ".unknown", text);
                boolean met = need.minimum().isPresent() && need.maximum().isPresent()
                    ? covered.stream().anyMatch(r -> r[0] <= low && r[1] >= high)
                    : need.minimum().isPresent() ? covered.stream().anyMatch(r -> r[0] <= low) : covered.stream().anyMatch(r -> r[1] >= high);
                yield result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), bounds(need), text);
            }
            case "maxAperture" -> {
                var best = lenses.stream().map(l -> number(l, "maxAperture")).flatMap(Optional::stream).min(Double::compare);
                if (best.isEmpty()) yield result(need, NeedStatus.UNKNOWN, key + ".unknown");
                boolean met = best.get() <= need.maximum().orElseThrow();
                yield result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), num(need.maximum().orElseThrow()), num(best.get()));
            }
            case "autofocus" -> flags(need, lenses, "autofocus", key, true);
            case "stabilization" -> {
                // Each mounted body/lens pair needs in-body or optical stabilization; unknown flags stay unknown.
                if (loadout.cameraLensAssignments().isEmpty()) yield result(need, NeedStatus.UNKNOWN, key + ".unknown", "-");
                NeedStatus status = NeedStatus.MET; String missing = "";
                var byId = new HashMap<UUID, GearSnapshot>(); loadout.gear().forEach(g -> byId.put(g.sourceGearId(), g));
                for (var pair : loadout.cameraLensAssignments().entrySet()) {
                    var body = flag(byId.get(pair.getKey()), "stabilization"); var optic = flag(byId.get(pair.getValue()), "stabilization");
                    if (body.orElse(false) || optic.orElse(false)) continue;
                    if (body.isEmpty() || optic.isEmpty()) { if (status == NeedStatus.MET) status = NeedStatus.UNKNOWN; }
                    else status = NeedStatus.UNMET;
                    missing = name(byId.get(pair.getKey()));
                }
                yield result(need, status, key + "." + status.name().toLowerCase(Locale.ROOT), missing);
            }
            case "burstRate" -> best(need, cameras, "burstFps", key);
            case "resolution" -> best(need, cameras, "resolutionMP", key);
            case "cameraCount" -> count(need, loadout.units(EquipmentType.CAMERA), key);
            case "lensCount" -> count(need, loadout.units(EquipmentType.LENS), key);
            case "batteryCount" -> count(need, loadout.units(EquipmentType.BATTERY), key);
            case "totalWeight" -> {
                var weight = loadout.totalWeightGrams();
                if (weight.isEmpty()) yield result(need, NeedStatus.UNKNOWN, key + ".unknown", num(need.maximum().orElseThrow()));
                boolean met = weight.get() <= need.maximum().orElseThrow();
                yield result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), num(weight.get()), num(need.maximum().orElseThrow()));
            }
            case "storageCapacity" -> {
                double total = 0;
                for (GearSnapshot card : storage) {
                    var capacity = number(card, "capacityGB");
                    if (capacity.isEmpty()) yield result(need, NeedStatus.UNKNOWN, key + ".unknown", num(need.minimum().orElseThrow()));
                    total += capacity.get() * card.selectedQuantity();
                }
                boolean met = total >= need.minimum().orElseThrow();
                yield result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), num(total), num(need.minimum().orElseThrow()));
            }
            case "storageWriteSpeed" -> best(need, storage, "writeSpeedMBps", key);
            default -> result(need, NeedStatus.UNKNOWN, "need.unsupported", need.capability());
        };
    }
    private static NeedResult best(CapabilityNeed need, List<GearSnapshot> items, String spec, String key) {
        var best = items.stream().map(item -> number(item, spec)).flatMap(Optional::stream).max(Double::compare);
        if (best.isEmpty()) return result(need, NeedStatus.UNKNOWN, key + ".unknown", num(need.minimum().orElseThrow()));
        boolean met = best.get() >= need.minimum().orElseThrow();
        return result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), num(best.get()), num(need.minimum().orElseThrow()));
    }
    private static NeedResult count(CapabilityNeed need, int units, String key) {
        boolean met = need.minimum().filter(min -> units < min).isEmpty() && need.maximum().filter(max -> units > max).isEmpty();
        return result(need, met ? NeedStatus.MET : NeedStatus.UNMET, key + (met ? ".met" : ".unmet"), units, bounds(need));
    }
    private static NeedResult flags(CapabilityNeed need, List<GearSnapshot> items, String spec, String key, boolean required) {
        if (items.isEmpty()) return result(need, NeedStatus.UNKNOWN, key + ".unknown", "-");
        for (GearSnapshot item : items) {
            var value = flag(item, spec);
            if (value.isPresent() && value.get() != required) return result(need, NeedStatus.UNMET, key + ".unmet", name(item));
        }
        for (GearSnapshot item : items) if (flag(item, spec).isEmpty()) return result(need, NeedStatus.UNKNOWN, key + ".unknown", name(item));
        return result(need, NeedStatus.MET, key + ".met");
    }
    private static NeedResult result(CapabilityNeed need, NeedStatus status, String key, Object... arguments) {
        return new NeedResult(need, status, LocalizedMessage.of(key, arguments));
    }

    /** Merged closed focal intervals of the loadout's lenses; recorded values only, no tolerance. */
    public static List<double[]> focalCoverage(Loadout loadout) {
        var ranges = new ArrayList<double[]>();
        for (GearSnapshot lens : loadout.ofType(EquipmentType.LENS)) {
            var low = number(lens, "minFocalMm"); var high = number(lens, "maxFocalMm");
            if (low.isPresent() && high.isPresent()) ranges.add(new double[]{low.get(), high.get()});
        }
        ranges.sort(Comparator.<double[]>comparingDouble(r -> r[0]).thenComparingDouble(r -> r[1]));
        var merged = new ArrayList<double[]>();
        for (double[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.getLast()[1]) merged.getLast()[1] = Math.max(merged.getLast()[1], range[1]);
            else merged.add(range.clone());
        }
        return merged;
    }

    private void fits(GearSnapshot item, List<GearSnapshot> partners, List<LocalizedMessage> violations, List<LocalizedMessage> unverified) {
        Check firstProblem = null;
        for (GearSnapshot partner : partners) {
            var check = check(item, partner).orElseThrow();
            if (check.status() == Status.COMPATIBLE) return;
            if (firstProblem == null || check.status() == Status.UNVERIFIED && firstProblem.status() == Status.INCOMPATIBLE) firstProblem = check;
        }
        if (firstProblem == null) return; // No partner type present: reported as noCamera/noLens.
        (firstProblem.status() == Status.UNVERIFIED ? unverified : violations).add(firstProblem.reason());
    }
    private Status mountedStatus(GearSnapshot camera, GearSnapshot accessory) { return check(camera, accessory).map(Check::status).orElse(Status.UNVERIFIED); }

    private static Check mount(GearSnapshot camera, GearSnapshot lens) {
        String body = text(camera, "mount"), optic = text(lens, "mount");
        if (unknown(body) || unknown(optic)) return new Check(Status.UNVERIFIED, LocalizedMessage.of("compat.mountUnknown", name(lens), name(camera)));
        if (body.equalsIgnoreCase(optic)) return new Check(Status.COMPATIBLE, LocalizedMessage.of("compat.mountMatch", name(lens), name(camera), optic));
        return new Check(Status.INCOMPATIBLE, LocalizedMessage.of("compat.mountMismatch", name(lens), optic, name(camera), body));
    }
    private static Check filter(GearSnapshot lens, GearSnapshot filter) {
        var thread = number(lens, "filterDiameterMm"); var size = number(filter, "diameterMm");
        if (thread.isEmpty() || size.isEmpty()) return new Check(Status.UNVERIFIED, LocalizedMessage.of("compat.filterUnknown", name(filter), name(lens)));
        if (Math.abs(thread.get() - size.get()) < 1e-9) return new Check(Status.COMPATIBLE, LocalizedMessage.of("compat.filterMatch", name(filter), name(lens), num(size.get())));
        return new Check(Status.INCOMPATIBLE, LocalizedMessage.of("compat.filterMismatch", name(filter), num(size.get()), name(lens), num(thread.get())));
    }
    /** Battery type / storage format must be listed exactly (case-insensitive) by the camera; names alone prove nothing. */
    private static Check listed(GearSnapshot camera, GearSnapshot item, String cameraSpec, String itemSpec, String kind) {
        var supported = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String value : text(camera, cameraSpec).split(",")) if (!unknown(value)) supported.add(value.strip());
        String value = text(item, itemSpec);
        if (supported.isEmpty() || unknown(value)) return new Check(Status.UNVERIFIED, LocalizedMessage.of("compat." + kind + "Unknown", name(item), name(camera)));
        if (supported.contains(value)) return new Check(Status.COMPATIBLE, LocalizedMessage.of("compat." + kind + "Match", name(item), name(camera), value));
        return new Check(Status.INCOMPATIBLE, LocalizedMessage.of("compat." + kind + "Mismatch", name(item), value, name(camera), String.join(", ", supported)));
    }

    private static EquipmentType type(GearSnapshot item) {
        try { return item == null ? null : EquipmentType.valueOf(item.type()); } catch (IllegalArgumentException unknown) { return null; }
    }
    private static boolean unknown(String value) { return SpecificationValues.unknown(value); }
    private static String text(GearSnapshot item, String spec) { return Objects.toString(item.specs().get(spec), "").strip(); }
    private static Optional<Double> number(GearSnapshot item, String spec) {
        try {
            double value = Double.parseDouble(text(item, spec));
            return Double.isFinite(value) && value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException missing) { return Optional.empty(); }
    }
    private static Optional<Boolean> flag(GearSnapshot item, String spec) {
        String value = item == null ? "" : text(item, spec);
        return value.equals("true") ? Optional.of(true) : value.equals("false") ? Optional.of(false) : Optional.empty();
    }
    public static String name(GearSnapshot item) { return item.brand() + " " + item.model(); }
    public static String num(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    public static String range(double[] range) { return num(range[0]) + (range[0] == range[1] ? "" : "-" + num(range[1])) + " mm"; }
    private static String bounds(CapabilityNeed need) {
        if (need.minimum().isPresent() && need.maximum().isPresent())
            return need.minimum().get().equals(need.maximum().get()) ? num(need.minimum().get()) : num(need.minimum().get()) + "-" + num(need.maximum().get());
        return need.minimum().map(v -> ">= " + num(v)).orElseGet(() -> need.maximum().map(v -> "<= " + num(v)).orElse("-"));
    }
    private static List<LocalizedMessage> distinct(List<LocalizedMessage> messages) { return List.copyOf(new LinkedHashSet<>(messages)); }
    public static List<String> english(List<LocalizedMessage> messages) {
        return messages.stream().map(message -> EnglishMessages.text(message.key(), message.arguments().toArray())).toList();
    }
}
