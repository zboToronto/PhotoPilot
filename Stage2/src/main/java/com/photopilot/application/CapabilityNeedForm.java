package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.CapabilityNeed;
import com.photopilot.model.EquipmentRequirementProfile;
import java.math.BigDecimal;
import java.util.*;

/**
 * Shared GUI/CLI text format for F06 capability needs and proposals. Parsing and formatting only: the deterministic
 * rules live in {@link CapabilityNeed} and {@link EquipmentRequirementProfile}, AI conversion in RequirementService.
 */
public final class CapabilityNeedForm {
    private CapabilityNeedForm() { }
    public static final List<String> FIELDS = List.of("capability", "minimum", "maximum", "priority", "mandatory", "note");
    public static final String DEFAULT_PRIORITY = "5";

    /** One need from field text. Blank priority means 5 and blank mandatory means mandatory (never silently optional). */
    public static Result<CapabilityNeed> parse(Map<String, String> values) {
        for (String key : values.keySet()) if (!FIELDS.contains(key)) return Result.failureKey("error.cli.option", key);
        String capability = text(values, "capability");
        if (!CapabilityNeed.SUPPORTED.containsKey(capability)) return Result.failureKey("needs.error.capability", capability.isEmpty() ? "-" : capability);
        try {
            var minimum = number(values, "minimum", capability); var maximum = number(values, "maximum", capability);
            int priority;
            try { priority = Integer.parseInt(text(values, "priority").isEmpty() ? DEFAULT_PRIORITY : text(values, "priority")); }
            catch (NumberFormatException error) { return Result.failureKey("needs.error.priority", CapabilityNeed.MAX_PRIORITY); }
            String mandatory = text(values, "mandatory").toLowerCase(Locale.ROOT);
            if (!Set.of("", "yes", "no", "true", "false").contains(mandatory)) return Result.failureKey("needs.error.mandatory");
            CapabilityNeed need;
            try { need = CapabilityNeed.of(capability, minimum, maximum, priority, !mandatory.equals("no") && !mandatory.equals("false"), text(values, "note")); }
            catch (IllegalArgumentException reversed) { return Result.failureKey("needs.error.range", LocalizedMessage.of("needs.capability." + capability)); }
            return need.validate();
        } catch (LocalizedException error) { return Result.failure(error); }
    }

    /** Draft with {@code need} added, or replacing the need for the same capability; always unreviewed, provenance kept. */
    public static EquipmentRequirementProfile put(EquipmentRequirementProfile profile, CapabilityNeed need) {
        var base = profile == null ? new EquipmentRequirementProfile(List.of(), false) : profile;
        var needs = new ArrayList<CapabilityNeed>();
        boolean replaced = false;
        for (CapabilityNeed existing : base.needs()) {
            if (existing.capability().equals(need.capability())) { needs.add(need); replaced = true; } else needs.add(existing);
        }
        if (!replaced) needs.add(need);
        return base.withNeeds(needs);
    }
    public static EquipmentRequirementProfile remove(EquipmentRequirementProfile profile, String capability) {
        return profile.withNeeds(profile.needs().stream().filter(n -> !n.capability().equals(capability)).toList());
    }

