package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EquipmentRequirementProfile;

/**
 * F06 review package: an unreviewed profile draft plus what the user must see before confirming. Nothing here is
 * saved; only {@link PlanningService#confirmProfile} persists the (possibly edited) profile after revalidation.
 *
 * @param profile unreviewed draft (Gemini-proposed or manual)
 * @param clarificationQuestions model questions about materially missing information
 * @param unsupportedNeeds relevant needs the supported capabilities cannot express (shown, never checked by F07)
 * @param adjustments deterministic changes made to the model output (dropped items, preserved user constraints)
 * @param missingFields confirmed-requirement fields that are unspecified and affect capability sizing
 * @param environmentUsed whether an F09 assessment from this run was part of the evidence
 */
public record CapabilityProposal(EquipmentRequirementProfile profile, List<String> clarificationQuestions, List<String> unsupportedNeeds,
        List<LocalizedMessage> adjustments, List<String> missingFields, boolean environmentUsed) {
    public CapabilityProposal {
        Objects.requireNonNull(profile, "profile");
        clarificationQuestions = List.copyOf(clarificationQuestions); unsupportedNeeds = List.copyOf(unsupportedNeeds);
        adjustments = List.copyOf(adjustments); missingFields = List.copyOf(missingFields);
    }

    /** Manual entry: the draft alone, with no model output to review. */
    public static CapabilityProposal manual(EquipmentRequirementProfile draft, List<String> missingFields) {
        return new CapabilityProposal(draft.asDraft(), List.of(), List.of(), List.of(), missingFields, false);
    }

    /** Same review context around an edited draft (manual edits are kept for clarification and confirmation). */
    public CapabilityProposal withProfile(EquipmentRequirementProfile edited) {
        return new CapabilityProposal(edited.asDraft(), clarificationQuestions, unsupportedNeeds, adjustments, missingFields, environmentUsed);
    }
}
