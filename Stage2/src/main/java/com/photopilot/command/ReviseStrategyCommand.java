package com.photopilot.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 72 (zero-based): {Concrete Command; settings and shot goals}
 * Replaces only the named strategy sections of a draft (settings, strategy, shot priorities, trade-offs,
 * preparation, checklist); every other section of the draft is left as it was.
 */
public class ReviseStrategyCommand implements PlanCommand {
    public static final Set<String> SECTIONS = Set.of("settings", "strategy", "shotPriorities", "tradeOffs", "preparation", "checklist");
    private Map<String, String> settings;
    private List<String> shotPriorities;
    private List<String> preparation;
    private List<String> checklist;
    private Map<String, String> strategy = Map.of();
    private List<String> tradeOffs = List.of();
    private Set<String> sections = Set.of("settings", "shotPriorities", "preparation", "checklist");

    /** Original Stage 1 form: replaces settings, shot priorities, preparation and checklist. */
    public ReviseStrategyCommand(Map<String, String> settings, List<String> shotPriorities, List<String> preparation, List<String> checklist) {
        this.settings = Map.copyOf(settings);
        this.shotPriorities = List.copyOf(shotPriorities);
        this.preparation = List.copyOf(preparation);
        this.checklist = List.copyOf(checklist);
    }

    /** Replaces exactly {@code sections}; values of sections not named are ignored. */
    public ReviseStrategyCommand(Map<String, String> settings, Map<String, String> strategy, List<String> shotPriorities, List<String> tradeOffs,
            List<String> preparation, List<String> checklist, Set<String> sections) {
        this(settings, shotPriorities, preparation, checklist);
        this.strategy = Map.copyOf(strategy);
        this.tradeOffs = List.copyOf(tradeOffs);
        this.sections = Set.copyOf(Objects.requireNonNull(sections, "sections"));
        if (this.sections.isEmpty() || !SECTIONS.containsAll(this.sections)) throw new IllegalArgumentException("Unsupported strategy sections: " + sections);
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        if (draft == null) return Result.failureKey("plan.error.noDraft");
        if (draft.approved()) return Result.failureKey("plan.error.immutable");
        draft.reviseStrategy(this);
        return Result.success(draft);
    }

    Map<String, String> settings() { return settings; }
    Map<String, String> strategy() { return strategy; }
    List<String> shotPriorities() { return shotPriorities; }
    List<String> tradeOffs() { return tradeOffs; }
    List<String> preparation() { return preparation; }
    List<String> checklist() { return checklist; }
    public Set<String> sections() { return sections; }
}
