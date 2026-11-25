package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.CameraModel;
import com.photopilot.review.ExifFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.ui.gui.InventoryGuiTest.fx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The dedicated Camera Shutter Count page, with the real service and bundled
 * ExifTool: no manual camera selection, the supported-models dialog, the import area, loading, failure and success
 * states, and that an earlier result never stays on screen for a new file. The success state uses the private real
 * sample only when PHOTOPILOT_SHUTTER_SAMPLE/_REFERENCE are set.
 */
class ShutterCountGuiTest {
    @TempDir Path directory;
    InventoryPane inventory; ShutterCountPane card; PhotoPilotWorkspace workspace; Stage stage;

    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (fx(() -> inventory.busy() || card.busy())) { if (System.nanoTime() > deadline) fail("GUI operation timed out"); Thread.sleep(20); }
    }
    String text(String id) throws Exception { return fx(() -> ((Labeled) workspace.lookup("#" + id)).getText()); }
    boolean shown(String id) throws Exception { return fx(() -> ProfileGuiTest.shown(workspace.lookup("#" + id))); }
    Bounds scene(Node node) throws Exception { return fx(() -> node.localToScene(node.getLayoutBounds())); }
    void nav(String id) throws Exception { fx(() -> { ((ToggleButton) workspace.lookup("#nav-" + id)).fire(); workspace.applyCss(); workspace.layout(); return true; }); }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }
    /** The import area; on the JavaFX thread only. */
    FileDropZone zone() { return (FileDropZone) workspace.lookup("#shutter-import"); }
    void settle() throws Exception { ready(); fx(() -> { workspace.applyCss(); workspace.layout(); return true; }); }
    /** What "Choose photo…" does with the chooser's result (the native dialog itself cannot be automated). */
    void check(Path photo) throws Exception { assertTrue(fx(() -> zone().submit(List.of(photo))), photo.getFileName() + " accepted"); settle(); }
    /** A complete drag and drop of the files onto the import area; returns whether the drop completed. */
    boolean drop(Path... photos) throws Exception {
        boolean done = fx(() -> DropGestures.drop(zone(), DropGestures.files(Arrays.stream(photos).map(Path::toFile).toList())));
        settle(); return done;
    }
    /** Everything the results show, in order. */
    List<String> screen() throws Exception {
        List<String> shown = new ArrayList<>();
        for (String id : List.of("shutter-file-name", "shutter-file-details", "shutter-file-status", "shutter-count-value", "shutter-count-caption",
            "shutter-meta-brand", "shutter-meta-model", "shutter-meta-counter", "shutter-meta-source", "shutter-meta-date", "shutter-meta-format")) shown.add(text(id));
        shown.addAll(fx(card::messageTexts));
        return shown;
    }
    /** The drop zone's reason for the last refused drop or choice, "" when none is shown. */
    String refusal() throws Exception { return fx(() -> { var error = (Labeled) zone().lookup(".file-drop-error"); return error.isVisible() ? error.getText() : ""; }); }
    String hint() throws Exception { return fx(() -> ((Labeled) zone().lookup(".file-drop-hint")).getText()); }
    /** The open supported-models dialog, or null; on the JavaFX thread only. */
    DialogPane modelsDialog() {
        return javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing).map(javafx.stage.Window::getScene).filter(Objects::nonNull)
            .map(scene -> scene.getRoot().lookup("#shutter-models-dialog")).filter(Objects::nonNull).map(DialogPane.class::cast).findFirst().orElse(null);
    }
    String modelsText(DialogPane pane, String id) throws Exception { return fx(() -> ((Labeled) pane.lookup("#" + id)).getText()); }
    /** The listed models, each as "name · support level". */
    List<String> modelRows(DialogPane pane) throws Exception {
        return fx(() -> pane.lookupAll(".shutter-models-row").stream()
            .map(row -> ((javafx.scene.layout.HBox) row).getChildren().stream().map(label -> ((Labeled) label).getText()).collect(java.util.stream.Collectors.joining(" · "))).toList());
    }
    void snapshotCard(String name) throws Exception { fx(() -> { workspace.applyCss(); workspace.layout(); return true; }); snapshotNode(card, name); }
    void snapshotNode(Node node, String name) throws Exception {
        fx(() -> {
            node.applyCss(); if (node instanceof javafx.scene.Parent parent) parent.layout();
            var image = node.snapshot(null, null); var pixels = image.getPixelReader();
            var buffered = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, pixels.getArgb(x, y));
            javax.imageio.ImageIO.write(buffered, "png", new java.io.File("target/" + name + ".png"));
            return true;
        });
    }
    /** Resizes the real window and waits until the layout reached the expected arrangement (window resizes arrive asynchronously). */
    void size(double width, double height, boolean threeColumns) throws Exception {
        fx(() -> { stage.setWidth(width); stage.setHeight(height); return true; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(() -> { workspace.applyCss(); workspace.layout(); return card.threeColumns() == threeColumns; })) {
            if (System.nanoTime() > deadline) break; Thread.sleep(30);
        }
        assertEquals(threeColumns, fx(card::threeColumns), width + " px window: three result columns");
    }

    @Test void cardReadsPhotosWithoutSelectionListsSupportedModelsAndShowsNoStaleValues() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
        var executor = Executors.newSingleThreadExecutor();
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            fx(() -> {
                inventory = new InventoryPane(app.facade(), executor, app.localization());
                card = new ShutterCountPane(app.facade(), executor, app.localization());
                workspace = new PhotoPilotWorkspace(app.localization()); workspace.connectInventory(inventory); workspace.connectShutterCount(card);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                return true;
            });
            nav("F05"); ready();
            var scroll = fx(() -> (ScrollPane) workspace.lookup(".content-scroll"));

            // Only the inventory body takes space. No shutter controls or replacement card are attached.
            var page = fx(() -> (FeaturePane) workspace.lookup(".feature-content"));
            assertEquals(fx(() -> List.of(inventory.getParent())), fx(() -> page.getChildren().stream().filter(Node::isManaged).toList()));
            assertNull(fx(() -> workspace.lookup("#shutter-count")));
            assertTrue(shown("gear-table") && shown("gear-search") && shown("gear-add"), "inventory controls stay in place");
            nav("shutter");
            assertEquals(List.of(card), fx(() -> page.getChildren().stream().filter(Node::isManaged).toList()));
            assertNull(fx(() -> workspace.lookup("#gear-table")));

            // Initial state: no manual camera selection at all, an enabled import, empty placeholders.
            for (String removed : List.of("shutter-model", "shutter-series", "shutter-search", "shutter-brand-sony", "shutter-brand-nikon", "shutter-selection", "shutter-support", "shutter-catalog-source"))
                assertNull(fx(() -> workspace.lookup("#" + removed)), removed + " is gone");
            assertTrue(fx(() -> card.lookupAll(".combo-box").isEmpty() && card.lookupAll(".radio-button").isEmpty() && card.lookupAll(".text-field").isEmpty()),
                "the card has no camera selection controls");
            assertFalse(fx(() -> workspace.lookup("#shutter-choose").isDisabled()));
            assertEquals("No photo imported", text("shutter-file-name"));
            assertEquals("—", text("shutter-count-value"));
            assertFalse(shown("shutter-messages"));

            // The supported-models link directly follows the description sentence, in the accent (blue) text colour.
            var link = fx(() -> (Hyperlink) workspace.lookup("#shutter-models-link"));
            var description = fx(() -> (Labeled) card.lookup(".description"));
            assertEquals("Read a camera shutter count from photo metadata. Supports some Sony and Nikon models.", fx(description::getText));
            assertEquals("Supported models", fx(link::getText));
            assertEquals(fx(() -> List.of(description, link)), fx(() -> List.copyOf(link.getParent().getChildrenUnmodifiable())));
            Bounds sentence = scene(description), linkBounds = scene(link);
            assertTrue(linkBounds.getMinX() >= sentence.getMaxX() && linkBounds.getMinX() - sentence.getMaxX() < 20
                && Math.abs(linkBounds.getMinY() - sentence.getMinY()) < 6, "right after the sentence: " + sentence + " " + linkBounds);
            FileDropZoneTest.near(Color.rgb(52, 78, 166), (Color) fx(link::getTextFill)); // -pp-accent-text

            // The import area is taller (it was 152 px) and its content stays centred.
            Bounds area = fx(() -> zone().localToScene(zone().getLayoutBounds()));
            Bounds importContent = fx(() -> { var content = workspace.lookup("#shutter-choose").getParent(); return content.localToScene(content.getLayoutBounds()); });
            assertTrue(area.getHeight() >= 239, "taller import area: " + area.getHeight());
            assertEquals(area.getCenterY(), importContent.getCenterY(), 2, "content centred vertically");
            assertEquals(area.getCenterX(), importContent.getCenterX(), 2, "content centred horizontally");
            snapshotCard("shutter-count-top-en");

            // Supported models: a modal, lookup-only list from the camera catalog, by brand, searchable, closed by Close.
            var catalog = app.facade().shutterCount().catalog();
            fx(() -> { link.fire(); return true; });
            var models = fx(this::modelsDialog);
            assertNotNull(models, "the dialog opens");
            assertNotEquals(Modality.NONE, fx(() -> ((Stage) models.getScene().getWindow()).getModality()), "modal");
            assertEquals("Supported camera models", fx(models::getHeaderText));
            assertTrue(fx(() -> ((Labeled) models.lookup(".shutter-models-intro")).getText()).contains("for reference only"));
            assertTrue(fx(() -> ((ToggleButton) models.lookup("#shutter-models-sony")).isSelected()));
            assertEquals("Showing 360 of 360 Sony models", modelsText(models, "shutter-models-count"));
            assertEquals(360, fx(() -> models.lookupAll(".shutter-models-row").size()));
            assertEquals(catalog.series(CameraModel.Brand.SONY).size(), fx(() -> models.lookupAll(".shutter-models-group").size()), "grouped by series");
            assertTrue(modelsText(models, "shutter-models-source").contains("https://exiftool.org/models.html"));
            fx(() -> { models.applyCss(); models.layout(); return true; });
            snapshotNode(models, "shutter-models-dialog-en");
            var search = fx(() -> (TextField) models.lookup("#shutter-models-search"));
            fx(() -> { search.setText("A7R V"); return true; });
            assertEquals(List.of("α7R V (ILCE-7RM5) · Verified with a real photo"), modelRows(models));
            assertEquals("Showing 1 of 360 Sony models", modelsText(models, "shutter-models-count"));
            fx(() -> { search.setText("ILCE-7M4"); return true; });
            assertEquals(List.of("α7 IV (ILCE-7M4) · Documented by ExifTool"), modelRows(models));
            fx(() -> { search.setText("Z6II"); return true; });
            assertEquals(List.of(), modelRows(models));
            assertEquals("No Sony model matches “Z6II”.", modelsText(models, "shutter-models-empty"));
            fx(() -> { ((ToggleButton) models.lookup("#shutter-models-nikon")).fire(); return true; });
            var nikon = modelRows(models); // the search is kept when the brand changes (the catalog search also finds Z 6III)
            assertTrue(nikon.stream().anyMatch(row -> row.startsWith("Z 6II (")) && nikon.stream().allMatch(row -> row.replace(" ", "").startsWith("Z6II")), nikon.toString());
            assertEquals("Showing " + nikon.size() + " of 300 Nikon models", modelsText(models, "shutter-models-count"));
            fx(() -> { ((ToggleButton) models.lookup("#shutter-models-nikon")).fire(); return true; });
            assertTrue(fx(() -> ((ToggleButton) models.lookup("#shutter-models-nikon")).isSelected()), "one brand is always chosen");
            fx(() -> { search.clear(); return true; });
            assertEquals(300, modelRows(models).size());
            fx(() -> { ((Button) models.lookupButton(models.getButtonTypes().getFirst())).fire(); return true; });
            assertNull(fx(this::modelsDialog), "Close closes it");
            // Escape closes it too, also from the search field; the card is unchanged by either.
            fx(() -> { link.fire(); return true; });
            var again = fx(this::modelsDialog);
            assertEquals("Showing 360 of 360 Sony models", modelsText(again, "shutter-models-count"), "a fresh list each time");
            fx(() -> {
                var field = again.lookup("#shutter-models-search"); field.requestFocus();
                javafx.event.Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                return true;
            });
            assertNull(fx(this::modelsDialog), "Escape closes it");
            assertEquals("No photo imported", text("shutter-file-name"));
            assertTrue(fx(() -> card.lookupAll(".combo-box").isEmpty() && card.lookupAll(".text-field").isEmpty()), "nothing was selected into the card");

            // A photo without maker notes: read by the bundled ExifTool, explained, no count.
            Path jpeg = ExifFixtures.jpeg(directory.resolve("照片 测试"), "edited A7R V.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:01:02 03:04:05").withCamera("SONY", "ILCE-7RM5"));
            check(jpeg);
            assertEquals("edited A7R V.jpg", text("shutter-file-name"));
            assertEquals("No maker notes", text("shutter-file-status"));
            assertEquals("—", text("shutter-count-value"), "never a substituted zero");
            assertEquals("Sony", text("shutter-meta-brand"));
            assertEquals("α7R V (ILCE-7RM5)", text("shutter-meta-model"));
            assertEquals("2026-01-02 03:04:05", text("shutter-meta-date"));
            assertEquals("JPEG", text("shutter-meta-format"));
            var messages = fx(card::messageTexts);
            assertTrue(messages.stream().anyMatch(line -> line.startsWith("α7R V (ILCE-7RM5): Verified with a real photo")), "support of the detected camera: " + messages);
            assertTrue(messages.stream().noneMatch(line -> line.contains("selected") || line.contains("mismatch")), "no model comparison: " + messages);
            assertTrue(messages.stream().anyMatch(line -> line.startsWith("This JPEG has no manufacturer maker notes")), messages.toString());
            fx(() -> { scroll.setVvalue(1); return true; }); snapshot("shutter-count-no-makernotes-en");

            // Drag and drop goes through the same validation and handler as the chooser: the same screen for the same
            // file, from exactly one check per drop.
            var chosen = screen();
            check(directory.resolve("missing.ARW"));
            long checks = fx(card::checks);
            assertTrue(drop(jpeg), "the drop completes");
            assertEquals(checks + 1, fx(card::checks), "one drop, one check");
            assertEquals(chosen, screen(), "a dropped photo shows exactly what the chosen one did");
            assertEquals("", refusal());

            // While a supported photo is over the import area: the accent state and the release hint, nothing started yet.
            fx(() -> { DropGestures.hover(zone(), DropGestures.files(List.of(jpeg.toFile()))); return true; });
            assertEquals(FileDropZone.State.ACCEPTING, fx(() -> zone().state()));
            assertEquals("Release to import the photo", hint());
            snapshotCard("shutter-count-drag-accept-en");
            fx(() -> { DropGestures.fire(zone(), javafx.scene.input.DragEvent.DRAG_EXITED, DropGestures.files(List.of(jpeg.toFile()))); return true; });
            assertEquals(FileDropZone.State.IDLE, fx(() -> zone().state()), "leaving the area (or Escape) restores it");
            assertEquals(checks + 1, fx(card::checks));

            // Refused drops keep the previous result on screen and say why, in the import area; no check starts.
            Path notes = Files.writeString(directory.resolve("notes.txt"), "not a photo");
            assertFalse(drop(notes));
            assertEquals("notes.txt is not a supported format. Choose a JPG, JPEG, ARW, NEF or DNG file.", refusal());
            assertEquals(chosen, screen(), "a refused drop keeps the previous result");
            assertFalse(drop(jpeg, notes));
            assertEquals("2 files were dropped. Import one photo at a time.", refusal());
            assertFalse(drop(jpeg.getParent()));
            assertEquals("照片 测试 is a folder. Drop a file instead.", refusal());
            fx(() -> { DropGestures.hover(zone(), DropGestures.files(List.of(notes.toFile()))); return true; });
            assertEquals(FileDropZone.State.REFUSING, fx(() -> zone().state()));
            assertEquals("notes.txt is not a supported format. Choose a JPG, JPEG, ARW, NEF or DNG file.", hint());
            snapshotCard("shutter-count-drag-refuse-en");
            fx(() -> { DropGestures.fire(zone(), javafx.scene.input.DragEvent.DRAG_EXITED, DropGestures.files(List.of(notes.toFile()))); return true; });
            snapshotCard("shutter-count-drop-refused-en");
            assertEquals(chosen, screen());
            assertEquals(checks + 1, fx(card::checks), "refused drops start no check");

            // Extensions in any case; a new photo clears the refusal.
            Path upper = Files.copy(jpeg, jpeg.resolveSibling("EDITED A7R V.JPG"));
            assertTrue(drop(upper));
            assertEquals("EDITED A7R V.JPG", text("shutter-file-name"));
            assertEquals("No maker notes", text("shutter-file-status"));
            assertEquals("", refusal());

            // While a photo is being read, a drag is not accepted and says why; the running check is not duplicated.
            long running = fx(() -> {
                assertTrue(zone().submit(List.of(jpeg)));
                assertTrue(card.busy() && zone().isBusy());
                var board = DropGestures.files(List.of(jpeg.toFile()));
                assertFalse(DropGestures.hover(zone(), board).isAccepted());
                assertEquals("A photo is being read. Wait until it has finished, then import the next one.", ((Labeled) zone().lookup(".file-drop-hint")).getText());
                DropGestures.fire(zone(), javafx.scene.input.DragEvent.DRAG_EXITED, board);
                return card.checks();
            });
            settle();
            assertEquals(checks + 3, running);
            assertEquals(running, fx(card::checks));
            assertEquals("edited A7R V.jpg", text("shutter-file-name"));

            // A failing file clears everything from the previous one.
            check(directory.resolve("missing.ARW"));
            assertEquals("missing.ARW", text("shutter-file-name"));
            assertEquals("Failed", text("shutter-file-status"));
            assertEquals("—", text("shutter-meta-model"), "the previous photo's camera is not shown for this file");
            assertEquals("—", text("shutter-meta-date"));
            assertEquals(List.of("missing.ARW was not found."), fx(card::messageTexts));

            // Success with the private real sample, when it is configured.
            String sample = System.getenv("PHOTOPILOT_SHUTTER_SAMPLE"), reference = System.getenv("PHOTOPILOT_SHUTTER_REFERENCE");
            if (sample != null && reference != null && Files.isRegularFile(Path.of(sample))) {
                check(Path.of(sample));
                assertEquals(NumberFormat.getIntegerInstance(Locale.ENGLISH).format(Long.parseLong(reference.strip())), text("shutter-count-value"));
                assertEquals("Read", text("shutter-file-status"));
                assertEquals("Total image exposures", text("shutter-count-caption"));
                assertTrue(text("shutter-meta-source").endsWith("› ShutterCount"), text("shutter-meta-source"));
                assertTrue(fx(card::messageTexts).stream().anyMatch(line -> line.startsWith("α7R V (ILCE-7RM5): Verified with a real photo")), fx(card::messageTexts).toString());
                assertTrue(fx(card::messageTexts).stream().noneMatch(line -> line.contains("selected model") || line.contains("mismatch")));
                fx(() -> { scroll.setVvalue(1); return true; }); snapshot("shutter-count-success-en");
                var chosenRaw = screen();
                check(directory.resolve("missing.ARW"));
                assertTrue(drop(Path.of(sample)), "the real RAW sample can be dropped");
                assertEquals(chosenRaw, screen(), "dropped and chosen RAW show the same count, source and metadata");
                check(directory.resolve("missing.ARW"));
                assertEquals("—", text("shutter-count-value"), "a failure after a success shows no count");
                assertEquals("—", text("shutter-meta-source"));
                check(Path.of(sample));
            } else System.out.println("ShutterCountGuiTest: real sample not configured; success state checked by the CLI/service tests only");

            // A round trip through other pages preserves the last result on the dedicated tool.
            var lastResult = screen();
            nav("F08"); assertNull(fx(() -> workspace.lookup("#shutter-count")));
            nav("F05"); ready(); assertNull(fx(() -> workspace.lookup("#shutter-count")));
            nav("shutter"); assertEquals(lastResult, screen());

            // Narrow window: the results stack and nothing is wider than the content area.
            size(880, 600, false);
            assertTrue(fx(card::getWidth) <= fx(() -> scroll.getViewportBounds().getWidth()), "no horizontal overflow");
            assertTrue(scene(link).getMaxX() <= scene(card).getMaxX() && scene(description).getMaxX() <= scene(card).getMaxX(), "the description and link stay inside the card");
            assertTrue(fx(() -> zone().getHeight()) >= 239, "the import area keeps its height in a narrow window");
            fx(() -> { scroll.setVvalue(1); return true; });
            snapshot("shutter-count-en-narrow");

            // Chinese.
            size(1120, 760, true);
            fx(() -> { app.localization().selectLanguage("zh-CN"); workspace.applyCss(); workspace.layout(); return true; });
            assertEquals("快门次数检测", fx(() -> ((Labeled) card.lookup(".feature-title")).getText()));
            assertEquals("选择照片…", text("shutter-choose"));
            assertEquals("支持机型查询", fx(link::getText));
            fx(() -> { link.fire(); return true; });
            var chinese = fx(this::modelsDialog);
            assertEquals("支持机型查询", fx(chinese::getHeaderText));
            assertEquals("显示 360 个，共 360 个 Sony 机型", modelsText(chinese, "shutter-models-count"));
            fx(() -> { ((TextField) chinese.lookup("#shutter-models-search")).setText("α7R"); chinese.applyCss(); chinese.layout(); return true; });
            assertTrue(modelRows(chinese).contains("α7R V (ILCE-7RM5) · 已用真实照片验证"), modelRows(chinese).toString());
            snapshotNode(chinese, "shutter-models-dialog-zh");
            fx(() -> { ((Button) chinese.lookupButton(chinese.getButtonTypes().getFirst())).fire(); return true; });
            assertNull(fx(this::modelsDialog));
            check(directory.resolve("missing.ARW"));
            assertEquals("读取失败", text("shutter-file-status"));
            assertEquals(List.of("找不到文件 missing.ARW。"), fx(card::messageTexts));
            check(jpeg);
            assertEquals("缺少厂商元数据", text("shutter-file-status"));
            fx(() -> { DropGestures.hover(zone(), DropGestures.files(List.of(jpeg.toFile()))); return true; });
            assertEquals("松开即可导入照片", hint());
            fx(() -> { DropGestures.fire(zone(), javafx.scene.input.DragEvent.DRAG_EXITED, DropGestures.files(List.of(jpeg.toFile()))); return true; });
            assertFalse(drop(jpeg, notes));
            assertEquals("拖入了 2 个文件。请一次只导入一张照片。", refusal());
            assertEquals("缺少厂商元数据", text("shutter-file-status"), "the previous result stays");
            fx(() -> { scroll.setVvalue(0.75); return true; });
            snapshot("shutter-count-zh");
        } finally {
            fx(() -> { if (stage != null) stage.close(); return true; });
            executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
