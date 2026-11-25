package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.application.DiscoveryPresentation.GearCount;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearInventory;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * The read-only equipment context of Guided Discovery: one tile per equipment type with the number of units (summed
 * quantities) and, in words, the number of inventory records, then a closed "View equipment" disclosure that lists every
 * record by category. Everything comes from the current profile's inventory through {@link DiscoveryPresentation}; an
 * empty or unreadable inventory says so instead of showing tiles.
 */
final class DiscoveryGearSummary extends VBox {
    private final FxLocalizer texts;
    private final LocalizationService localization;
    private final CardGrid tiles = new CardGrid(150, 12, 5);
    private final Label empty = DiscoveryIdeaCard.text("", "discover-gear-empty");
    private final Label listing = DiscoveryIdeaCard.text("", "discover-gear-listing");
    private final VBox groups = new VBox(14);
    private final TitledPane details = new TitledPane();
    private final Button refresh;
    private GearInventory inventory;
    private List<LocalizedMessage> problem;

    DiscoveryGearSummary(FxLocalizer texts, Runnable reload) {
        super(14); this.texts = texts; localization = texts.service();
        setId("discover-gear"); getStyleClass().addAll("discover-subcard", "discover-gear");
        refresh = DiscoveryIdeaCard.button(texts, "discover.guided.refresh", "discover-gear-refresh", AppIcons.REFRESH, false);
        texts.bind(refresh.accessibleTextProperty(), () -> localization.text("discover.guided.refreshGear"));
        refresh.setOnAction(event -> reload.run());
        var title = texts.label("discover.guided.gearTitle", "discover-section-title");
        var note = texts.label("discover.guided.gearReadOnly", "discover-caption");
        var heading = new VBox(3, title, note); HBox.setHgrow(heading, Priority.ALWAYS); heading.setMinWidth(0);
        var header = new HBox(12, AppIcons.slot(AppIcons.CAMERA_OUTLINE, "discover-heading-icon", 22), heading, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        empty.setId("discover-gear-empty");
        listing.setId("discover-gear-list");
        // The complete shared listing (count sentence, every record, read-only note) kept as data for parity with the CLI
        // and the tests; it is not displayed (hidden nodes are not exposed to assistive technology either). What users and
        // screen readers get is the visible grouped view above it, which lists the same records, and the tiles' texts.
        listing.setVisible(false); listing.setManaged(false);
        details.setId("discover-gear-details"); details.setExpanded(false); details.setAnimated(false);
        details.getStyleClass().add("discover-disclosure");
        details.setContent(new VBox(12, groups, listing));
        getChildren().addAll(header, tiles, empty, details);
        show(null, null);
    }

    Button refreshButton() { return refresh; }
    /** {@code inventory} null and {@code problem} null: not read yet. */
    void show(GearInventory inventory, List<LocalizedMessage> problem) { this.inventory = inventory; this.problem = problem; render(); }

    void render() {
        tiles.getChildren().clear(); groups.getChildren().clear();
        boolean readable = inventory != null, any = readable && !inventory.items().isEmpty();
        if (problem != null) empty.setText(localization.text("discover.gear.unreadable", DiscoveryPresentation.render(problem, localization)));
        else if (readable && !any) empty.setText(localization.text("discover.guided.gearEmpty"));
        else empty.setText("");
        show(empty, !empty.getText().isEmpty());
        show(tiles, any); show(details, any);
        listing.setText(readable ? String.join("\n", DiscoveryPresentation.gear(inventory, localization)) : empty.getText());
        if (!any) return;
        for (GearCount count : DiscoveryPresentation.gearCounts(inventory)) tiles.getChildren().add(tile(count));
        details.setText(localization.text("discover.guided.gearDetails", inventory.items().size()));
        var grid = new CardGrid(240, 18, 3);
        DiscoveryPresentation.gearGroups(inventory, localization).forEach((type, lines) -> {
            var group = new VBox(4); group.getStyleClass().add("discover-gear-group"); group.setId("discover-gear-group-" + type.name());
            group.getChildren().add(DiscoveryIdeaCard.text(DiscoveryPresentation.gearCategory(type, localization), "discover-gear-group-title"));
            for (String line : lines) group.getChildren().add(DiscoveryIdeaCard.text(line, "discover-gear-item"));
            grid.getChildren().add(group);
        });
        groups.getChildren().add(grid);
    }

    private VBox tile(GearCount count) {
        var category = DiscoveryIdeaCard.text(DiscoveryPresentation.gearCategory(count.type(), localization), "discover-gear-category");
        var value = DiscoveryIdeaCard.text(String.valueOf(count.units()), "discover-gear-value");
        var unit = DiscoveryIdeaCard.text(localization.text("discover.guided.unitsWord"), "discover-gear-unit");
        var amount = new HBox(6, value, unit); amount.setAlignment(Pos.BASELINE_LEFT);
        var records = DiscoveryIdeaCard.text(DiscoveryPresentation.gearRecords(count, localization), "discover-gear-records");
        var top = new HBox(8, AppIcons.slot(icon(count.type()), "discover-gear-icon", 18), category); top.setAlignment(Pos.CENTER_LEFT);
        var tile = new VBox(6, top, amount, records); tile.getStyleClass().add("discover-gear-tile"); tile.setId("discover-gear-" + count.type().name());
        tile.setAccessibleText(localization.text("discover.guided.gearTileAccessible", DiscoveryPresentation.gearCategory(count.type(), localization),
            DiscoveryPresentation.gearUnits(count, localization), DiscoveryPresentation.gearRecords(count, localization)));
        return tile;
    }
    private static String icon(EquipmentType type) {
        return switch (type) {
            case CAMERA -> AppIcons.CAMERA_OUTLINE; case LENS -> AppIcons.LENS; case BATTERY -> AppIcons.BATTERY;
            case STORAGEMEDIA -> AppIcons.MEMORY_CARD; case FILTER -> AppIcons.FILTER;
        };
    }
    private static void show(javafx.scene.Node node, boolean visible) { node.setVisible(visible); node.setManaged(visible); }
}
