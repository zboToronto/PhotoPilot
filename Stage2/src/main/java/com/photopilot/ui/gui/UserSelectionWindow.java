package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import javafx.geometry.*;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;

/**
 * Borderless window for the workspace chooser. JavaFX 21 cannot draw content under a native title bar,
 * so this window owns working move, resize and window controls; the decorated main window is untouched.
 */
final class UserSelectionWindow extends Stage {
    private static final double BAR_HEIGHT = 40, EDGE = 6, DEFAULT_WIDTH = 1120, DEFAULT_HEIGHT = 790;
    private final FxLocalizer texts;
    private final StackPane root = new StackPane();
    private Rectangle2D restored;
    private double frameLeft, frameTop, frameWidth, frameHeight;
    private double dragX, dragY;
    private Cursor resizing;
    private Rectangle2D resizeStart;

    UserSelectionWindow(LocalizationService language) {
        initStyle(StageStyle.UNDECORATED); setMinWidth(880); setMinHeight(600); AppIcons.windowIcon(this);
        texts = new FxLocalizer(root, language); texts.useReadableFont(root); texts.text(titleProperty(), "app.title");
        var title = texts.label("app.title", "window-title");
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var bar = new HBox(11, AppIcons.logo(26), title, spacer,
            control("window-minimize", "window.minimize", AppIcons.MINIMIZE, () -> setIconified(true)),
            control("window-maximize", "window.maximize", AppIcons.MAXIMIZE, this::toggleMaximized),
            control("window-close", "window.close", AppIcons.CLOSE, () -> fireEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSE_REQUEST))));
        bar.setId("window-bar"); bar.getStyleClass().add("window-bar"); bar.setAlignment(Pos.CENTER_LEFT); bar.setMaxHeight(BAR_HEIGHT); bar.setMinHeight(BAR_HEIGHT);
        StackPane.setAlignment(bar, Pos.TOP_CENTER);
        bar.setOnMousePressed(event -> { dragX = event.getScreenX() - getX(); dragY = event.getScreenY() - getY(); });
        bar.setOnMouseDragged(event -> { if (restored == null && resizing == null) { setX(event.getScreenX() - dragX); setY(event.getScreenY() - dragY); } });
        bar.setOnMouseClicked(event -> { if (event.getClickCount() == 2) toggleMaximized(); });
        root.getChildren().add(bar);
        var scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.web("#a8472d"));
        scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        setScene(scene); enableResizing(scene);
    }
    private Button control(String id, String label, String icon, Runnable action) {
        var button = new Button(); button.setId(id); button.getStyleClass().setAll("window-control");
        button.setGraphic(AppIcons.outline(icon, "window-icon")); button.setOnAction(event -> action.run());
        button.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        texts.text(button.accessibleTextProperty(), label); return button;
    }
    /** Opens over the main window's position; the outer frame sizes are remembered for the way back. */
    void present(Stage anchor, UserSelectionPane pane) {
        root.getChildren().addFirst(pane);
        var scene = anchor.getScene();
        if (anchor.isShowing() && scene != null) {
            frameLeft = scene.getX(); frameTop = 0; frameWidth = anchor.getWidth() - scene.getWidth(); frameHeight = anchor.getHeight() - scene.getY() - scene.getHeight();
            setX(anchor.getX() + frameLeft); setY(anchor.getY()); setWidth(scene.getWidth()); setHeight(scene.getY() + scene.getHeight());
        } else centerOnScreen();
        show();
        if (anchor.isMaximized()) toggleMaximized();
    }
    void restore(Stage anchor) {
        anchor.setMaximized(restored != null);
        if (restored != null) return;
        anchor.setX(getX() - frameLeft); anchor.setY(getY() - frameTop); anchor.setWidth(getWidth() + frameWidth); anchor.setHeight(getHeight() + frameHeight);
    }
    void dispose() { texts.close(); hide(); }
    boolean maximizedToScreen() { return restored != null; }
    void toggleMaximized() {
        if (restored != null) { apply(restored); restored = null; return; }
        var current = new Rectangle2D(getX(), getY(), getWidth(), getHeight());
        var screen = Screen.getScreensForRectangle(current).stream().findFirst().orElse(Screen.getPrimary());
        restored = current; apply(screen.getVisualBounds());
    }
    private void apply(Rectangle2D bounds) { setX(bounds.getMinX()); setY(bounds.getMinY()); setWidth(bounds.getWidth()); setHeight(bounds.getHeight()); }
    private void enableResizing(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> { if (resizing == null) scene.setCursor(edge(event)); });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            var cursor = edge(event);
            if (cursor == Cursor.DEFAULT) return;
            resizing = cursor; resizeStart = new Rectangle2D(getX(), getY(), getWidth(), getHeight()); event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (resizing == null) return;
            resize(event.getScreenX(), event.getScreenY()); event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> { if (resizing != null) { resizing = null; event.consume(); } });
    }
    private Cursor edge(MouseEvent event) {
        if (restored != null) return Cursor.DEFAULT;
        boolean west = event.getSceneX() <= EDGE, east = event.getSceneX() >= getScene().getWidth() - EDGE;
        boolean north = event.getSceneY() <= EDGE, south = event.getSceneY() >= getScene().getHeight() - EDGE;
        if (north) return west ? Cursor.NW_RESIZE : east ? Cursor.NE_RESIZE : Cursor.N_RESIZE;
        if (south) return west ? Cursor.SW_RESIZE : east ? Cursor.SE_RESIZE : Cursor.S_RESIZE;
        return west ? Cursor.W_RESIZE : east ? Cursor.E_RESIZE : Cursor.DEFAULT;
    }
    private void resize(double x, double y) {
        double left = resizeStart.getMinX(), top = resizeStart.getMinY(), right = resizeStart.getMaxX(), bottom = resizeStart.getMaxY();
        if (resizing == Cursor.W_RESIZE || resizing == Cursor.NW_RESIZE || resizing == Cursor.SW_RESIZE) left = Math.min(x, right - getMinWidth());
        if (resizing == Cursor.E_RESIZE || resizing == Cursor.NE_RESIZE || resizing == Cursor.SE_RESIZE) right = Math.max(x, left + getMinWidth());
        if (resizing == Cursor.N_RESIZE || resizing == Cursor.NW_RESIZE || resizing == Cursor.NE_RESIZE) top = Math.min(y, bottom - getMinHeight());
        if (resizing == Cursor.S_RESIZE || resizing == Cursor.SW_RESIZE || resizing == Cursor.SE_RESIZE) bottom = Math.max(y, top + getMinHeight());
        apply(new Rectangle2D(left, top, right - left, bottom - top));
    }
}
