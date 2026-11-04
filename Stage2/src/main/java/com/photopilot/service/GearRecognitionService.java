package com.photopilot.service;

import java.util.*;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.EquipmentSpecificationProvider;
import com.photopilot.model.Equipment;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearImportDraft;
import com.photopilot.model.GearNames;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 38 (zero-based): {F04; no save before confirmation}
 * recognize: one structured agent step over the user-approved sanitized image only (no tools), converted into a
 * bounded, untrusted draft. verify: external specification evidence for the current identity. confirm: the
 * validated Equipment of a reviewed draft. Nothing here writes inventory.
 */
public class GearRecognitionService {
    public static final int MAX_CANDIDATES = 4;
    static final String TASK = """
        You identify photography equipment (camera bodies and interchangeable lenses) in one image for the PhotoPilot
        inventory app. The image is a privacy-sanitized copy the user approved: an equipment photo, a product label or a
        purchase screenshot. Solid black rectangles are redactions; never guess what they hide.
        Reply only with JSON that matches the response schema.
        Rules:
        1. Base every statement on what is visible in the image. visibleSpecifications may only contain values printed
           or engraved in the image (for example "24-70mm", "1:2.8", a filter-thread symbol with 82, "E-mount").
           Never add specifications from memory; use null for everything that is not visible.
        2. candidates: the most plausible exact models, best first, at most four. Add alternatives only when the visible
           evidence does not distinguish them (for example a "II" version or a regional variant) and explain in
           distinguishingDetails what would tell them apart.
        3. brand and model as the manufacturer names them. modelCode only when a model number or code is visible;
           never invent one.
        4. visualClues: short descriptions of the visible markings and features that support each candidate.
        5. ambiguous is true when more than one candidate is plausible or the identification is uncertain; explain
           briefly in uncertainty.
        6. If the image is unreadable or shows no camera or lens, set readable to false and return no candidates.
        7. visibleText: only product-relevant text you read (model names, specifications). Never transcribe personal
           information such as names, addresses, phone numbers, email addresses, order or account numbers or payment details.
        8. category: CAMERA for camera bodies, LENS for interchangeable lenses, OTHER for anything else.
        9. Never claim that a model or specification is verified; the application verifies specifications separately.
        """;
    static final String REQUEST = "Identify the camera body or lens shown in the attached image.";

    private PhotoPilotAgent agent;
    private EquipmentSpecificationProvider specifications;

