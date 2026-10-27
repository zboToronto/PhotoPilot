package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;
import com.photopilot.model.GearImportDraft;
import com.photopilot.model.GearInventory;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.SessionRepository;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 7 (zero-based): {F04-F05; confirmation and validation}
 * F05 deterministic inventory operations; F04 recognition remains scaffolded.
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

    public List<Equipment> duplicates(Equipment item) {
        var loaded = repository.load();
        if (!loaded.isSuccess()) throw new IllegalStateException(String.join("; ", loaded.errors()));
        return duplicates(loaded.value().orElseThrow(), item);
    }

    public Result<List<UUID>> affectedSessions(UUID id) {
        return id == null ? Result.failure("Equipment ID is required.") : sessions.referencingGear(id);
    }

    public Result<Boolean> remove(UUID id, boolean warningAccepted) {
        return remove(id, warningAccepted, null);
    }

    public Result<Boolean> remove(UUID id, boolean warningAccepted, Equipment expected) {
        return repository.transact(inventory -> {
            if (expected != null && !same(inventory.find(id).orElse(null), expected)) return Result.failure("Equipment changed in another window. Refresh and review before removal.");
            var affected = affectedSessions(id);
            if (!affected.isSuccess()) return Result.failure(String.join("; ", affected.errors()));
            if (!affected.value().orElseThrow().isEmpty() && !warningAccepted) {
                return Result.failure("Removal affects saved sessions " + affected.value().orElseThrow()
                    + ". Confirm removal; historical gear snapshots will remain unchanged.");
            }
            return inventory.remove(id);
        });
    }

    public Result<GearImportDraft> recognize(SanitizedImage image) {
        return Result.failure("NOT IMPLEMENTED: InventoryService.recognize");
    }

    public Result<Equipment> confirm(GearImportDraft draft) {
        return Result.failure("NOT IMPLEMENTED: InventoryService.confirm");
    }

    public Result<GearInventory> list(String query, com.photopilot.model.EquipmentType type) {
        var loaded = repository.load();
        if (!loaded.isSuccess()) return Result.failure(String.join("; ", loaded.errors()));
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
        if (item == null) return Result.failure("Equipment is required.");
        if (!item.validate().isSuccess()) return item.validate();
        return repository.transact(inventory -> {
            var existing = inventory.find(item.id());
            if (expected != null && !same(existing.orElse(null), expected)) return Result.failure("Equipment changed in another window. Refresh and reopen the editor before saving.");
            if (operation.equals("add") && existing.isPresent()) return Result.failure("Duplicate equipment ID: " + item.id());
            if (operation.equals("update") && existing.isEmpty()) return Result.failure("Equipment no longer exists. Refresh the inventory.");
            var duplicates = duplicates(inventory, item);
            if (!duplicates.isEmpty() && !duplicatesAccepted) return Result.failure("Possible duplicate gear: "
                + duplicates.stream().map(e -> e.id().toString()).toList() + ". Review and explicitly confirm the duplicate before saving.");
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
