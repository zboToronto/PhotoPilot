package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.service.GearMatchingService;

/**
 * Stage 1 UML element 95 (zero-based): {AgentTool; returns verified candidates; no AI recursion}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
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
        throw new UnsupportedOperationException("NOT IMPLEMENTED: GearMatchingTool.inputSchema");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failureKey("error.notImplemented", "GearMatchingTool.execute");
    }
}
