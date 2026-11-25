package com.photopilot.ui.gui;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.capability.CapabilityDemoInventory;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityDemoInventory.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Gear Capability Report dashboard through real JavaFX controls in the workspace, with the demonstration-like inventory. */
class CapabilityGuiTest {
    @TempDir Path directory;
    static final Set<EquipmentType> SHOWN = EnumSet.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.FILTER);
    ExecutorService executor; Stage stage; Scene scene; PhotoPilotWorkspace shell; CapabilityPane pane; InventoryPane inventory;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void run(CalculatorGuiTest.ThrowingRunnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    Node node(String id) throws Exception { return fx(() -> shell.lookup("#" + id)); }
    void click(String id) throws Exception { run(() -> ((ButtonBase) shell.lookup("#" + id)).fire()); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) shell.lookup("#" + id)).getText()); }
    boolean shown(String id) throws Exception { return fx(() -> { var n = shell.lookup("#" + id); return n != null && n.isVisible() && n.isManaged() && n.getScene() != null; }); }
    void await() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(() -> ((Button) shell.lookup("#capability-refresh")).isDisabled())) { if (System.nanoTime() > deadline) fail("Capability analysis timed out"); Thread.sleep(20); }
        run(() -> { shell.applyCss(); shell.layout(); });
    }
    void tab(CapabilityDashboard.View view) throws Exception { click("capability-tab-" + view.name()); run(() -> { shell.applyCss(); shell.layout(); }); }
    void snapshot(String name) throws Exception { run(() -> CalculatorGuiTest.snapshot(shell, name)); }
    GearCapabilityAnalysis report() throws Exception { return fx(() -> pane.dashboard().report()); }
    static List<String> texts(Node root) {
        var found = new ArrayList<String>();
        if (root instanceof Labeled labeled && labeled.getText() != null) found.add(labeled.getText());
        if (root instanceof Parent parent) for (var child : parent.getChildrenUnmodifiable()) found.addAll(texts(child));
        return found;
    }
    /** Everything in the dashboard lies inside the window (no horizontal overflow). */
    void fitsHorizontally(String view) throws Exception {
        run(() -> {
            shell.applyCss(); shell.layout();
            var dashboard = shell.lookup("#capability-dashboard");
            var bounds = dashboard.localToScene(dashboard.getBoundsInLocal());
            assertTrue(bounds.getMaxX() <= scene.getWidth() + 1, view + " overflows: " + bounds.getMaxX() + " > " + scene.getWidth());
        });
    }

    void launch(ApplicationBootstrap app, double width, double height) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        executor = Executors.newSingleThreadExecutor();
        run(() -> {
            shell = new PhotoPilotWorkspace(app.localization());
            pane = new CapabilityPane(app.facade(), executor, app.localization());
            inventory = new InventoryPane(app.facade(), executor, app.localization());
            // The same wiring as GuiProfileController: the report opens Gear Inventory's own editor.
            pane.onEditEquipment(id -> { inventory.edit(id); shell.selectInventory(); });
            shell.connectCapabilities(pane); shell.connectInventory(inventory);
            stage = new Stage(); scene = new Scene(shell, width, height);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((ToggleButton) shell.lookup("#nav-capability-report")).fire();
        });
        await();
    }
    @AfterEach void closeWindow() throws Exception {
        if (stage != null) run(() -> { pane.dispose(); inventory.dispose(); stage.close(); });
        if (executor != null) executor.shutdownNow();
    }

    @Test void dashboardPresentsTheInventoryThroughEveryViewWithoutChangingIt() throws Exception {
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            var l = app.localization();
            launch(app, 1400, 900);
            // Empty state: no tabs, a way to Gear Inventory.
            assertTrue(shown("capability-empty")); assertFalse(shown("capability-tabs"));
            assertTrue(texts(node("capability-empty")).contains("No equipment yet"));
            for (var item : items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            assertTrue(text("capability-status").startsWith("Inventory changed after this analysis"), text("capability-status"));
            byte[] stored = Files.readAllBytes(app.storageScope().inventoryFile());
            click("capability-refresh"); await();
            assertTrue(text("capability-status").startsWith("Analyzed at"));

            // Overview: metrics from the analysis, findings in the shared wording, no identifiers.
            var report = report();
            assertEquals("3", text("capability-metric-camera")); assertEquals("7", text("capability-metric-lens")); assertEquals("0", text("capability-metric-filter"));
            assertEquals("16–400 mm", text("capability-metric-span")); assertEquals("f/1.4", text("capability-metric-aperture")); assertEquals("1", text("capability-metric-gaps"));
            for (int i = 0; i < report.findings().size(); i++)
                assertEquals(CapabilityPresentation.finding(report.findings().get(i), report, l), text("capability-finding-" + (i + 1)));
            var overview = String.join("\n", texts(node("capability-view-OVERVIEW")));
            for (var item : report.equipment()) assertFalse(overview.contains(item.id().toString()), "no identifiers on the dashboard");
            assertTrue(overview.contains("Mount match only: 18") && overview.contains("Not established: 3") && overview.contains("Mount conflict: 0"), overview);
            snapshot("capability-overview");

            // Camera bodies: cards, selection, sortable numeric columns.
            tab(CapabilityDashboard.View.CAMERAS);
            assertEquals(3, fx(() -> ((CardGrid) shell.lookup("#capability-camera-cards")).getChildren().size()));
            click("capability-camera-card-3");
            assertEquals("Sony A7R V", fx(() -> ((Labeled) ((javafx.scene.layout.VBox) shell.lookup("#capability-camera-detail")).getChildren().getFirst()).getText()));
            var cameraRows = String.join("\n", texts(node("capability-camera-rows")));
            assertTrue(cameraRows.contains("61 MP") && cameraRows.contains("35.7 × 23.8 mm") && cameraRows.contains("Full frame"), cameraRows);
            List<String> sorted = fx(() -> {
                @SuppressWarnings("unchecked") var table = (TableView<CameraCapability>) shell.lookup("#capability-camera-table");
                var resolution = table.getColumns().get(4); resolution.setSortType(TableColumn.SortType.ASCENDING);
                table.getSortOrder().setAll(resolution); table.sort();
                return table.getItems().stream().map(c -> c.camera().model()).toList();
            });
            assertEquals(List.of("A7 IV", "A7R V", "A7 V"), sorted, "numeric order; unknown resolution last");
            snapshot("capability-cameras");

            // Lenses: chart rows, log-scale geometry, filters, equivalent mode, selection.
            tab(CapabilityDashboard.View.LENSES);
            var chart = (FocalCoverageChart) node("capability-chart");
            assertEquals(7, fx(chart::rowCount));
            double[] zoom = fx(() -> chart.markBounds(0)), tele = fx(() -> chart.markBounds(4)), prime = fx(() -> chart.markBounds(6));
            assertEquals(12, prime[1] - prime[0], 0.6, "the 16 mm prime is a marker, not a bar");
            assertTrue((prime[0] + prime[1]) / 2 < zoom[0], "16 mm sits left of 20 mm, with the gap between");
            assertEquals(Math.log(70 / 20.) / Math.log(400 / 50.), (zoom[1] - zoom[0]) / (tele[1] - tele[0]), 0.02, "bars follow the logarithmic scale");
            assertEquals(fx(() -> chart.tickX(20)), zoom[0], 1.5);
            run(() -> { @SuppressWarnings("unchecked") var type = (ComboBox<String>) shell.lookup("#capability-filter-type"); type.setValue("PRIME"); });
            assertEquals(3, fx(chart::rowCount)); assertEquals(3, fx(() -> ((TableView<?>) shell.lookup("#capability-lens-table")).getItems().size()));
            run(() -> { @SuppressWarnings("unchecked") var type = (ComboBox<String>) shell.lookup("#capability-filter-type"); type.setValue("ALL");
                @SuppressWarnings("unchecked") var aperture = (ComboBox<Double>) shell.lookup("#capability-filter-aperture"); aperture.setValue(2.8); });
            assertEquals(5, fx(chart::rowCount), "f/2.8 or brighter at the widest end");
            run(() -> { @SuppressWarnings("unchecked") var aperture = (ComboBox<Double>) shell.lookup("#capability-filter-aperture"); aperture.setValue(0.0); });
            click("capability-mode-equivalent");
            assertTrue(text("capability-chart-axis").contains("35 mm-equivalent focal length on Sony A7 IV"), text("capability-chart-axis"));
            for (int row = 0; row < 7; row++) { int r = row; assertTrue(fx(() -> chart.plotted(r)), "A7 IV has sensor data and matching mounts"); }
            run(() -> { @SuppressWarnings("unchecked") var camera = (ComboBox<CameraCapability>) shell.lookup("#capability-chart-camera"); camera.setValue(camera.getItems().get(2)); });
            assertTrue(text("capability-chart-axis").contains("Sony A7 V"));
            assertEquals("Not calculated: no valid sensor size recorded", fx(() -> chart.reason(0)));
            assertEquals("Not calculated: mount not established", fx(() -> chart.reason(4)));
            click("capability-mode-physical");
            run(() -> { var row = shell.lookup("#capability-chart-row-5");
                Event.fireEvent(row, new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null)); });
            assertTrue(String.join(" ", texts(node("capability-lens-detail"))).contains("Tamron 50-400mm F4.5-6.3 Di III VC VXD"));
            assertTrue(String.join(" ", texts(node("capability-lens-rows"))).contains("f/4.5–6.3"));
            assertEquals("f/1.4", text("capability-aperture-3"));
            snapshot("capability-lenses");

            // Compatibility: matrix cells, the shared detail rows, filters.
            tab(CapabilityDashboard.View.COMPATIBILITY);
            assertTrue(text("capability-cell-1-1").startsWith("M"), text("capability-cell-1-1"));
            assertTrue(text("capability-cell-1-2").startsWith("A"), "Sony lens on the body recorded as Sony E: equivalent labels");
            click("capability-cell-5-2");
            assertTrue(text("capability-cell-5-2").startsWith("?"));
            var pair = report.pair(A7V, T50400).orElseThrow();
            var expected = new ArrayList<>(CapabilityPresentation.pairDetails(pair, report.lens(T50400).orElseThrow(), CapabilityPresentation.names(report.equipment()), l).values());
            for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), text("capability-pair-rows-" + i));
            assertTrue(text("capability-matrix-note").contains("Mount matching alone does not establish"));
            run(() -> {
                var cell = shell.lookup("#capability-cell-7-3"); var scroll = shell.lookup(".cap-matrix-scroll");
                assertTrue(cell.localToScene(cell.getBoundsInLocal()).getMaxY() <= scroll.localToScene(scroll.getBoundsInLocal()).getMaxY() + 0.5, "the last row is not cut off");
            });
            run(() -> { @SuppressWarnings("unchecked") var status = (ComboBox<PairStatus>) shell.lookup("#capability-matrix-status"); status.setValue(PairStatus.CONFLICT); });
            assertTrue(shown("capability-matrix-empty"));
            run(() -> { @SuppressWarnings("unchecked") var status = (ComboBox<PairStatus>) shell.lookup("#capability-matrix-status"); status.setValue(PairStatus.NOT_ESTABLISHED); });
            assertNotNull(node("capability-matrix-lens-5")); assertNull(node("capability-matrix-lens-1"), "rows without a not-established pair are filtered out");
            assertTrue(text("capability-cell-5-1").contains("—"), "cells outside the status filter are neutral");
            run(() -> { @SuppressWarnings("unchecked") var status = (ComboBox<PairStatus>) shell.lookup("#capability-matrix-status"); status.setValue(null); });
            snapshot("capability-matrix");

            // Data quality: grouped by item, with the existing editor one click away.
            tab(CapabilityDashboard.View.QUALITY);
            assertEquals("Items with complete key specifications: 9 of 10", text("capability-quality-summary"));
            var quality = String.join("\n", texts(node("capability-quality-item-1")));
            assertTrue(quality.contains("Sony A7 V") && quality.contains("sensor size") && quality.contains("Inconsistent"), quality);
            assertTrue(shown("capability-naming"));
            snapshot("capability-quality");
            click("capability-edit-1");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!fx(() -> inventory.dirty())) { if (System.nanoTime() > deadline) fail("editor did not open"); Thread.sleep(20); }
            assertEquals("A7 V", fx(() -> ((TextField) shell.lookup("#gear-field-model")).getText()));
            assertTrue(fx(() -> inventory.isVisible()), "Gear Inventory is shown");
            click("gear-cancel");
            run(() -> ((ToggleButton) shell.lookup("#nav-capability-report")).fire()); await();
            assertEquals(CapabilityDashboard.View.QUALITY, fx(() -> pane.dashboard().state().view), "the view is kept when coming back");

            // Technical details: the complete report, identifiers included, and Copy.
            tab(CapabilityDashboard.View.TECHNICAL);
            String technical = fx(() -> ((TextArea) shell.lookup("#capability-technical-text")).getText());
            assertEquals(CapabilityPresentation.all(report(), l, SHOWN), technical);
            assertTrue(technical.contains(A7V.toString()));
            click("capability-copy");
            assertEquals(technical, fx(() -> Clipboard.getSystemClipboard().getString()));
            assertTrue(shown("capability-copied"));
            assertArrayEquals(stored, Files.readAllBytes(app.storageScope().inventoryFile()), "the report never writes the inventory");

            // Wide window: the report uses the width; other pages keep the reading width.
            run(() -> { stage.setWidth(1900); stage.setHeight(1000); });
            Thread.sleep(150); tab(CapabilityDashboard.View.OVERVIEW);
            assertTrue(fx(() -> shell.lookup(".feature-content").getLayoutBounds().getWidth()) > 1300, "the dashboard is not capped at the reading width");
            snapshot("capability-overview-wide");

            // Chinese, narrow window: everything translated and inside the window.
            l.useLanguage("zh-CN");
            run(() -> { stage.setWidth(880); stage.setHeight(600); });
            Thread.sleep(200);
            run(() -> { shell.applyCss(); shell.layout(); });
            assertEquals("概览", text("capability-tab-OVERVIEW")); assertEquals("3", text("capability-metric-camera"));
            assertEquals(CapabilityPresentation.finding(report.findings().getFirst(), report, l), text("capability-finding-1"));
            for (var view : CapabilityDashboard.View.values()) { tab(view); fitsHorizontally(view.name()); }
            tab(CapabilityDashboard.View.OVERVIEW); snapshot("capability-zh-narrow");
            tab(CapabilityDashboard.View.COMPATIBILITY); snapshot("capability-zh-narrow-matrix");

            // Dark mode: the same views on the dark token sheet.
            l.useLanguage("en");
            run(() -> { stage.setWidth(1400); stage.setHeight(900); scene.getStylesheets().add(new Theme(Theme.Accent.BLUE, Theme.Mode.DARK, true, 62).stylesheetUrl()); });
            Thread.sleep(150);
            tab(CapabilityDashboard.View.OVERVIEW); snapshot("capability-dark-overview");
            tab(CapabilityDashboard.View.LENSES); snapshot("capability-dark-lenses");
            tab(CapabilityDashboard.View.COMPATIBILITY); snapshot("capability-dark-matrix");

            // A failed read shows the failure state and keeps nothing stale on screen.
            Files.writeString(app.storageScope().inventoryFile(), "malformed");
            click("capability-refresh"); await();
            assertTrue(shown("capability-failure")); assertFalse(shown("capability-tabs"));
            assertFalse(fx(() -> pane.feedbackText()).isBlank());
            assertNull(fx(() -> pane.dashboard().report()));
        }
    }

    @Test void gearRequirementsPageKeepsTheProfileAboveTheSameReport() throws Exception {
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            for (var item : items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            launch(app, 1120, 760);
            run(() -> ((ToggleButton) shell.lookup("#nav-F06")).fire()); await();
            assertTrue(fx(() -> pane.profile().isVisible()), "the session's capability requirements stay on the page");
            assertTrue(shown("capability-tabs"));
            assertEquals("3", text("capability-metric-camera"));
            assertEquals("16–400 mm", text("capability-metric-span"));
            run(() -> ((ToggleButton) shell.lookup("#nav-F05")).fire());
            assertFalse(fx(() -> pane.isVisible()));
            // Showing the page again re-reads the inventory: a removed prime is gone, and so is its gap.
            assertTrue(app.facade().inventory().remove(V16, false).isSuccess());
            run(() -> ((ToggleButton) shell.lookup("#nav-F06")).fire()); await();
            assertEquals("20–400 mm", text("capability-metric-span")); assertEquals("0", text("capability-metric-gaps"));
            assertEquals("6", text("capability-metric-lens"));
            assertTrue(text("capability-status").startsWith("Analyzed at"), "a fresh analysis, not a stale snapshot");
        }
    }
}
