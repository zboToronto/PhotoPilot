package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** F04 review text shared by GUI and CLI; formatting only, no business rules. */
public final class GearImportPresentation {
    private static final DateTimeFormatter RETRIEVED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private GearImportPresentation() { }

    public static String fieldName(String key, LocalizationService l) {
        return key.equals("type") ? l.text("gear.type") : l.text("gear.field." + key);
    }
    /** Status with its source, e.g. "Externally verified (Lensfun)". */
    public static String status(GearImportDraft.Field field, LocalizationService l) {
        String status = l.text("import.status." + field.status().name());
        return field.status() == SpecificationStatus.EXTERNALLY_VERIFIED && !field.source().isBlank() ? l.text("import.statusSource", status, field.source()) : status;
    }
    public static String field(String key, GearImportDraft.Field field, LocalizationService l) {
        return l.text("import.fieldLine", fieldName(key, l), field.value().isBlank() ? l.text("gear.unknown") : field.value(), status(field, l));
    }
    public static String candidate(int index, GearImportDraft.Candidate candidate, LocalizationService l) {
        return l.text("import.candidate", index + 1, candidate.name(), l.text("gear.type." + candidate.type().name()));
    }
    public static List<String> candidateDetails(GearImportDraft.Candidate candidate, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (!candidate.modelCode().isBlank()) lines.add(l.text("import.candidateCode", candidate.modelCode()));
        if (!candidate.clues().isEmpty()) lines.add(l.text("import.candidateClues", String.join("; ", candidate.clues())));
        if (!candidate.distinguishing().isBlank()) lines.add(l.text("import.candidateDistinguishing", candidate.distinguishing()));
        if (!candidate.visibleValues().isEmpty()) lines.add(l.text("import.candidateVisible", String.join(", ", candidate.visibleValues().entrySet().stream()
            .map(entry -> fieldName(entry.getKey(), l) + " " + entry.getValue()).toList())));
        return lines;
    }
    /** Identity, ambiguity, recognition notes and the product text read from the image. */
    public static List<String> summary(GearImportDraft draft, LocalizationService l) {
        var lines = new ArrayList<String>();
        String products = String.join("; ", draft.evidence().stream().map(record -> record.source() + ": " + record.product()).toList());
        lines.add(l.text("import.identity." + draft.identity().name(), products));
        if (draft.ambiguous() && !draft.selectionMade()) lines.add(l.text("import.ambiguous"));
        if (!draft.uncertainty().isBlank()) lines.add(l.text("import.uncertainty", draft.uncertainty()));
        if (!draft.visibleText().isEmpty()) lines.add(l.text("import.visibleText", String.join(" | ", draft.visibleText())));
        return lines;
    }
    public static List<String> evidence(GearImportDraft draft, LocalizationService l) {
        if (draft.evidence().isEmpty()) return List.of(l.text("import.noEvidence"));
        return draft.evidence().stream().map(record -> l.text("import.evidence", record.source(), record.product(), record.url(),
            RETRIEVED.format(record.retrievedAt()))).toList();
    }
    public static List<String> conflicts(GearImportDraft draft, LocalizationService l) {
        return draft.fieldConflicts().stream().map(conflict -> l.text("import.conflict", fieldName(conflict.field(), l), String.join("; ", conflict.claims()))).toList();
    }
    public static List<String> notices(GearImportDraft draft, LocalizationService l) {
        return draft.notices().stream().map(notice -> notice.render(l)).toList();
    }
    public static String problems(GearImportDraft draft, LocalizationService l) {
        var problems = draft.problems();
        return problems.isEmpty() ? l.text("import.valid") : l.text("import.problems", String.join(" ", problems.stream().map(problem -> problem.render(l)).toList()));
    }
    public static String unverified(GearImportDraft draft, LocalizationService l) {
        return String.join(", ", draft.unverifiedCritical().stream().map(key -> fieldName(key, l)).toList());
    }
    public static String duplicate(Equipment item, LocalizationService l) {
        return l.text("import.duplicate", item.brand(), item.model(), item.quantity(), item.id());
    }
    /** Complete review used by the CLI. */
    public static List<String> describe(GearImportDraft draft, LocalizationService l) {
        var lines = new ArrayList<>(summary(draft, l));
        var candidates = draft.recognizedCandidates();
        if (!candidates.isEmpty()) {
            lines.add(l.text("import.candidates"));
            for (int i = 0; i < candidates.size(); i++) {
                lines.add((i == draft.selectedIndex() ? "* " : "  ") + candidate(i, candidates.get(i), l));
                candidateDetails(candidates.get(i), l).forEach(detail -> lines.add("     " + detail));
            }
        }
        lines.add(l.text("import.typeLine", fieldName("type", l), draft.type() == null ? l.text("gear.unknown") : l.text("gear.type." + draft.type().name())));
        draft.fields().forEach((key, field) -> lines.add("  " + field(key, field, l)));
        var conflicts = conflicts(draft, l);
        if (!conflicts.isEmpty()) { lines.add(l.text("import.conflictsTitle")); conflicts.forEach(line -> lines.add("  " + line)); }
        lines.add(l.text("import.evidenceTitle"));
        evidence(draft, l).forEach(line -> lines.add("  " + line));
        lines.addAll(notices(draft, l));
        lines.add(problems(draft, l));
        return lines;
    }
}
