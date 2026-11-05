package com.photopilot.service;

import com.photopilot.i18n.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearImportDraft;
import com.photopilot.model.GearInventory;
import com.photopilot.model.GearNames;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.SessionRepository;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 7 (zero-based): {F04-F05; confirmation and validation}
 * F05 deterministic inventory operations; F04 recognition, verification and explicit import reuse them.
 */
public class InventoryService {
    private InventoryRepository repository;
    private SessionRepository sessions;
    private GearRecognitionService recognition;

    public InventoryService(InventoryRepository repository, SessionRepository sessions, GearRecognitionService recognition) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.recognition = Objects.requireNonNull(recognition, "recognition");
    }

    public Result<GearInventory> list(String query) {
        return list(query, null);
    }

    public Result<Equipment> save(Equipment item) {
        return save(item, false);
    }

    /**
     * Stage 1 signature (a List, not a Result), kept for UML traceability: a storage failure surfaces as
     * IllegalStateException. GUI and CLI use the Result-returning save/confirm paths, which report it instead.
     */
    public List<Equipment> duplicates(Equipment item) {
        var loaded = repository.load();
        if (!loaded.isSuccess()) throw new IllegalStateException(String.join("; ", loaded.errors()));
        return duplicates(loaded.value().orElseThrow(), item);
    }

    public Result<List<UUID>> affectedSessions(UUID id) {
        return id == null ? Result.failureKey("error.gear.idRequired") : sessions.referencingGear(id);
    }

    public Result<Boolean> remove(UUID id, boolean warningAccepted) {
        return remove(id, warningAccepted, null);
    }

    public Result<Boolean> remove(UUID id, boolean warningAccepted, Equipment expected) {
        return repository.transact(inventory -> {
            if (expected != null && !same(inventory.find(id).orElse(null), expected)) return Result.failureKey("error.gear.changedRemoval");
            var affected = affectedSessions(id);
            if (!affected.isSuccess()) return Result.failure(affected);
            if (!affected.value().orElseThrow().isEmpty() && !warningAccepted) {
                return Result.failureKey("error.gear.removalReferences", affected.value().orElseThrow());
            }
            return inventory.remove(id);
        });
    }

    /** F04 (SD02): recognition of the approved image, then external verification of the first candidate. Never saves. */
    public Result<GearImportDraft> recognize(SanitizedImage image) {
        var recognized = recognition.recognize(image);
        if (!recognized.isSuccess()) return recognized;
        var verified = recognition.verify(recognized.value().orElseThrow());
        return verified.isSuccess() ? verified : recognized;
    }

    /** F04: re-runs specification verification after the user chose another candidate or corrected the identity. */
    public Result<GearImportDraft> verify(GearImportDraft draft) {
        return recognition.verify(draft);
    }

    /**
     * F04 duplicate review: F05 duplicates (same type, brand and model) plus similar records whose names differ only
     * in punctuation, spacing or order. Distinct variants (e.g. "II" versions) are not reported; nothing is merged.
     */
    public Result<List<Equipment>> importDuplicates(EquipmentType type, String brand, String model) {
        var loaded = repository.load();
        if (!loaded.isSuccess()) return Result.failure(loaded);
        return Result.success(loaded.value().orElseThrow().items().stream().filter(item -> item.type() == type
            && GearNames.sameBrand(item.brand(), brand) && (item.model().strip().equalsIgnoreCase(Objects.requireNonNullElse(model, "").strip())
                || GearNames.sameModel(brand, item.model(), model))).toList());
    }

    /** Strict F04 confirmation: no unverified critical values, no duplicates accepted implicitly. */
    public Result<Equipment> confirm(GearImportDraft draft) {
        return confirm(draft, GearImportDraft.Decision.none());
    }

    /**
     * F04 explicit import (SD02): deterministic validation of the reviewed draft, explicit acceptance of unverified
     * critical values and of duplicates, then the existing F05 add (or F05 update of the chosen duplicate's quantity,
     * which keeps that record's specifications and evidence). Failures leave the inventory unchanged.
     */
    public Result<Equipment> confirm(GearImportDraft draft, GearImportDraft.Decision decision) {
        if (draft == null || decision == null) return Result.failureKey("import.error.noDraft");
        if (draft.confirmed()) return Result.failureKey("import.error.alreadyImported");
        var built = recognition.confirm(draft);
        if (!built.isSuccess()) return built;
        Equipment item = built.value().orElseThrow();
        var unverified = draft.unverifiedCritical();
        if (!unverified.isEmpty() && !decision.unverifiedAccepted()) return Result.failureKey("import.error.unverified", String.join(", ", unverified));
        var similar = importDuplicates(item.type(), item.brand(), item.model());
        if (!similar.isSuccess()) return Result.failure(similar);
        Result<Equipment> saved;
        if (decision.increaseQuantityOf().isPresent()) {
            Equipment target = decision.increaseQuantityOf().orElseThrow();
            if (similar.value().orElseThrow().stream().noneMatch(existing -> existing.id().equals(target.id())))
                return Result.failureKey("import.error.notDuplicate");
            var data = com.photopilot.model.EquipmentData.from(target);
            var increased = new com.photopilot.model.EquipmentData(data.type(), data.id(), data.brand(), data.model(),
                target.quantity() + item.quantity(), data.weightGrams(), data.verifiedFields(), data.sources(), data.verifiedAt(), data.specs()).toEquipment();
            if (!increased.isSuccess()) return increased;
            saved = update(increased.value().orElseThrow(), true, target);
        } else {
            if (!similar.value().orElseThrow().isEmpty() && !decision.duplicateAccepted())
                return Result.failureKey("error.gear.duplicate", similar.value().orElseThrow().stream().map(e -> e.brand() + " " + e.model() + " (" + e.id() + ")").toList());
            saved = add(item, decision.duplicateAccepted());
        }
        if (saved.isSuccess()) draft.markConfirmed();
        return saved;
    }

    public Result<GearInventory> list(String query, com.photopilot.model.EquipmentType type) {
        var loaded = repository.load();
        if (!loaded.isSuccess()) return Result.failure(loaded);
        var inventory = loaded.value().orElseThrow();
        return Result.success(new GearInventory(inventory.search(query).stream()
            .filter(item -> type == null || item.type() == type).toList(), inventory.revision()));
    }
    public Result<Equipment> save(Equipment item, boolean duplicatesAccepted) { return change(item, duplicatesAccepted, "save"); }
    public Result<Equipment> add(Equipment item, boolean duplicatesAccepted) { return change(item, duplicatesAccepted, "add"); }
    public Result<Equipment> update(Equipment item, boolean duplicatesAccepted) { return change(item, duplicatesAccepted, "update"); }
    public Result<Equipment> update(Equipment item, boolean duplicatesAccepted, Equipment expected) {
        return change(item, duplicatesAccepted, "update", Objects.requireNonNull(expected));
    }

    private Result<Equipment> change(Equipment item, boolean duplicatesAccepted, String operation) {
        return change(item, duplicatesAccepted, operation, null);
    }
    private Result<Equipment> change(Equipment item, boolean duplicatesAccepted, String operation, Equipment expected) {
        if (item == null) return Result.failureKey("error.gear.required");
        if (!item.validate().isSuccess()) return item.validate();
        return repository.transact(inventory -> {
            var existing = inventory.find(item.id());
            if (expected != null && !same(existing.orElse(null), expected)) return Result.failureKey("error.gear.changedSave");
            if (operation.equals("add") && existing.isPresent()) return Result.failureKey("error.gear.duplicateId", item.id());
            if (operation.equals("update") && existing.isEmpty()) return Result.failureKey("error.gear.noLongerExists");
            var duplicates = duplicates(inventory, item);
            if (!duplicates.isEmpty() && !duplicatesAccepted) return Result.failureKey("error.gear.duplicate", duplicates.stream().map(e -> e.id().toString()).toList());
            return existing.isPresent() ? inventory.update(item) : inventory.add(item);
        });
    }
    private boolean same(Equipment current, Equipment expected) {
        return current != null && com.photopilot.model.EquipmentData.from(current).equals(com.photopilot.model.EquipmentData.from(expected));
    }
    private List<Equipment> duplicates(GearInventory inventory, Equipment item) {
        Objects.requireNonNull(item, "item");
        return inventory.items().stream().filter(other -> !other.id().equals(item.id()) && other.type() == item.type()
            && other.brand().strip().equalsIgnoreCase(item.brand().strip())
            && other.model().strip().equalsIgnoreCase(item.model().strip())).toList();
    }
}
