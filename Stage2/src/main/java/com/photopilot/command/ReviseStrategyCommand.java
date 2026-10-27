package com.photopilot.command;

import java.util.List;
import java.util.Map;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 72 (zero-based): {Concrete Command; settings and shot goals}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ReviseStrategyCommand implements PlanCommand {
    private Map<String, String> settings;
    private List<String> shotPriorities;
    private List<String> preparation;
    private List<String> checklist;

    public ReviseStrategyCommand(Map<String, String> settings, List<String> shotPriorities, List<String> preparation, List<String> checklist) {
        this.settings = Map.copyOf(settings);
        this.shotPriorities = List.copyOf(shotPriorities);
        this.preparation = List.copyOf(preparation);
        this.checklist = List.copyOf(checklist);
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        return Result.failureKey("error.notImplemented", "ReviseStrategyCommand.applyTo");
    }
}
