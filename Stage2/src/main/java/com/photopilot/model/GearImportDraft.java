package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 39 (zero-based): {F04; specification provenance retained}
 * Reviewable, never-persisted import draft. It keeps the recognized candidates, the values read from the image or
 * entered by the user ("base" values) and the external evidence separately, and derives each field's effective value
 * and {@link SpecificationStatus} deterministically: agreeing evidence verifies a value, disagreeing sources (the
 * image reading included) make it conflicting and blank, user entries stay user-provided unless they match the
 * evidence. Editing brand, model or category discards evidence for the previous identity.
 */
public class GearImportDraft {
    /** One model proposed from the image; values only as visibly printed on the product, never from memory. */
    public record Candidate(EquipmentType type, String brand, String model, String modelCode, List<String> clues,
            String distinguishing, Map<String, String> visibleValues) {
        public Candidate {
            Objects.requireNonNull(type); brand = Objects.requireNonNullElse(brand, "").strip(); model = Objects.requireNonNullElse(model, "").strip();
            modelCode = Objects.requireNonNullElse(modelCode, "").strip(); clues = List.copyOf(clues);
            distinguishing = Objects.requireNonNullElse(distinguishing, "").strip(); visibleValues = Map.copyOf(visibleValues);
        }
        public String name() { return GearNames.tokens(model).containsAll(GearNames.tokens(brand)) ? model : brand + " " + model; }
    }
    /** Effective review value with its provenance; {@code source}/{@code sourceUrl} only for verified values. */
    public record Field(String value, SpecificationStatus status, String source, Optional<URI> sourceUrl) { }
    /** Disagreeing statements about one field, e.g. "Wikipedia: Sony E-mount" and "image: Canon RF". */
    public record Conflict(String field, List<String> claims) { }
    /** Whether external catalogues identified the product named by the current brand and model. */
    public enum Identity { NOT_CHECKED, VERIFIED, NOT_FOUND, UNAVAILABLE }
    /** The user's explicit import choices; nothing is accepted implicitly. */
    public record Decision(boolean unverifiedAccepted, boolean duplicateAccepted, Optional<Equipment> increaseQuantityOf) {
        public Decision { Objects.requireNonNull(increaseQuantityOf); }
        public static Decision none() { return new Decision(false, false, Optional.empty()); }
    }
    private record Entry(String value, SpecificationStatus origin) { }
    public static final String IMAGE_SOURCE = "image";

    private List<Equipment> candidates;
    private Optional<Equipment> selected;
    private List<String> conflicts;
    private boolean confirmed;
    private UUID itemId = UUID.randomUUID();
    private String imageKind = "";
    private List<String> visibleText = List.of();
    private List<Candidate> recognized = List.of();
    private int selectedIndex = -1;
    private boolean selectionMade;
    private boolean ambiguous;
    private String uncertainty = "";
    private EquipmentType type;
    private Map<String, Entry> base = new LinkedHashMap<>();
    private List<SpecificationEvidence> evidence = List.of();
    private Identity identity = Identity.NOT_CHECKED;
    private List<LocalizedMessage> notices = List.of();

    /** Stage 1 constructor: a draft over existing Equipment candidates (verified fields keep their sources). */
    public GearImportDraft(List<Equipment> candidates, Optional<Equipment> selected, List<String> conflicts, boolean confirmed) {
        this.candidates = List.copyOf(candidates);
        this.selected = Objects.requireNonNull(selected, "selected");
        this.conflicts = List.copyOf(conflicts);
        this.confirmed = confirmed;
        this.recognized = this.candidates.stream().map(item -> new Candidate(item.type(), item.brand(), item.model(), "", List.of(), "",
            item.specifications())).toList();
        selected.ifPresent(item -> { selectedIndex = this.candidates.indexOf(item); load(asCandidate(item)); selectionMade = true;
            evidence = item.verifiedFields().isEmpty() ? List.of() : List.of(new SpecificationEvidence("record", item.sources().values().stream()
                .findFirst().orElse(URI.create("about:blank")), item.type(), item.brand(), item.model(), verifiedValues(item), item.verifiedAt().orElse(Instant.EPOCH)));
            identity = evidence.isEmpty() ? Identity.NOT_CHECKED : Identity.VERIFIED; });
    }

