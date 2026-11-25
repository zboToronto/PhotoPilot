package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearCapabilityAnalysis;
import com.photopilot.repository.StoreChanges;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/**
 * F06 page in the existing feature surface: the UC06 shoot capability-requirement workflow
 * ({@link CapabilityProfilePane}) followed by the read-only inventory capability report ({@link CapabilityDashboard}),
 * which the Gear Capability Report entry shows on its own. The report is analyzed on the worker lane whenever the page
 * is shown or refreshed; an inventory save of this process marks the shown snapshot outdated. Nothing here writes.
 */
final class CapabilityPane extends VBox {
    /** Gear Overview omits battery and storage-media records and the camera fields that describe them. */
    private static final Set<EquipmentType> SHOWN_TYPES = EnumSet.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.FILTER);
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final CapabilityProfilePane profile;
    private final CapabilityDashboard dashboard;
    private final AutoCloseable changes;
    private boolean active, reportOnly;
    private long generation;
    /** Inventory saves announced in this process; an analysis started before the latest one is outdated. */
    private volatile long inventoryVersion;
    private final Separator divider = new Separator();
    private final Label reportTitle, notice;
    CapabilityPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        profile = new CapabilityProfilePane(app, operations, localization);
        dashboard = new CapabilityDashboard(localization, SHOWN_TYPES);
        dashboard.onRefresh(this::analyze);
        notice = texts.label("capability.scope", "secondary-text");
        reportTitle = texts.label("capability.inventoryTitle", "empty-title");
        getChildren().addAll(profile, divider, reportTitle, notice, dashboard);
        texts.onRefresh(dashboard::relabel);
        changes = app.changes().listen(change -> {
            if (change.kind() != StoreChanges.Kind.INVENTORY_SAVED) return;
            inventoryVersion++;
            Platform.runLater(() -> { if (active) dashboard.stale(); });
        });
    }
    void activate(boolean value) {
        active = value; generation++;
        profile.activate(value && !reportOnly);
        if (active) analyze();
    }
    /**
     * The Gear Capability Report entry shows the inventory report on its own (the page heading names it); the F06 page
     * keeps the session's capability requirements above the same report. Call before {@link #activate}.
     */
    void reportOnly(boolean value) {
        reportOnly = value;
        for (var node : new javafx.scene.Node[] { profile, divider, reportTitle, notice }) { node.setVisible(!value); node.setManaged(!value); }
    }
    /** "Edit equipment details" opens this item in the existing Gear Inventory editor. */
    void onEditEquipment(Consumer<UUID> action) { dashboard.onEdit(action); }
    void dispose() {
        active = false; generation++; profile.dispose(); texts.close();
        try { changes.close(); } catch (Exception ignored) { /* the subscription only removes a listener */ }
    }
    boolean dirty() { return profile.dirty(); }
    boolean busy() { return profile.busy(); }
    CapabilityProfilePane profile() { return profile; }
    CapabilityDashboard dashboard() { return dashboard; }
    private void analyze() {
        long request = ++generation, version = inventoryVersion;
        dashboard.loading();
        Task<Result<GearCapabilityAnalysis>> task = new Task<>() {
            protected Result<GearCapabilityAnalysis> call() { return app.capabilities().analyze(); }
        };
        task.setOnSucceeded(event -> {
            if (!active || request != generation) return;
            var result = task.getValue();
            if (result.isSuccess()) {
                dashboard.show(result.value().orElseThrow());
                if (version != inventoryVersion) dashboard.stale();
            } else dashboard.failed(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(event -> {
            if (active && request == generation) dashboard.failed(() -> localization.text("capability.failed"));
        });
        operations.execute(task);
    }
    String feedbackText() { return dashboard.statusText(); }
}
