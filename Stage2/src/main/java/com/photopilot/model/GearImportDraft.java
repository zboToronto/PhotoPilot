package com.photopilot.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 39 (zero-based): {F04; specification provenance retained}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GearImportDraft {
    private List<Equipment> candidates;
    private Optional<Equipment> selected;
    private List<String> conflicts;
    private boolean confirmed;

    public GearImportDraft(List<Equipment> candidates, Optional<Equipment> selected, List<String> conflicts, boolean confirmed) {
        this.candidates = List.copyOf(candidates);
        this.selected = Objects.requireNonNull(selected, "selected");
        this.conflicts = List.copyOf(conflicts);
        this.confirmed = confirmed;
    }

    public void correct(Equipment item) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: GearImportDraft.correct");
    }

    public Result<Equipment> confirm() {
        return Result.failureKey("error.notImplemented", "GearImportDraft.confirm");
    }
}
