package com.photopilot.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 40 (zero-based): {F06; e.g. aperture, AF, burst, stabilization}
 * One capability-only requirement (no product identity). Only the capabilities in {@link #SUPPORTED} can be
 * checked deterministically by F07; each has a fixed unit and the bounds that are meaningful for it.
 */
public class CapabilityNeed {
    /** Unit and accepted bounds of a supported capability; {@code integral} counts physical units. */
    public record Definition(String unit, boolean minimum, boolean maximum, boolean integral, double limit) {
        public boolean hasBounds() { return minimum || maximum; }
    }
    public static final int MAX_PRIORITY = 10, MAX_RATIONALE = 500;
    /** Deterministically checkable capabilities, in display order. */
    public static final Map<String, Definition> SUPPORTED = supported();
    private static Map<String, Definition> supported() {
        var values = new LinkedHashMap<String, Definition>();
        values.put("focalLength", new Definition("mm", true, true, false, 2000));
        values.put("maxAperture", new Definition("f-number", false, true, false, 64));
        values.put("autofocus", new Definition("required", false, false, false, 0));
        values.put("burstRate", new Definition("fps", true, false, false, 120));
        values.put("stabilization", new Definition("required", false, false, false, 0));
        values.put("resolution", new Definition("MP", true, false, false, 400));
        values.put("cameraCount", new Definition("bodies", true, true, true, ShootRequirement.MAX_CAMERAS));
        values.put("lensCount", new Definition("lenses", true, true, true, 20));
        values.put("totalWeight", new Definition("g", false, true, false, ShootRequirement.MAX_WEIGHT_GRAMS));
        values.put("storageCapacity", new Definition("GB", true, false, false, 100_000));
        values.put("storageWriteSpeed", new Definition("MB/s", true, false, false, 10_000));
        values.put("batteryCount", new Definition("batteries", true, false, true, 50));
        return java.util.Collections.unmodifiableMap(values);
    }

    private String capability;
    private Optional<Double> minimum;
    private Optional<Double> maximum;
    private String unit;
    private int priority;
    private boolean hardConstraint;
    private String rationale;

    public CapabilityNeed(String capability, Optional<Double> minimum, Optional<Double> maximum, String unit, int priority, boolean hardConstraint, String rationale) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.priority = priority;
        this.hardConstraint = hardConstraint;
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        if (minimum.filter(value -> !Double.isFinite(value)).isPresent()
                || maximum.filter(value -> !Double.isFinite(value)).isPresent()
                || (minimum.isPresent() && maximum.isPresent() && minimum.get() > maximum.get()))
            throw new IllegalArgumentException("Invalid capability bounds.");
    }

    /** Need for a supported capability with its defined unit; unknown capabilities keep an empty unit and fail validation. */
    public static CapabilityNeed of(String capability, Optional<Double> minimum, Optional<Double> maximum, int priority, boolean hardConstraint, String rationale) {
        var definition = SUPPORTED.get(capability);
        return new CapabilityNeed(capability, minimum, maximum, definition == null ? "" : definition.unit(), priority, hardConstraint, rationale);
    }

    /** Deterministic findings: supported capability, fixed unit, meaningful bounds, priority and note length. */
    public List<LocalizedMessage> problems() {
        var found = new ArrayList<LocalizedMessage>();
        var definition = SUPPORTED.get(capability);
        if (definition == null) { found.add(LocalizedMessage.of("needs.error.capability", capability)); return found; }
        String name = "needs.capability." + capability;
        if (!unit.equals(definition.unit())) found.add(LocalizedMessage.of("needs.error.unit", LocalizedMessage.of(name), definition.unit()));
        if (minimum.isPresent() && !definition.minimum() || maximum.isPresent() && !definition.maximum())
            found.add(LocalizedMessage.of("needs.error.bound", LocalizedMessage.of(name)));
        if (definition.hasBounds() && minimum.isEmpty() && maximum.isEmpty())
            found.add(LocalizedMessage.of("needs.error.boundRequired", LocalizedMessage.of(name)));
        for (var value : List.of(minimum, maximum)) {
            if (value.isEmpty()) continue;
            double number = value.get();
            if (number <= 0 || number > definition.limit()) found.add(LocalizedMessage.of("needs.error.value", LocalizedMessage.of(name), definition.limit()));
            else if (definition.integral() && number != Math.rint(number)) found.add(LocalizedMessage.of("needs.error.integer", LocalizedMessage.of(name)));
        }
        if (priority < 1 || priority > MAX_PRIORITY) found.add(LocalizedMessage.of("needs.error.priority", MAX_PRIORITY));
        if (rationale.length() > MAX_RATIONALE) found.add(LocalizedMessage.of("needs.error.rationale", MAX_RATIONALE));
        return found;
    }

    public Result<CapabilityNeed> validate() {
        var found = problems();
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }

    public String capability() { return capability; }
    public Optional<Double> minimum() { return minimum; }
    public Optional<Double> maximum() { return maximum; }
    public String unit() { return unit; }
    public int priority() { return priority; }
    public boolean hardConstraint() { return hardConstraint; }
    public String rationale() { return rationale; }
}
