package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.service.GearMatchingService;

/**
 * Stage 1 UML element 95 (zero-based): {AgentTool; returns verified candidates; no AI recursion}
 * Retained for Stage 1 traceability and deliberately not registered: F07's UC14-verified candidates already reach F10
 * as labelled offers (PlanAssembly), so a model-callable re-match would duplicate GearMatchingService without adding
 * evidence. It publishes no schema, so ToolRegistry refuses to register it.
 */
public class GearMatchingTool implements AgentTool {
    private GearMatchingService matching;
    private InventoryRepository inventory;

    public GearMatchingTool(GearMatchingService matching, InventoryRepository inventory) {
        this.matching = Objects.requireNonNull(matching, "matching");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public String name() {
        return "gearMatching";
    }

    public String inputSchema() {
        throw new UnsupportedOperationException("GearMatchingTool is not offered to the model; see the class documentation");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failureKey("error.notImplemented", "GearMatchingTool.execute");
    }
}
