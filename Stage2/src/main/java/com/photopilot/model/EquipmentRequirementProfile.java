package com.photopilot.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 37 (zero-based): {F06; capabilities only, no product identities}
 * Capability needs of one shoot, as proposed by Gemini (RequirementService.deriveProfile) or entered manually, plus
 * the trade-off explanations the user reviewed. Deterministic validation runs on every draft and again on
 * confirmation; only {@link #confirm()} produces a reviewed profile that F07/F10 accept.
 */
public class EquipmentRequirementProfile {
    public static final int MAX_NEEDS = 20, MAX_TRADE_OFFS = 8, MAX_TRADE_OFF = 400;
    /** Provenance: needs typed in by the user, or proposed by Gemini and then reviewed (possibly edited) by the user. */
    public static final String MANUAL = "MANUAL", AI_ASSISTED = "AI_ASSISTED";
    public static final Set<String> ORIGINS = Set.of(MANUAL, AI_ASSISTED);
    private List<CapabilityNeed> needs;
    private boolean reviewed;
    private String origin;
    private List<String> tradeOffs;

    public EquipmentRequirementProfile(List<CapabilityNeed> needs, boolean reviewed) {
        this(needs, reviewed, MANUAL, List.of());
    }

    public EquipmentRequirementProfile(List<CapabilityNeed> needs, boolean reviewed, String origin, List<String> tradeOffs) {
        this.needs = List.copyOf(needs);
        this.reviewed = reviewed;
        this.origin = Objects.requireNonNull(origin, "origin");
        this.tradeOffs = List.copyOf(tradeOffs);
    }

    /** Structural findings of the needs themselves, independent of any requirement or AI reasoning. */
    public List<LocalizedMessage> problems() {
        var found = new ArrayList<LocalizedMessage>();
        if (needs.isEmpty()) found.add(LocalizedMessage.of("needs.error.empty"));
        if (needs.size() > MAX_NEEDS) found.add(LocalizedMessage.of("needs.error.tooMany", MAX_NEEDS));
        var names = new HashSet<String>();
        for (CapabilityNeed need : needs) {
            if (!names.add(need.capability())) found.add(LocalizedMessage.of("needs.error.duplicate", LocalizedMessage.of("needs.capability." + need.capability())));
            found.addAll(need.problems());
        }
        // Every camera body needs its own lens, so a lens ceiling below the body floor cannot be satisfied.
        var lensCeiling = need("lensCount").flatMap(CapabilityNeed::maximum);
        var bodyFloor = need("cameraCount").flatMap(CapabilityNeed::minimum);
        if (lensCeiling.isPresent() && bodyFloor.isPresent() && lensCeiling.get() < bodyFloor.get())
            found.add(LocalizedMessage.of("needs.error.lensesForBodies", number(lensCeiling.get()), number(bodyFloor.get())));
        if (!ORIGINS.contains(origin)) found.add(LocalizedMessage.of("needs.error.origin"));
        if (tradeOffs.size() > MAX_TRADE_OFFS || tradeOffs.stream().anyMatch(text -> text.isBlank() || text.length() > MAX_TRADE_OFF))
            found.add(LocalizedMessage.of("needs.error.tradeOffs", MAX_TRADE_OFFS, MAX_TRADE_OFF));
        return found;
    }

    /** Checks the needs and their consistency with the confirmed shooting requirement they belong to. */
    public Result<EquipmentRequirementProfile> validate(ShootRequirement req) {
        if (req == null || !req.confirmed()) return Result.failureKey("needs.error.requirement");
        var found = problems();
        need("cameraCount").ifPresent(need -> req.cameraCount().ifPresent(count -> {
            if (need.minimum().filter(min -> count < min).isPresent() || need.maximum().filter(max -> count > max).isPresent())
                found.add(LocalizedMessage.of("needs.error.cameraConflict", count));
        }));
        need("totalWeight").flatMap(CapabilityNeed::maximum).ifPresent(max -> req.maxWeightGrams().ifPresent(limit -> {
            if (max > limit) found.add(LocalizedMessage.of("needs.error.weightConflict", limit));
        }));
        // Avoiding lens changes means one lens per body: demanding more lenses than bodies contradicts it.
        if (req.lensChangesAllowed().equals(Optional.of(false))) req.cameraCount().ifPresent(count ->
            need("lensCount").flatMap(CapabilityNeed::minimum).filter(min -> min > count)
                .ifPresent(min -> found.add(LocalizedMessage.of("needs.error.lensChangeConflict", count))));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }

    /** Explicitly confirmed (reviewed) copy; never produced by AI output alone. */
    public Result<EquipmentRequirementProfile> confirm() {
        var found = problems();
        return found.isEmpty() ? Result.success(new EquipmentRequirementProfile(needs, true, origin, tradeOffs)) : Result.failures(found);
    }

    /** Unreviewed copy for editing; the stored confirmed profile stays unchanged until a new confirmation saves. */
    public EquipmentRequirementProfile asDraft() { return new EquipmentRequirementProfile(needs, false, origin, tradeOffs); }

    /** Unreviewed copy with different needs; provenance and explanations are kept. */
    public EquipmentRequirementProfile withNeeds(List<CapabilityNeed> changed) { return new EquipmentRequirementProfile(changed, false, origin, tradeOffs); }

    public Optional<CapabilityNeed> need(String capability) {
        return needs.stream().filter(need -> need.capability().equals(capability)).findFirst();
    }
    public List<CapabilityNeed> needs() { return needs; }
    public boolean reviewed() { return reviewed; }
    public String origin() { return origin; }
    public boolean aiAssisted() { return AI_ASSISTED.equals(origin); }
    public List<String> tradeOffs() { return tradeOffs; }

    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