    /** F04 recognition result; the first candidate is preselected, and choosing explicitly is required when ambiguous. */
    public static GearImportDraft recognized(String imageKind, List<String> visibleText, List<Candidate> candidates,
                                             boolean ambiguous, String uncertainty) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("A recognition draft needs a candidate");
        var draft = new GearImportDraft(List.of(), Optional.empty(), List.of(), false);
        draft.imageKind = Objects.requireNonNullElse(imageKind, "");
        draft.visibleText = List.copyOf(visibleText);
        draft.recognized = List.copyOf(candidates);
        draft.ambiguous = ambiguous || candidates.size() > 1;
        draft.uncertainty = Objects.requireNonNullElse(uncertainty, "").strip();
        draft.selectedIndex = 0;
        draft.selectionMade = !draft.ambiguous;
        draft.load(candidates.getFirst());
        return draft;
    }

    /** Switches to another recognized candidate: its image readings replace the fields and evidence is cleared. */
    public synchronized GearImportDraft select(int index) {
        if (index < 0 || index >= recognized.size()) throw new IllegalArgumentException("No candidate " + (index + 1));
        var copy = copy();
        copy.selectedIndex = index; copy.selectionMade = true;
        copy.load(recognized.get(index));
        copy.evidence = List.of(); copy.identity = Identity.NOT_CHECKED; copy.notices = List.of();
        return copy;
    }

    /**
     * Applies reviewed values ("type" plus F05 field keys). Unchanged values keep their status; changed values become
     * user-provided; a new brand, model or category discards evidence that described the previous identity.
     */
    public synchronized Result<GearImportDraft> withEdits(Map<String, String> edits) {
        var copy = copy();
        String typeEdit = edits.getOrDefault("type", "").strip();
        if (!typeEdit.isEmpty()) {
            EquipmentType chosen;
            try { chosen = EquipmentType.valueOf(typeEdit.replace("-", "").replace("_", "").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { return Result.failureKey("error.cli.type", typeEdit); }
            if (chosen != type) {
                copy.detach();
                copy.type = chosen;
                copy.base.keySet().removeIf(key -> !keys(chosen).contains(key));
            }
        }
        for (var edit : edits.entrySet()) {
            String key = edit.getKey();
            if (!key.equals("type") && !keys(copy.type).contains(key)) return Result.failureKey("error.gear.unknownField", key);
            if (Objects.requireNonNullElse(edit.getValue(), "").strip().length() > 120) return Result.failureKey("import.error.valueLength", key);
        }
        // Identity first: values shown for the previous product are kept but lose that product's verification.
        for (String key : List.of("brand", "model")) {
            if (!edits.containsKey(key)) continue;
            String value = Objects.requireNonNullElse(edits.get(key), "").strip();
            if (value.equals(copy.value(key))) continue;
            if (copy.identity != Identity.NOT_CHECKED || !copy.evidence.isEmpty()) copy.detach();
            copy.base.put(key, new Entry(value, SpecificationStatus.USER_PROVIDED));
        }
        var current = copy.fields();
        for (var edit : edits.entrySet()) {
            String key = edit.getKey();
            if (Set.of("type", "brand", "model").contains(key)) continue;
            String value = Objects.requireNonNullElse(edit.getValue(), "").strip();
            if (value.equals(current.get(key).value())) continue;
            copy.base.put(key, new Entry(value, SpecificationStatus.USER_PROVIDED));
        }
        return Result.success(copy);
    }

    /** Stage 1 correction: the reviewed Equipment's values replace the draft values as user-provided entries. */
    public synchronized void correct(Equipment item) {
        Objects.requireNonNull(item, "item");
        var values = new LinkedHashMap<>(item.specifications());
        values.put("type", item.type().name()); values.put("brand", item.brand()); values.put("model", item.model());
        values.put("quantity", String.valueOf(item.quantity()));
        values.put("weightGrams", item.weightGrams().map(String::valueOf).orElse(""));
        keys(item.type()).forEach(key -> values.putIfAbsent(key, ""));
        var corrected = withEdits(values);
        if (!corrected.isSuccess()) throw new IllegalArgumentException(String.join("; ", corrected.errors()));
        adopt(corrected.value().orElseThrow());
    }

    /** Result of external verification for the current identity; replaces earlier evidence. */
    public synchronized GearImportDraft withEvidence(List<SpecificationEvidence> records, Identity checked, List<LocalizedMessage> messages) {
        var copy = copy();
        copy.evidence = List.copyOf(records); copy.identity = Objects.requireNonNull(checked); copy.notices = List.copyOf(messages);
        return copy;
    }

    /** Effective values in display order: brand, model, quantity, weight, then the category's specification fields. */
    public synchronized Map<String, Field> fields() {
        var result = new LinkedHashMap<String, Field>();
        for (String key : keys(type)) result.put(key, field(key));
        return Collections.unmodifiableMap(result);
    }

    public synchronized List<Conflict> fieldConflicts() {
        var result = new ArrayList<Conflict>();
        for (String key : keys(type)) {
            if (Set.of("brand", "model", "quantity").contains(key)) continue;
            var entry = base.get(key);
            if (entry != null && entry.origin() == SpecificationStatus.USER_PROVIDED) continue;
            var claims = claims(key);
            boolean disagree = !agree(key, claims) || (entry != null && !entry.value().isBlank() && !claims.isEmpty()
                && !GearNames.sameValue(key, brand(), entry.value(), claims.getFirst().value()));
            if (!disagree) continue;
            var lines = new ArrayList<String>();
            if (entry != null && !entry.value().isBlank()) lines.add(IMAGE_SOURCE + ": " + entry.value());
            claims.forEach(claim -> lines.add(claim.source() + ": " + claim.value()));
            result.add(new Conflict(key, lines));
        }
        return result;
    }

    /**
     * Required or compatibility-critical values that would be saved without external verification (and the model
     * itself when the catalogues did not identify it); the user must explicitly accept them.
     */
    public synchronized List<String> unverifiedCritical() {
        var result = new ArrayList<String>();
        if (identity != Identity.VERIFIED) result.add("model");
        if (type == null) return result;
        var current = fields();
        for (var spec : type.fields()) {
            if (!spec.required()) continue;
            var field = current.get(spec.key());
            if (!field.value().isBlank() && field.status() != SpecificationStatus.EXTERNALLY_VERIFIED) result.add(spec.key());
        }
        return result;
    }

    /** Deterministic problems that block import (live review feedback). */
    public synchronized List<LocalizedMessage> problems() {
        var result = new ArrayList<LocalizedMessage>();
        if (confirmed) result.add(LocalizedMessage.of("import.error.alreadyImported"));
        if (type == null) { result.add(LocalizedMessage.of("import.error.type")); return result; }
        if (!selectionMade) result.add(LocalizedMessage.of("import.error.selectCandidate"));
        var built = build();
        if (!built.isSuccess()) result.addAll(built.messages());
        return result;
    }

    /** Stage 1 confirm: the validated Equipment this draft would import. Saving is InventoryService's job. */
    public synchronized Result<Equipment> confirm() {
        if (confirmed) return Result.failureKey("import.error.alreadyImported");
        if (type == null) return Result.failureKey("import.error.type");
        if (!selectionMade) return Result.failureKey("import.error.selectCandidate");
        var built = build();
        built.value().ifPresent(item -> selected = Optional.of(item));
        return built;
    }

    /** Marks this draft as imported so the same review can never be saved twice. */
    public synchronized void markConfirmed() { confirmed = true; }

    private Result<Equipment> build() {
        var current = fields();
        int quantity;
        try { quantity = Integer.parseInt(current.get("quantity").value()); }
        catch (NumberFormatException invalid) { return Result.failureKey("error.gear.quantity"); }
        Double weight = null;
        if (!current.get("weightGrams").value().isBlank()) {
            try { weight = Double.valueOf(current.get("weightGrams").value()); }
            catch (NumberFormatException invalid) { return Result.failureKey("error.gear.number"); }
        }
        var specs = new LinkedHashMap<String, String>();
        var verified = new TreeSet<String>();
        var sources = new LinkedHashMap<String, String>();
        Instant latest = null;
        for (var entry : current.entrySet()) {
            var field = entry.getValue();
            if (field.status() == SpecificationStatus.EXTERNALLY_VERIFIED) {
                verified.add(entry.getKey());
                field.sourceUrl().ifPresent(url -> sources.put(entry.getKey(), url.toString()));
            }
            if (!Set.of("brand", "model", "quantity", "weightGrams").contains(entry.getKey()) && !field.value().isBlank())
                specs.put(entry.getKey(), field.value());
        }
        for (var record : evidence) latest = latest == null || record.retrievedAt().isAfter(latest) ? record.retrievedAt() : latest;
        return new EquipmentData(type.name(), itemId.toString(), current.get("brand").value(), current.get("model").value(), quantity,
            weight, verified, sources, verified.isEmpty() || latest == null ? null : latest.toString(), specs).toEquipment();
    }

    private Field field(String key) {
        var entry = base.get(key);
        if (key.equals("quantity")) return new Field(entry == null ? "1" : entry.value(), SpecificationStatus.USER_PROVIDED, "", Optional.empty());
        if (key.equals("brand") || key.equals("model")) {
            String value = entry == null ? "" : entry.value();
            if (identity == Identity.VERIFIED && !value.isBlank() && !evidence.isEmpty())
                return new Field(value, SpecificationStatus.EXTERNALLY_VERIFIED, evidence.getFirst().source(), Optional.of(evidence.getFirst().url()));
            return new Field(value, value.isBlank() ? SpecificationStatus.UNKNOWN_OR_CONFLICTING : entry.origin(), "", Optional.empty());
        }
        var claims = claims(key);
        boolean agreeing = agree(key, claims);
        if (entry != null && entry.origin() == SpecificationStatus.USER_PROVIDED) {
            if (entry.value().isBlank()) return unknown();
            if (!claims.isEmpty() && agreeing && GearNames.sameValue(key, brand(), entry.value(), claims.getFirst().value()))
                return verified(entry.value(), claims.getFirst());
            return new Field(entry.value(), SpecificationStatus.USER_PROVIDED, "", Optional.empty());
        }
        boolean read = entry != null && !entry.value().isBlank();
        if (claims.isEmpty()) return read ? new Field(entry.value(), SpecificationStatus.AI_PROPOSED, IMAGE_SOURCE, Optional.empty()) : unknown();
        if (!agreeing || (read && !GearNames.sameValue(key, brand(), entry.value(), claims.getFirst().value()))) return unknown();
        return verified(claims.getFirst().value(), claims.getFirst());
    }
    private record Claim(String source, String value, URI url) { }
    /** Evidence statements for a field; for mounts the catalogue label (Lensfun) is listed first so labels stay consistent. */
    private List<Claim> claims(String key) {
        var result = new ArrayList<Claim>();
        for (var record : evidence) {
            String value = record.values().getOrDefault(key, "").strip();
            if (!value.isEmpty()) result.add(new Claim(record.source(), value, record.url()));
        }
        if (key.equals("mount")) result.sort(Comparator.comparing(claim -> claim.source().equals("Lensfun") ? 0 : 1));
        return result;
    }
    private boolean agree(String key, List<Claim> claims) {
        return claims.stream().allMatch(claim -> GearNames.sameValue(key, brand(), claim.value(), claims.getFirst().value()));
    }
    private static Field verified(String value, Claim claim) {
        return new Field(value, SpecificationStatus.EXTERNALLY_VERIFIED, claim.source(), Optional.of(claim.url()));
    }
    private static Field unknown() { return new Field("", SpecificationStatus.UNKNOWN_OR_CONFLICTING, "", Optional.empty()); }
    private String brand() { var entry = base.get("brand"); return entry == null ? "" : entry.value(); }

    private void load(Candidate candidate) {
        type = candidate.type();
        String quantity = base.containsKey("quantity") ? base.get("quantity").value() : "1";
        base = new LinkedHashMap<>();
        base.put("brand", new Entry(candidate.brand(), SpecificationStatus.AI_PROPOSED));
        base.put("model", new Entry(candidate.model(), SpecificationStatus.AI_PROPOSED));
        base.put("quantity", new Entry(quantity, SpecificationStatus.USER_PROVIDED));
        candidate.visibleValues().forEach((key, value) -> {
            if (keys(type).contains(key) && !value.isBlank()) base.put(key, new Entry(value.strip(), SpecificationStatus.AI_PROPOSED));
        });
    }
    /** Evidence no longer applies: verified values stay as values the user kept, then evidence is discarded. */
    private void detach() {
        boolean hadEvidence = !evidence.isEmpty();
        for (var entry : fields().entrySet()) {
            String key = entry.getKey(); var field = entry.getValue();
            if (Set.of("brand", "model", "quantity").contains(key) || field.value().isBlank()) continue;
            if (field.status() == SpecificationStatus.EXTERNALLY_VERIFIED) base.put(key, new Entry(field.value(), SpecificationStatus.USER_PROVIDED));
        }
        evidence = List.of(); identity = Identity.NOT_CHECKED;
        notices = hadEvidence ? List.of(LocalizedMessage.of("import.notice.identityChanged")) : List.of();
    }
    private static Candidate asCandidate(Equipment item) {
        return new Candidate(item.type(), item.brand(), item.model(), "", List.of(), "", item.specifications());
    }
    private static Map<String, String> verifiedValues(Equipment item) {
        var values = new LinkedHashMap<String, String>();
        item.specifications().forEach((key, value) -> { if (item.verifiedFields().contains(key)) values.put(key, value); });
        return values;
    }
    /** brand, model, quantity, weightGrams and the category's specification keys. */
    public static List<String> keys(EquipmentType type) {
        var keys = new ArrayList<>(List.of("brand", "model", "quantity", "weightGrams"));
        if (type != null) type.fields().forEach(field -> keys.add(field.key()));
        return keys;
    }

    private GearImportDraft copy() {
        var copy = new GearImportDraft(List.of(), Optional.empty(), List.of(), false);
        copy.adopt(this);
        return copy;
    }
    private void adopt(GearImportDraft other) {
        candidates = other.candidates; selected = other.selected; conflicts = other.conflicts; confirmed = other.confirmed;
        itemId = other.itemId; imageKind = other.imageKind; visibleText = other.visibleText; recognized = other.recognized;
        selectedIndex = other.selectedIndex; selectionMade = other.selectionMade; ambiguous = other.ambiguous;
        uncertainty = other.uncertainty; type = other.type; base = new LinkedHashMap<>(other.base); evidence = other.evidence;
        identity = other.identity; notices = other.notices;
    }

    public synchronized List<Equipment> candidates() { return candidates; }
    public synchronized Optional<Equipment> selected() { return selected; }
    /** Stage 1 conflict list: supplied conflicts plus the current field disagreements. */
    public synchronized List<String> conflicts() {
        var all = new ArrayList<>(conflicts);
        fieldConflicts().forEach(conflict -> all.add(conflict.field() + ": " + String.join("; ", conflict.claims())));
        return all;
    }
    public synchronized boolean confirmed() { return confirmed; }
    public synchronized UUID itemId() { return itemId; }
    public synchronized String imageKind() { return imageKind; }
    public synchronized List<String> visibleText() { return visibleText; }
    public synchronized List<Candidate> recognizedCandidates() { return recognized; }
    public synchronized int selectedIndex() { return selectedIndex; }
    public synchronized boolean selectionMade() { return selectionMade; }
    public synchronized boolean ambiguous() { return ambiguous; }
    public synchronized String uncertainty() { return uncertainty; }
    public synchronized EquipmentType type() { return type; }
    public synchronized List<SpecificationEvidence> evidence() { return evidence; }
    public synchronized Identity identity() { return identity; }
    public synchronized List<LocalizedMessage> notices() { return notices; }
    public synchronized String value(String key) { var field = fields().get(key); return field == null ? "" : field.value(); }
    /** Visible model code of the selected candidate, when the image showed one and the model was not edited since. */
    public synchronized String modelCode() {
        if (selectedIndex < 0 || selectedIndex >= recognized.size()) return "";
        var candidate = recognized.get(selectedIndex);
        return candidate.model().equals(value("model")) && candidate.brand().equals(value("brand")) ? candidate.modelCode() : "";
    }
}
