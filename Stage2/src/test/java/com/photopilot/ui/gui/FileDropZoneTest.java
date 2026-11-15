package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalePreferenceRepository;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.ui.gui.FileDropZone.Problem;
import com.photopilot.ui.gui.FileDropZone.State;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.ui.gui.DropGestures.*;
import static com.photopilot.ui.gui.InventoryGuiTest.fx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The reusable drop zone on its own, configured as another module would (several text
 * files, a size limit, its own check), not as the shutter count checker: validation, states, styling, callbacks, the
 * picker entry point, and that drags without files are left to the rest of the window.
 */
class FileDropZoneTest {
    @TempDir Path directory;

    LocalizationService localization;
    FileDropZone zone; VBox parent; Stage stage;
    final List<List<Path>> accepted = new ArrayList<>();
    final List<FileDropZone.Rejection> rejected = new ArrayList<>();
    final List<DragEvent> reachedParent = new ArrayList<>();

    File file(String name, int bytes) throws Exception { Path path = directory.resolve(name); Files.createDirectories(path.getParent()); Files.write(path, new byte[bytes]); return path.toFile(); }
    String hint() { return ((Label) zone.lookup(".file-drop-hint")).getText(); }
    Label error() { return (Label) zone.lookup(".file-drop-error"); }
    boolean pseudo(String name) { return zone.getPseudoClassStates().contains(PseudoClass.getPseudoClass(name)); }
    Color border() { zone.applyCss(); return (Color) zone.getBorder().getStrokes().getFirst().getTopStroke(); }
    static void near(Color expected, Color actual) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) < 0.01 && Math.abs(expected.getGreen() - actual.getGreen()) < 0.01
            && Math.abs(expected.getBlue() - actual.getBlue()) < 0.01 && Math.abs(expected.getOpacity() - actual.getOpacity()) < 0.01, expected + " != " + actual);
    }

    @Test void configurableValidationStatesAndCallbacks() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
        File notes = file("notes.txt", 10), table = file("月度 数据 表.CSV", 20), large = file("large.csv", 4096);
        File draft = file("draft.txt", 5), image = file("photo.jpg", 5);
        File folder = Files.createDirectories(directory.resolve("folder.txt")).toFile();
        try {
            fx(() -> {
                localization = new LocalizationService(LocalePreferenceRepository.transientPreference());
                localization.selectLanguage("en");
                VBox content = new VBox(new Label(), new Button());
                var options = new FileDropZone.Options().extensions(List.of(".TXT", "csv")).multiple(true).maxBytes(2048)
                    .check(path -> path.getFileName().toString().startsWith("draft") ? Optional.of(LocalizedMessage.of("fileDrop.error.invalidPath", "draft")) : Optional.empty());
                parent = new VBox(); // the zone keeps its own height, as in a card
                var texts = new FxLocalizer(parent, localization);
                zone = new FileDropZone(texts, content, options, accepted::add);
                zone.onRejected(rejected::add);
                options.extensions(List.of("jpg")).multiple(false); // the zone keeps its own copy
                parent.getChildren().add(zone);
                parent.setOnDragOver(reachedParent::add);
                stage = new Stage(); var scene = new Scene(parent, 520, 260);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
                stage.setScene(scene); stage.show(); parent.applyCss(); parent.layout();
                return true;
            });

            fx(() -> {
                assertEquals(State.IDLE, zone.state());
                assertFalse(error().isVisible() || error().isManaged(), "no error before anything was refused");
                near(Color.rgb(163, 177, 214, 0.8), border()); // the dashed import border, -pp-border-strong
                double height = zone.getHeight(), width = zone.getWidth();

                // Acceptable files over the zone: accent state and release hint, copy only (never move), same size.
                var board = files(List.of(notes, table));
                DragEvent over = hover(zone, board);
                assertTrue(over.isAccepted());
                assertEquals(TransferMode.COPY, over.getAcceptedTransferMode(), "a move would let Explorer delete the original");
                assertEquals(State.ACCEPTING, zone.state());
                assertTrue(pseudo("drag-accept") && !pseudo("drag-refuse"));
                assertEquals("Release to import", hint());
                near(Color.rgb(74, 104, 204, 0.72), border()); // -pp-accent-border
                parent.layout();
                assertEquals(height, zone.getHeight(), 0.01, "the highlight keeps the zone's size");
                assertEquals(width, zone.getWidth(), 0.01);
                assertTrue(reachedParent.isEmpty(), "file drags over the zone are consumed by it");

                // Drop: both files (several files are enabled here), unchanged paths, once.
                assertTrue(fire(zone, DragEvent.DRAG_DROPPED, board).isDropCompleted());
                fire(zone, DragEvent.DRAG_EXITED, board);
                assertEquals(List.of(List.of(notes.toPath(), table.toPath())), accepted, "Unicode and spaces in names are kept");
                assertEquals(State.IDLE, zone.state());
                assertFalse(pseudo("drag-accept"));
                accepted.clear();
                return true;
            });

            // Refused drops: reason while hovering, the drop reports failure, nothing accepted, the reason stays below the content.
            record Case(List<File> files, Problem problem, String message) { }
            for (Case refused : List.of(
                new Case(List.of(folder), Problem.DIRECTORY, "folder.txt is a folder. Drop a file instead."),
                new Case(List.of(image), Problem.EXTENSION, "photo.jpg is not a supported file type. Supported: TXT / CSV."),
                new Case(List.of(notes, large), Problem.TOO_LARGE, "large.csv is larger than 2 KB and was not imported."),
                new Case(List.of(draft), Problem.CHECK, "draft cannot be imported from this location."),
                new Case(List.of(new File("bad\0name.txt")), Problem.INVALID_PATH, "bad\0name.txt cannot be imported from this location."))) {
                fx(() -> {
                    var board = files(refused.files());
                    DragEvent over = hover(zone, board);
                    assertEquals(State.REFUSING, zone.state(), refused.problem().name());
                    assertTrue(pseudo("drag-refuse") && !pseudo("drag-accept"));
                    assertEquals(refused.message(), hint());
                    near(Color.rgb(163, 43, 28, 0.45), border()); // -pp-danger-border
                    assertTrue(over.isAccepted(), "accepted as a gesture so that the drop can explain the refusal");
                    assertFalse(fire(zone, DragEvent.DRAG_DROPPED, board).isDropCompleted());
                    fire(zone, DragEvent.DRAG_EXITED, board);
                    assertEquals(State.IDLE, zone.state());
                    assertEquals(refused.problem(), rejected.getLast().problem());
                    assertEquals(refused.problem(), zone.rejection().orElseThrow().problem());
                    assertTrue(error().isVisible() && error().isManaged());
                    assertEquals(refused.message(), error().getText());
                    return true;
                });
            }
            assertTrue(accepted.isEmpty(), "a refused drop never reaches the module");
            assertEquals(5, rejected.size());

            fx(() -> {
                // The picker entry point: the same validation; acceptance clears the previous reason.
                assertFalse(zone.submit(List.of()));
                assertEquals(Problem.NO_FILES, zone.rejection().orElseThrow().problem());
                assertEquals("There is nothing to import. Drop a file.", error().getText());
                assertTrue(zone.submit(List.of(notes.toPath())));
                assertEquals(List.of(List.of(notes.toPath())), accepted);
                assertTrue(zone.rejection().isEmpty());
                assertFalse(error().isVisible() || error().isManaged());

                // Busy: nothing is accepted, by drop or picker, and the hover says why.
                zone.setBusy(true);
                var board = files(List.of(notes));
                DragEvent over = hover(zone, board);
                assertFalse(over.isAccepted(), "the operating system shows that the drop is not possible");
                assertEquals(State.REFUSING, zone.state());
                assertEquals("Wait until the current file has been processed.", hint());
                zone.setBusy(false);
                assertEquals(State.ACCEPTING, zone.state(), "the hover follows the end of the work");
                assertEquals("Release to import", hint());
                fire(zone, DragEvent.DRAG_EXITED, board);
                zone.setBusy(true);
                assertFalse(zone.submit(List.of(notes.toPath())));
                zone.setBusy(false);
                assertEquals(1, accepted.size());

                // Drags without files (text from another control) pass through to the rest of the window untouched.
                var text = text("not a file");
                DragEvent textOver = hover(zone, text);
                assertFalse(textOver.isAccepted());
                assertEquals(State.IDLE, zone.state());
                assertFalse(reachedParent.isEmpty(), "the parent still receives drags the zone does not handle");
                assertFalse(fire(zone, DragEvent.DRAG_DROPPED, text).isDropCompleted());
                assertEquals(1, accepted.size());

                // Escape or leaving the zone during a drag: back to the default look, nothing imported.
                hover(zone, files(List.of(notes)));
                fire(zone, DragEvent.DRAG_EXITED, files(List.of(notes)));
                assertEquals(State.IDLE, zone.state());
                assertEquals(1, accepted.size());

                // Language follows the owning view.
                assertFalse(zone.submit(List.of(folder.toPath())));
                localization.selectLanguage("zh-CN");
                assertEquals("folder.txt 是文件夹。请拖入文件。", error().getText());
                return true;
            });

            // A zone in single-file mode refuses several files with its own message.
            fx(() -> {
                var single = new FileDropZone(new FxLocalizer(new Region(), localization), new Label(),
                    new FileDropZone.Options().message(Problem.MULTIPLE, count -> LocalizedMessage.of("fileDrop.error.multiple", count + "!")), accepted::add);
                assertFalse(drop(single, files(List.of(notes, table))));
                assertEquals("拖入了 2! 个文件。请一次只导入一个文件。", single.rejection().orElseThrow().message().render(localization));
                assertTrue(drop(single, files(List.of(image))), "no extensions configured: any file");
                assertEquals(List.of(image.toPath()), accepted.getLast());
                localization.selectLanguage("en");
                return true;
            });
            assertEquals("1 KB", FileDropZone.limit(1024));
            assertEquals("1.5 MB", FileDropZone.limit(1536L * 1024));
            assertEquals("2048 MB", FileDropZone.limit(2L * 1024 * 1024 * 1024));
            assertEquals("10 B", FileDropZone.limit(10));
        } finally {
            fx(() -> { if (stage != null) stage.close(); return true; });
        }
    }
}
