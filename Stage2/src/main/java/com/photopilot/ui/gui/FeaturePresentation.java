package com.photopilot.ui.gui;

import com.photopilot.config.FeatureCatalog;
import java.util.List;
import com.photopilot.i18n.*;

/**
 * GUI-only names and grouping. The shared catalog remains the source of feature status. The group is the sidebar
 * section a page belongs to (General, Active Session, Equipment Management, Photography Tools), also shown above the
 * page title; the order of {@link #all()} is the catalog order, not the sidebar's display order.
 */
record FeaturePresentation(String id, String title, String description, String group) {
    static List<FeaturePresentation> all() {
        return List.of(
            new FeaturePresentation("F01", EnglishMessages.text("feature.F01.title"), EnglishMessages.text("feature.F01.description"), EnglishMessages.text("nav.group.general")),
            new FeaturePresentation("F02", EnglishMessages.text("feature.F02.title"), EnglishMessages.text("feature.F02.description"), EnglishMessages.text("nav.group.general")),
            new FeaturePresentation("F03", EnglishMessages.text("feature.F03.title"), EnglishMessages.text("feature.F03.description"), EnglishMessages.text("nav.group.session")),
            new FeaturePresentation("F10", EnglishMessages.text("feature.F10.title"), EnglishMessages.text("feature.F10.description"), EnglishMessages.text("nav.group.session")),
            new FeaturePresentation("F04", EnglishMessages.text("feature.F04.title"), EnglishMessages.text("feature.F04.description"), EnglishMessages.text("nav.group.equipment")),
            new FeaturePresentation("F05", EnglishMessages.text("feature.F05.title"), EnglishMessages.text("feature.F05.description"), EnglishMessages.text("nav.group.equipment")),
            new FeaturePresentation("F06", EnglishMessages.text("feature.F06.title"), EnglishMessages.text("feature.F06.description"), EnglishMessages.text("nav.group.session")),
            new FeaturePresentation("F07", EnglishMessages.text("feature.F07.title"), EnglishMessages.text("feature.F07.description"), EnglishMessages.text("nav.group.session")),
            new FeaturePresentation("F08", EnglishMessages.text("feature.F08.title"), EnglishMessages.text("feature.F08.description"), EnglishMessages.text("nav.group.tools")),
            new FeaturePresentation("F09", EnglishMessages.text("feature.F09.title"), EnglishMessages.text("feature.F09.description"), EnglishMessages.text("nav.group.session")),
            new FeaturePresentation("F11", EnglishMessages.text("feature.F11.title"), EnglishMessages.text("feature.F11.description"), EnglishMessages.text("nav.group.session"))
        );
    }

    String title(LocalizationService l) { return l.text("feature." + id + ".title"); }
    String description(LocalizationService l) { return l.text("feature." + id + ".description"); }
    String group(LocalizationService l) { return l.text(groupKey()); }
    /** Sidebar section key of this page. */
    String groupKey() {
        return "nav.group." + switch (id) {
            case "F01", "F02" -> "general";
            case "F03", "F06", "F07", "F09", "F10", "F11" -> "session";
            case "F04", "F05" -> "equipment";
            default -> "tools";
        };
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
        SCAFFOLDED, IN_PROGRESS, IMPLEMENTED, UNAVAILABLE;
        String label() { return EnglishMessages.text("feature.status." + name()); }
        String label(LocalizationService l) { return l.text("feature.status." + name()); }
    }
}
