package com.photopilot.ui.gui;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

/**
 * Skin of every ComboBox in PhotoPilot, assigned centrally by photopilot.css ({@code -fx-skin}). Values, items, cells,
 * converters, selection, keyboard (arrows, Enter, Escape), mouse and accessibility are the standard
 * {@link ComboBoxListViewSkin}'s, and the expanded list is still its own ListView. This adds presentation only:
 * <ul>
 * <li>A frosted backdrop: the window content under the open list, Gaussian-blurred with the same {@link WaveRenderer}
 *     blur and scale as the panels' backdrop, drawn behind the list's translucent theme surface (which follows the
 *     Transparency setting through the same tokens as cards). Hidden when surfaces are opaque.</li>
 * <li>Placement inside the application window: directly under the control, or above it when there is more room
 *     there; never past the window's edges; at most as tall as that room (the list then scrolls). It is as wide as
 *     the control, wider only when an option needs it, and never wider than the window.</li>
 * <li>Readable text on that backdrop: after each frost the actual range of the blurred pixels under the list (for an
 *     opaque list: any backdrop) goes to {@link DropdownContrast}, and only the text colours (or, in the rare case that needs it, the list's own surface
 *     opacity) that would fall below 4.5:1 there are overridden on this open list. Theme colours that already read stay
 *     exactly as they are; nothing global changes.</li>
 * </ul>
 */
public class DropdownSkin<T> extends ComboBoxListViewSkin<T> {
    /** Distance between the control and its list, and the free margin kept inside the window. */
    static final double GAP = 4, MARGIN = 8;
    /** The panels' backdrop blur (ThemeBackdrop): a quarter-size copy blurred with sigma 10, about 40 px on screen. */
    private static final double FROST_SCALE = .25, FROST_SIGMA = 10, FROST_MARGIN = 24;
    /** Transparency from which the list's surface is opaque enough that no frost is drawn behind it. */
    static final int OPAQUE = 99;
    private static final String STYLESHEET = java.util.Objects.requireNonNull(PhotoPilotGUI.class.getResource("photopilot.css")).toExternalForm();
    private final ComboBox<T> combo;
    private final ImageView frost = new ImageView();
    private final Rectangle frostShape = new Rectangle();
    private final EventHandler<Event> showing = event -> fitToWindow(), shown = event -> refreshLater();
    // Held strongly here; ThemeManager keeps listeners weakly.
    private final Runnable themeChanged = this::refreshLater;
    private boolean refreshQueued;
    // The list's inline style: the control's font (fitToWindow) followed by the readable-text overrides (readable).
    private String fontStyle = "", contrastStyle = "";
    private DropdownContrast.Result contrast;
    private Theme contrastTheme;

    public DropdownSkin(ComboBox<T> combo) {
        super(combo);
        this.combo = combo;
        frost.setId("dropdown-frost"); frost.setManaged(false); frost.setMouseTransparent(true); frost.setSmooth(true);
        frost.setClip(frostShape); frost.setVisible(false);
        combo.addEventHandler(ComboBoxBase.ON_SHOWING, showing);
        combo.addEventHandler(ComboBoxBase.ON_SHOWN, shown);
        list().layoutBoundsProperty().addListener((property, old, bounds) -> { if (combo.isShowing()) refreshLater(); });
        ThemeManager.get().listen(themeChanged);
    }
    private Region list() { return (Region) getPopupContent(); }