    public GearRecognitionService(PhotoPilotAgent agent, EquipmentSpecificationProvider specifications) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
    }

    /** Sends only an approved, uncleared sanitized image; anything else fails before any request. */
    public Result<GearImportDraft> recognize(SanitizedImage image) {
        if (image == null || !image.userApproved() || image.cleared()) return Result.failureKey("import.error.notApproved");
        var context = new AgentContext(UUID.randomUUID(), List.of(REQUEST), "{}", List.of(), Optional.of(image))
            .withResponseSchema(GearRecognition.SCHEMA);
        var reply = agent.run(TASK, context, GearRecognition.class);
        return reply.isSuccess() ? toDraft(reply.value().orElseThrow()) : Result.failure(reply);
    }

    /**
     * Looks up external evidence for the draft's current brand, model and category. Provider outages keep the draft
     * (marked unavailable, values unverified); only a missing identity fails.
     */
    public Result<GearImportDraft> verify(GearImportDraft draft) {
        if (draft == null) return Result.failureKey("import.error.noDraft");
        if (draft.confirmed()) return Result.failureKey("import.error.alreadyImported");
        EquipmentType type = draft.type();
        String brand = draft.value("brand"), model = draft.value("model");
        if (type == null || brand.isBlank() || model.isBlank()) return Result.failureKey("import.error.identityRequired");
        if (type != EquipmentType.CAMERA && type != EquipmentType.LENS)
            return Result.success(draft.withEvidence(List.of(), GearImportDraft.Identity.NOT_FOUND,
                List.of(LocalizedMessage.of("import.notice.typeNotCovered", type.name()))));
        var found = specifications.search(type, brand, model, draft.modelCode());
        if (!found.isSuccess()) {
            var notices = new ArrayList<LocalizedMessage>(); notices.add(LocalizedMessage.of("import.notice.catalogUnavailable"));
            notices.addAll(found.messages());
            return Result.success(draft.withEvidence(List.of(), GearImportDraft.Identity.UNAVAILABLE, notices));
        }
        var records = found.value().orElseThrow();
        var notices = new ArrayList<LocalizedMessage>();
        found.warnings().forEach(source -> notices.add(LocalizedMessage.of("import.notice.sourceUnavailable", source)));
        if (records.isEmpty()) notices.add(LocalizedMessage.of("import.notice.notFound", brand, model));
        return Result.success(draft.withEvidence(records, records.isEmpty() ? GearImportDraft.Identity.NOT_FOUND : GearImportDraft.Identity.VERIFIED, notices));
    }

    /** The validated Equipment of the reviewed draft (deterministic gate); saving happens in InventoryService. */
    public Result<Equipment> confirm(GearImportDraft edited) {
        return edited == null ? Result.failureKey("import.error.noDraft") : edited.confirm();
    }

    /** Converts untrusted model output into a bounded draft; nothing invented, unusable entries dropped. */
    Result<GearImportDraft> toDraft(GearRecognition out) {
        String uncertainty = text(out.uncertainty(), 400);
        if (Boolean.FALSE.equals(out.readable())) return Result.failureKey("import.error.unrecognized", uncertainty.isEmpty() ? "-" : uncertainty);
        var candidates = new ArrayList<GearImportDraft.Candidate>();
        boolean unsupported = false;
        for (var item : list(out.candidates())) {
            if (item == null || candidates.size() >= MAX_CANDIDATES) continue;
            EquipmentType type = switch (text(item.category(), 20).toUpperCase(Locale.ROOT)) {
                case "CAMERA" -> EquipmentType.CAMERA;
                case "LENS" -> EquipmentType.LENS;
                default -> null;
            };
            if (type == null) { unsupported = true; continue; }
            String brand = text(item.brand(), 60), model = text(item.model(), 100);
            if (brand.isEmpty() || model.isEmpty()) continue;
            if (candidates.stream().anyMatch(c -> c.type() == type && GearNames.sameBrand(c.brand(), brand) && GearNames.sameModel(brand, c.model(), model))) continue;
            candidates.add(new GearImportDraft.Candidate(type, brand, model, text(item.modelCode(), 40),
                list(item.visualClues()).stream().map(clue -> text(clue, 160)).filter(clue -> !clue.isEmpty()).limit(5).toList(),
                text(item.distinguishingDetails(), 300), visible(type, item.visibleSpecifications())));
        }
        if (candidates.isEmpty()) {
            if (unsupported) return Result.failureKey("import.error.unsupportedType");
            return Result.failureKey("import.error.unrecognized", uncertainty.isEmpty() ? "-" : uncertainty);
        }
        String kind = text(out.imageKind(), 30);
        if (!Set.of("equipment_photo", "product_label", "purchase_screenshot", "other").contains(kind)) kind = "other";
        var visibleText = list(out.visibleText()).stream().map(line -> text(line, 120)).filter(line -> !line.isEmpty()).limit(10).toList();
        return Result.success(GearImportDraft.recognized(kind, visibleText, candidates, Boolean.TRUE.equals(out.ambiguous()), uncertainty));
    }

    /** Only keys defined for the category, with plausible values; invalid readings are dropped, never repaired. */
    private static Map<String, String> visible(EquipmentType type, GearRecognition.Visible values) {
        var result = new LinkedHashMap<String, String>();
        if (values == null) return result;
        var allowed = type.fields().stream().map(EquipmentType.Field::key).collect(java.util.stream.Collectors.toSet());
        put(result, allowed, "mount", text(values.mount(), 40));
        number(result, allowed, "minFocalMm", values.minFocalMm(), 2000);
        number(result, allowed, "maxFocalMm", values.maxFocalMm(), 2000);
        number(result, allowed, "maxAperture", values.maxAperture(), 64);
        number(result, allowed, "maxApertureAtMaxFocal", values.maxApertureAtMaxFocal(), 64);
        number(result, allowed, "filterDiameterMm", values.filterDiameterMm(), 200);
        number(result, allowed, "resolutionMP", values.resolutionMP(), 1000);
        if (values.autofocus() != null) put(result, allowed, "autofocus", values.autofocus().toString());
        if (values.stabilization() != null) put(result, allowed, "stabilization", values.stabilization().toString());
        if (type == EquipmentType.LENS && result.containsKey("maxFocalMm") && result.containsKey("minFocalMm")
                && Double.parseDouble(result.get("minFocalMm")) > Double.parseDouble(result.get("maxFocalMm"))) {
            result.remove("minFocalMm"); result.remove("maxFocalMm");
        }
        return result;
    }
    private static void number(Map<String, String> result, Set<String> allowed, String key, Double value, double max) {
        if (value != null && Double.isFinite(value) && value > 0 && value <= max) put(result, allowed, key, format(value));
    }
    private static void put(Map<String, String> result, Set<String> allowed, String key, String value) {
        if (allowed.contains(key) && !value.isBlank()) result.put(key, value);
    }
    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
    /** Single-line, control-character-free, bounded text. */
    static String text(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max).strip();
    }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
}