    /** Saved or draft profile: provenance, one line per need and the reviewed trade-off explanations. */
    public static List<String> profile(EquipmentRequirementProfile profile, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("needs.origin." + profile.origin()));
        describe(profile, l).forEach(line -> lines.add("  " + line));
        if (!profile.tradeOffs().isEmpty()) {
            lines.add(l.text("needs.tradeOffs"));
            profile.tradeOffs().forEach(text -> lines.add("  - " + text));
        }
        return lines;
    }

    /** What the user must see before confirming a proposal, besides the needs themselves. Empty sections are omitted. */
    public static List<String> review(com.photopilot.service.CapabilityProposal proposal, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (proposal.environmentUsed()) lines.add(l.text("needs.review.environment"));
        else if (proposal.profile().aiAssisted()) lines.add(l.text("needs.review.noEnvironment")); // F09 results last only while the app runs
        if (!proposal.adjustments().isEmpty()) {
            lines.add(l.text("needs.review.adjustments"));
            proposal.adjustments().forEach(message -> lines.add("  - " + message.render(l)));
        }
        if (!proposal.unsupportedNeeds().isEmpty()) {
            lines.add(l.text("needs.review.unsupported"));
            proposal.unsupportedNeeds().forEach(text -> lines.add("  - " + text));
        }
        if (!proposal.missingFields().isEmpty())
            lines.add(l.text("needs.review.missing", String.join(", ", proposal.missingFields().stream().map(f -> l.text("requirement.field." + f)).toList())));
        return lines;
    }

    /** Questions of the proposal, numbered for answering. */
    public static List<String> questions(com.photopilot.service.CapabilityProposal proposal) {
        var lines = new ArrayList<String>();
        for (int i = 0; i < proposal.clarificationQuestions().size(); i++) lines.add((i + 1) + ". " + proposal.clarificationQuestions().get(i));
        return lines;
    }

    /** Field text of an existing need, for editing it again. */
    public static Map<String, String> format(CapabilityNeed need) {
        var values = new LinkedHashMap<String, String>();
        values.put("capability", need.capability());
        values.put("minimum", need.minimum().map(CapabilityNeedForm::number).orElse(""));
        values.put("maximum", need.maximum().map(CapabilityNeedForm::number).orElse(""));
        values.put("priority", String.valueOf(need.priority()));
        values.put("mandatory", need.hardConstraint() ? "yes" : "no");
        values.put("note", need.rationale());
        return values;
    }

    public static String describe(CapabilityNeed need, LocalizationService l) {
        String name = l.text("needs.capability." + need.capability());
        String bounds = need.minimum().isPresent() && need.minimum().equals(need.maximum())
            ? l.text("needs.bounds.exact", number(need.minimum().get()), unit(need.capability(), l))
            : need.minimum().isPresent() && need.maximum().isPresent()
            ? l.text("needs.bounds.range", number(need.minimum().get()), number(need.maximum().get()), unit(need.capability(), l))
            : need.minimum().map(v -> l.text("needs.bounds.min", number(v), unit(need.capability(), l)))
                .orElseGet(() -> need.maximum().map(v -> l.text("needs.bounds.max", number(v), unit(need.capability(), l))).orElse(l.text("needs.bounds.required")));
        String line = l.text("needs.need", name, bounds, need.priority(), l.text(need.hardConstraint() ? "needs.mandatory" : "needs.optional"));
        return need.rationale().isBlank() ? line : line + " - " + need.rationale();
    }
    public static List<String> describe(EquipmentRequirementProfile profile, LocalizationService l) {
        return profile.needs().stream().map(need -> describe(need, l)).toList();
    }
    /** Localized capability names with their accepted bounds, for help text and choices. */
    public static String capabilityHelp(String capability, LocalizationService l) {
        var definition = CapabilityNeed.SUPPORTED.get(capability);
        String unit = unit(capability, l);
        String accepted = definition.minimum() && definition.maximum() ? l.text("needs.accepts.both", unit)
            : definition.minimum() ? l.text("needs.accepts.min", unit)
            : definition.maximum() ? l.text("needs.accepts.max", unit) : l.text("needs.accepts.none");
        return capability + " - " + l.text("needs.capability." + capability) + " (" + accepted + ")";
    }

    private static Optional<Double> number(Map<String, String> values, String key, String capability) {
        String value = text(values, key).replaceFirst("(?i)^f/", "");
        if (value.isEmpty()) return Optional.empty();
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return Optional.of(parsed);
        } catch (NumberFormatException error) {
            throw new LocalizedException("needs.error.number", LocalizedMessage.of("needs.field." + key), LocalizedMessage.of("needs.capability." + capability));
        }
    }
    /** Display name of the capability's fixed unit; the stored unit code is unchanged. */
    private static String unit(String capability, LocalizationService l) {
        return CapabilityNeed.SUPPORTED.get(capability).hasBounds() ? l.text("needs.unit." + capability) : "";
    }
    private static String text(Map<String, String> values, String key) { return Objects.toString(values.get(key), "").strip(); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
