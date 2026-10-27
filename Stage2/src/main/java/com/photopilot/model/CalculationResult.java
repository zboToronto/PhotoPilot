package com.photopilot.model;

import com.photopilot.i18n.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 1 UML element 92 (zero-based): {F08; reproducible inputs retained}
 * Immutable numerical evidence with explicit units and assumptions.
 */
public class CalculationResult {
    private final Map<String, Double> values;
    private final Map<String, String> units;
    private final boolean estimated;
    private final List<String> assumptions;
    private final CalculationInput input;
    private List<LocalizedMessage> localizedAssumptions;

    public CalculationResult(Map<String, Double> values, Map<String, String> units, boolean estimated, List<String> assumptions, CalculationInput input) {
        this.values = Map.copyOf(values);
        this.units = Map.copyOf(units);
        this.estimated = estimated;
        this.assumptions = List.copyOf(assumptions);
        this.localizedAssumptions = assumptions.stream().map(note -> LocalizedMessage.of("common.literal", note)).toList();
        this.input = Objects.requireNonNull(input, "input");
    }
    public static CalculationResult localized(Map<String, Double> values, Map<String, String> units,
            boolean estimated, List<LocalizedMessage> notes, CalculationInput input) {
        var result = new CalculationResult(values, units, estimated,
            notes.stream().map(note -> EnglishMessages.text(note.key(), note.arguments().toArray())).toList(), input);
        result.localizedAssumptions = List.copyOf(notes);
        return result;
    }
    public List<LocalizedMessage> localizedAssumptions() { return localizedAssumptions; }
    public Map<String, Double> values() { return values; }
    public Map<String, String> units() { return units; }
    public boolean estimated() { return estimated; }
    public List<String> assumptions() { return assumptions; }
    public CalculationInput input() { return input; }
}
