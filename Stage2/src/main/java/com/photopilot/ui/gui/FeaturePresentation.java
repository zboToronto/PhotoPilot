package com.photopilot.ui.gui;

import com.photopilot.config.FeatureCatalog;
import java.util.List;

/** GUI-only names and grouping. The shared catalog remains the source of feature status. */
record FeaturePresentation(String id, String title, String description, String group) {
    static List<FeaturePresentation> all() {
        return List.of(
            new FeaturePresentation("F01", "Sessions & History", "Organize photography sessions and revisit your shooting history.", "Workspace"),
            new FeaturePresentation("F02", "Inspiration", "Explore photography ideas and activities for your next session.", "Workspace"),
            new FeaturePresentation("F03", "Requirement Parsing", "Turn a photography brief into structured shooting requirements.", "Workspace"),
            new FeaturePresentation("F10", "Shoot Planning", "Build a shoot plan and refine your equipment, schedule, and strategy.", "Workspace"),
            new FeaturePresentation("F04", "AI Gear Import", "Identify photography gear and prepare it for import into your inventory.", "Equipment"),
            new FeaturePresentation("F05", "Gear Inventory", "Keep your cameras, lenses, and accessories organized in one place.", "Equipment"),
            new FeaturePresentation("F06", "Capability Analysis", "Understand the equipment capabilities your photography session needs.", "Equipment"),
            new FeaturePresentation("F07", "Compatibility Check", "Match your owned gear to a session and check equipment compatibility.", "Equipment"),
            new FeaturePresentation("F08", "Technical Calculator", "Work through the technical calculations behind your camera settings.", "Photography Tools"),
            new FeaturePresentation("F09", "Environment Planning", "Consider location, timing, and environmental conditions for your shoot.", "Photography Tools"),
            new FeaturePresentation("F11", "Post-Shoot Analysis", "Review image metadata and reflect on a completed photography session.", "Photography Tools")
        );
    }

    Status status() { return statusFrom(FeatureCatalog.lines()); }

    // Fail closed if a feature or its status is missing/unrecognized; never infer completion.
    Status statusFrom(List<String> catalog) {
        return catalog.stream().filter(line -> line.startsWith(id + " "))
            .findFirst().map(line -> {
                int separator = line.lastIndexOf(" - ");
                if (separator < 0) return Status.UNAVAILABLE;
                return switch (line.substring(separator + 3)) {
                    case "SCAFFOLDED / NOT IMPLEMENTED", "SCAFFOLDED" -> Status.SCAFFOLDED;
                    case "IN PROGRESS" -> Status.IN_PROGRESS;
                    case "IMPLEMENTED" -> Status.IMPLEMENTED;
                    default -> Status.UNAVAILABLE;
                };
            }).orElse(Status.UNAVAILABLE);
    }

    enum Status {
        SCAFFOLDED("Scaffolded"), IN_PROGRESS("In Progress"), IMPLEMENTED("Implemented"), UNAVAILABLE("Unavailable");
        private final String label;
        Status(String label) { this.label = label; }
        String label() { return label; }
    }
}
