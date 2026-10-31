package com.photopilot.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.Loadout;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 73 (zero-based): {F10; Command receiver; immutable after approval}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ShootPlan {
    private UUID id;
    private int revision;
    private boolean approved;
    private List<String> provisionalReasons;
    private Optional<Loadout> loadout;
    private Map<String, String> settings;
    private List<ShootingWindow> windows;
    private List<String> checklist;
    private List<String> preparation;
    private List<String> shotPriorities;
    private Map<String, String> fixedConstraints;
    private List<CalculationResult> calculations;

    public ShootPlan(UUID id, int revision, boolean approved, List<String> provisionalReasons, Optional<Loadout> loadout, Map<String, String> settings, List<ShootingWindow> windows, List<String> checklist, List<String> preparation, List<String> shotPriorities, Map<String, String> fixedConstraints, List<CalculationResult> calculations) {
        this.id = Objects.requireNonNull(id, "id");
        this.revision = revision;
        this.approved = approved;
        this.provisionalReasons = List.copyOf(provisionalReasons);
        this.loadout = Objects.requireNonNull(loadout, "loadout");
        this.settings = Map.copyOf(settings);
        this.windows = List.copyOf(windows);
        this.checklist = List.copyOf(checklist);
        this.preparation = List.copyOf(preparation);
        this.shotPriorities = List.copyOf(shotPriorities);
        this.fixedConstraints = Map.copyOf(fixedConstraints);
        this.calculations = List.copyOf(calculations);
        if (revision < 1) throw new IllegalArgumentException("Plan revision must be positive.");
        if (approved && !this.provisionalReasons.isEmpty())
            throw new IllegalArgumentException("An approved plan cannot be provisional.");
    }

    public ShootPlan copyForRevision() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ShootPlan.copyForRevision");
    }

    /** Read-only history access. Approval/validation remains the responsibility of F10. */
    public UUID id() { return id; }
    public int revision() { return revision; }
    public boolean approved() { return approved && provisionalReasons.isEmpty(); }
    public Optional<Loadout> loadout() { return loadout; }
    public List<CalculationResult> calculations() { return List.copyOf(calculations); }

    void replaceLoadout(Loadout loadout) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ShootPlan.replaceLoadout");
    }

    void reviseSchedule(List<ShootingWindow> windows) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ShootPlan.reviseSchedule");
    }

    void reviseStrategy(ReviseStrategyCommand change) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ShootPlan.reviseStrategy");
    }
}