    /** Before the list is sized: limit it to the room the window offers, so it scrolls instead of leaving the window. */
    private void fitToWindow() {
        var scene = combo.getScene();
        if (scene == null) return;
        Bounds anchor = combo.localToScene(combo.getLayoutBounds());
        double below = scene.getHeight() - anchor.getMaxY() - GAP - MARGIN, above = anchor.getMinY() - GAP - MARGIN;
        var list = list();
        // A popup copies only its owner scene's stylesheets; where photopilot.css sits on a dialog pane instead (the
        // startup recovery dialog), the list carries it itself so it looks the same everywhere.
        if (!scene.getStylesheets().contains(STYLESHEET) && !list.getStylesheets().contains(STYLESHEET)) list.getStylesheets().add(STYLESHEET);
        list.setMaxHeight(Math.max(80, Math.max(below, above)));
        // As wide as the control; wider only when an option needs it (as standard), never wider than the window.
        list.setMaxWidth(Math.max(combo.getWidth(), scene.getWidth() - 2 * MARGIN));
        // The list shares the control's font (the Chinese UI font and the Bold Text face are set on the window content,
        // not on the popup).
        if (combo.lookup(".list-cell") instanceof Labeled shown)
            fontStyle = "-fx-font-family: \"" + shown.getFont().getFamily() + "\"; -fx-font-weight: "
                + (shown.getFont().getStyle().matches("(?i).*\\bbold\\b.*") ? "bold" : "normal") + "; ";
        // Overrides found for another appearance are dropped until this opening's frost has been measured.
        if (!ThemeManager.get().theme().equals(contrastTheme)) { contrast = null; contrastStyle = ""; contrastTheme = null; }
        restyle();
    }
    private void restyle() {
        String style = (fontStyle + contrastStyle).strip();
        if (!style.equals(list().getStyle())) list().setStyle(style);
    }
    /** After showing or resizing: once per pulse, place the list and refresh its frosted backdrop. */
    private void refreshLater() {
        if (refreshQueued) return;
        refreshQueued = true;
        Platform.runLater(() -> { refreshQueued = false; if (combo.isShowing()) { place(); frost(); } else frost.setVisible(false); });
    }
    /** Under the control, or above it when the room below is too small and smaller than above; inside the window. */
    void place() {
        var list = list(); var scene = combo.getScene();
        if (list.getScene() == null || scene == null || scene.getWindow() == null) return;
        Window popup = list.getScene().getWindow(), owner = scene.getWindow();
        Bounds anchor = combo.localToScreen(combo.getLayoutBounds()), content = list.localToScreen(list.getLayoutBounds());
        if (popup == null || anchor == null || content == null) return;
        double left = owner.getX() + scene.getX(), top = owner.getY() + scene.getY();
        double right = left + scene.getWidth(), bottom = top + scene.getHeight();
        double below = bottom - MARGIN - (anchor.getMaxY() + GAP), above = anchor.getMinY() - GAP - (top + MARGIN);
        double y = content.getHeight() <= below || below >= above ? anchor.getMaxY() + GAP : anchor.getMinY() - GAP - content.getHeight();
        double x = Math.max(left + MARGIN, Math.min(anchor.getMinX(), right - MARGIN - content.getWidth()));
        if (Math.abs(x - content.getMinX()) > .5) popup.setX(popup.getX() + x - content.getMinX());
        if (Math.abs(y - content.getMinY()) > .5) popup.setY(popup.getY() + y - content.getMinY());
    }
    /** Blurs what lies under the list (in the owner window) and shows it behind the list's translucent surface. */
    void frost() {
        var list = list(); var scene = combo.getScene();
        boolean opaque = ThemeManager.get().theme().opacity() >= OPAQUE;
        boolean wanted = !opaque && scene != null && list.getScene() != null
            && list.getParent() instanceof Pane && list.getWidth() > 0 && list.getHeight() > 0;
        frost.setVisible(wanted);
        // An opaque list hides what is under it, so it is checked over any backdrop; otherwise wait for a measurable frost.
        if (!wanted) { readable(opaque ? DropdownContrast.UNKNOWN : null); return; }
        var host = (Pane) list.getParent();   // the popup's own root, which holds only the list
        if (frost.getParent() != host) host.getChildren().add(0, frost);
        Bounds content = list.localToScreen(list.getLayoutBounds());
        Bounds under = content == null ? null : scene.getRoot().screenToLocal(content);
        if (under == null) { frost.setVisible(false); readable(null); return; }
        int width = (int) Math.ceil(under.getWidth() + 2 * FROST_MARGIN), height = (int) Math.ceil(under.getHeight() + 2 * FROST_MARGIN);
        var params = new SnapshotParameters();
        params.setFill(ThemeManager.get().theme().backdropBase());
        params.setViewport(new Rectangle2D(Math.floor(under.getMinX() - FROST_MARGIN), Math.floor(under.getMinY() - FROST_MARGIN), width, height));
        frost.setImage(blurred(scene.getRoot().snapshot(params, null)));
        frost.setFitWidth(width); frost.setFitHeight(height);
        frost.relocate(list.getLayoutX() - FROST_MARGIN, list.getLayoutY() - FROST_MARGIN);
        double radius = 0;
        var background = list.getBackground();
        if (background != null && !background.getFills().isEmpty()) radius = background.getFills().getFirst().getRadii().getTopLeftHorizontalRadius();
        frostShape.setX(FROST_MARGIN); frostShape.setY(FROST_MARGIN); frostShape.setWidth(list.getWidth()); frostShape.setHeight(list.getHeight());
        frostShape.setArcWidth(radius * 2); frostShape.setArcHeight(radius * 2);
        readable(under(frost.getImage(), width, height, list.getWidth(), list.getHeight()));
    }
    /**
     * Darkest and lightest of each channel of the frost shown behind the list (its pixels under the list, and one more on
     * each side: the image is drawn scaled up and smoothed, so what is painted lies between neighbouring pixels).
     */
    static Color[] under(Image image, double shownWidth, double shownHeight, double listWidth, double listHeight) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        double sx = w / shownWidth, sy = h / shownHeight;
        int x0 = Math.max(0, (int) Math.floor(FROST_MARGIN * sx) - 1), x1 = Math.min(w - 1, (int) Math.ceil((FROST_MARGIN + listWidth) * sx) + 1);
        int y0 = Math.max(0, (int) Math.floor(FROST_MARGIN * sy) - 1), y1 = Math.min(h - 1, (int) Math.ceil((FROST_MARGIN + listHeight) * sy) + 1);
        double[] low = { 1, 1, 1 }, high = { 0, 0, 0 };
        var pixels = image.getPixelReader();
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            Color c = pixels.getColor(x, y);
            double[] channel = { c.getRed(), c.getGreen(), c.getBlue() };
            for (int i = 0; i < 3; i++) { low[i] = Math.min(low[i], channel[i]); high[i] = Math.max(high[i], channel[i]); }
        }
        return new Color[] { Color.color(low[0], low[1], low[2]), Color.color(high[0], high[1], high[2]) };
    }
    /** Applies the readable text colours for the backdrop range (null: not measurable yet, the theme's colours stay). */
    private void readable(Color[] range) {
        var theme = ThemeManager.get().theme();
        contrast = range == null ? null : DropdownContrast.resolve(theme, range[0], range[1]);
        contrastTheme = theme;
        contrastStyle = contrast == null ? "" : contrast.style();
        restyle();
    }
    /** Test hook: the readable colours applied to the open list (null until it has been measured). */
    DropdownContrast.Result contrast() { return contrast; }
    /** Quarter-size box average, then the same separable Gaussian blur (edges clamped) as the panels' backdrop. */
    static Image blurred(Image shot) {
        int sw = (int) shot.getWidth(), sh = (int) shot.getHeight();
        int w = Math.max(2, (int) Math.ceil(sw * FROST_SCALE)), h = Math.max(2, (int) Math.ceil(sh * FROST_SCALE));
        int[] argb = new int[sw * sh];
        shot.getPixelReader().getPixels(0, 0, sw, sh, PixelFormat.getIntArgbInstance(), argb, 0, sw);
        float[] rgb = new float[w * h * 3]; int[] count = new int[w * h];
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int p = argb[y * sw + x], cell = Math.min(h - 1, (int) (y * FROST_SCALE)) * w + Math.min(w - 1, (int) (x * FROST_SCALE));
            for (int c = 0; c < 3; c++) rgb[cell * 3 + c] += ((p >> (16 - 8 * c)) & 0xFF) / 255f;
            count[cell]++;
        }
        for (int i = 0; i < count.length; i++) if (count[i] > 0) for (int c = 0; c < 3; c++) rgb[i * 3 + c] /= count[i];
        float[] soft = WaveRenderer.blur(rgb, w, h, FROST_SIGMA);
        var image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), WaveRenderer.pack(soft, w, h), 0, w);
        return image;
    }
    /** Test hook: whether the frosted backdrop is currently shown behind the open list. */
    boolean frosted() { return frost.isVisible() && frost.getImage() != null && frost.getParent() != null; }

    @Override public void dispose() {
        combo.removeEventHandler(ComboBoxBase.ON_SHOWING, showing);
        combo.removeEventHandler(ComboBoxBase.ON_SHOWN, shown);
        if (frost.getParent() instanceof Pane host) host.getChildren().remove(frost);
        super.dispose();
    }
}
