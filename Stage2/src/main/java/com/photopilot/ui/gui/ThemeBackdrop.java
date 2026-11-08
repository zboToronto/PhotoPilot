package com.photopilot.ui.gui;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.image.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Decorative flowing-wave background: the procedural field of {@link WaveRenderer} (Bézier wave surfaces, light
 * fields and gradients computed from the current {@link Theme} lighting; no image asset is involved), plus a backdrop
 * blur, a Gaussian-blurred copy of the same field shown only behind translucent UI surfaces. Both are rendered once
 * per window size, display scale or theme at reduced size and scaled smoothly (at half that size while the custom hue
 * is being dragged, then in full). The first paint is immediate; every later one is computed on a background thread
 * and swapped in when ready, so resizing or changing the theme never blocks the interface. Purely decorative (mouse
 * transparent, never focusable) and behind all content, so photographs drawn above it are never blurred, tinted or faded.
 */
final class ThemeBackdrop extends Region {
    static final Set<String> WORKSPACE_SURFACES = Set.of("app-header", "navigation-scroll", "surface-panel", "status-tile",
        "module-card", "feedback-box", "glass-panel");
    static final Set<String> CHOOSER_SURFACES = Set.of("profile-tile");
    /**
     * The field's narrowest feature (a crest's soft rim) spans several of its pixels at half the window's physical size,
     * so that resolution is visually lossless when scaled up (at most 1600 px wide); the blur copy uses a quarter.
     */
    private static final double FIELD_SCALE = .5, FIELD_MAX_WIDTH = 1600, FROST_SCALE = .25, FROST_SIGMA = 10;
    /** One background thread renders every backdrop; a request that is superseded before it starts is skipped. */
    private static final ExecutorService RENDERER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "photopilot-backdrop"); thread.setDaemon(true); return thread;
    });
    private final AtomicLong requested = new AtomicLong();
    private long shown;
    private Frame shownFrame;
    private final Parent host;
    private final Set<String> surfaces;
    private final ImageView waves = new ImageView(), frost = new ImageView();
    private final Group mask = new Group();
    private final PauseTransition resized = new PauseTransition(Duration.millis(140));
    private final Runnable themeChanged = this::themeChanged, pulse = this::trackSurfaces;
    private Theme painted = ThemeManager.get().theme();
    private double paintedWidth = -1, paintedHeight = -1;
    private boolean renderQueued;
    private long lastRelight;
    private static final long RAPID_NANOS = 200_000_000L;
    private List<double[]> regions = List.of();
    private Scene scene;
    private Window window;
    private Parent sized;
    private final javafx.beans.value.ChangeListener<Bounds> parentBounds = (property, old, bounds) -> fit();
    private final javafx.beans.value.ChangeListener<Window> windowChanged = (property, old, value) -> watchDisplay(value);
    private final javafx.beans.value.ChangeListener<Number> displayChanged = (property, old, value) -> { if (waves.getImage() != null) resized.playFromStart(); };

    ThemeBackdrop(Parent host, Set<String> surfaces) {
        this.host = host; this.surfaces = surfaces;
        setId("theme-backdrop"); setManaged(false); setMouseTransparent(true); setFocusTraversable(false);
        waves.setSmooth(true); frost.setSmooth(true); frost.setClip(mask); frost.setVisible(false);
        getChildren().addAll(waves, frost);
        resized.setOnFinished(event -> render());
        ThemeManager.get().track();
        ThemeManager.get().listen(themeChanged);
        sceneProperty().addListener((property, old, value) -> attach(value));
        parentProperty().addListener((property, old, value) -> follow(value));
    }

    /** Sizes itself to its parent, so hosts only add it as their first child. */
    private void follow(Parent parent) {
        if (sized != null) sized.layoutBoundsProperty().removeListener(parentBounds);
        sized = parent;
        if (sized != null) { sized.layoutBoundsProperty().addListener(parentBounds); fit(); }
    }
    private void fit() { if (sized != null) resizeRelocate(0, 0, sized.getLayoutBounds().getWidth(), sized.getLayoutBounds().getHeight()); }

    @Override protected void layoutChildren() {
        double width = getWidth(), height = getHeight();
        for (ImageView view : List.of(waves, frost)) { view.setFitWidth(width); view.setFitHeight(height); }
        if (width == paintedWidth && height == paintedHeight) return;
        paintedWidth = width; paintedHeight = height;
        // The first paint is immediate, so no frame shows an empty background; resizes stretch the soft field until settled.
        if (waves.getImage() == null) render(); else resized.playFromStart();
    }
    private void themeChanged() {
        var next = ThemeManager.get().theme();
        // Any change of the lighting (preset, custom hue or mode) repaints; opacity alone only changes surface tokens.
        boolean relight = !next.lighting().equals(painted.lighting());
        painted = next;
        if (relight && !renderQueued) {
            // Coalesced: while the hue slider is dragged, several value changes between two frames give one render.
            renderQueued = true;
            Platform.runLater(() -> {
                renderQueued = false;
                long now = System.nanoTime();
                boolean rapid = now - lastRelight < RAPID_NANOS;
                lastRelight = now;
                // Rapid changes (a hue drag) get a half-resolution draft, about a quarter of the work; the soft field
                // looks nearly the same, and the full render follows as soon as the changes pause.
                if (rapid) { render(.5); resized.playFromStart(); } else render();
            });
        }
    }
    private void render() { render(1); }
    private void render(double quality) {
        if (paintedWidth < 2 || paintedHeight < 2) return;
        var light = painted.lighting();
        // Sized to the display's pixels: a 150% display gets a field 1.5 times as detailed as a 100% one.
        double display = scene == null || scene.getWindow() == null ? 1 : Math.max(1, scene.getWindow().getRenderScaleX());
        double scale = Math.min(FIELD_SCALE * display, FIELD_MAX_WIDTH / paintedWidth) * quality, frostScale = FROST_SCALE * quality;
        int width = pixels(paintedWidth * scale), height = pixels(paintedHeight * scale);
        int frostWidth = pixels(paintedWidth * frostScale), frostHeight = pixels(paintedHeight * frostScale);
        // The blur radius is in image pixels, so it shrinks with the draft to keep the same look on screen.
        double sigma = FROST_SIGMA * quality;
        long id = requested.incrementAndGet();
        if (waves.getImage() == null) {   // the first paint is immediate, so no frame shows an empty background
            show(id, Frame.render(light, quality, width, height, frostWidth, frostHeight, sigma));
            return;
        }
        RENDERER.execute(() -> {
            if (id != requested.get()) return;   // superseded while queued (for example during a hue drag)
            var frame = Frame.render(light, quality, width, height, frostWidth, frostHeight, sigma);
            Platform.runLater(() -> show(id, frame));
        });
    }
    /** Shows a finished render unless a newer one was requested meanwhile. */
    private void show(long id, Frame frame) {
        if (id != requested.get()) return;
        waves.setImage(frame.waves()); frost.setImage(frame.frost()); shown = id; shownFrame = frame;
    }
    /** The rendered field and its backdrop-blur copy (a true separable Gaussian blur of the field). */
    private record Frame(Theme.Lighting light, double quality, int width, int height, int[] wavePixels, int frostWidth, int frostHeight, int[] frostPixels) {
        static Frame render(Theme.Lighting light, double quality, int width, int height, int frostWidth, int frostHeight, double sigma) {
            return new Frame(light, quality, width, height, pixels(light, width, height, 0), frostWidth, frostHeight, pixels(light, frostWidth, frostHeight, sigma));
        }
        Image waves() { return image(wavePixels, width, height); }
        Image frost() { return image(frostPixels, frostWidth, frostHeight); }
    }
    private static int pixels(double size) { return Math.max(2, (int) Math.ceil(size)); }
    static int[] pixels(Theme.Lighting light, int width, int height, double sigma) {
        float[] field = WaveRenderer.field(light, width, height);
        if (sigma > 0) field = WaveRenderer.blur(field, width, height, sigma);
        return WaveRenderer.pack(field, width, height);
    }
    private static Image image(int[] argb, int width, int height) {
        var image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return image;
    }
    private void attach(Scene value) {
        if (scene != null) { scene.removePostLayoutPulseListener(pulse); scene.windowProperty().removeListener(windowChanged); }
        scene = value;
        if (scene != null) { scene.addPostLayoutPulseListener(pulse); scene.windowProperty().addListener(windowChanged); }
        watchDisplay(scene == null ? null : scene.getWindow());
    }
    /** Re-renders at the new pixel density when the window moves to a display with another scale. */
    private void watchDisplay(Window value) {
        if (window != null) window.renderScaleXProperty().removeListener(displayChanged);
        window = value;
        if (window != null) window.renderScaleXProperty().addListener(displayChanged);
    }
    /** After each layout pass: show the blurred copy exactly behind the visible translucent surfaces. */
    private void trackSurfaces() {
        boolean show = painted.opacity() < 99 && frost.getImage() != null;
        if (frost.isVisible() != show) frost.setVisible(show);
        if (!show) return;
        List<double[]> found = new ArrayList<>();
        collect(host, null, found);
        if (same(found, regions)) return;
        regions = found;
        List<Node> shapes = new ArrayList<>(found.size());
        for (double[] region : found) {
            var shape = new Rectangle(region[0], region[1], region[2], region[3]);
            shape.setArcWidth(region[4] * 2); shape.setArcHeight(region[4] * 2);
            if (region.length == 9) {
                var visible = Shape.intersect(shape, new Rectangle(region[5], region[6], region[7], region[8]));
                visible.setFill(Color.BLACK); shapes.add(visible);
            } else shapes.add(shape);
        }
        mask.getChildren().setAll(shapes);
    }
    private void collect(Node node, Bounds limit, List<double[]> found) {
        if (node == this || !node.isVisible() || node.getOpacity() == 0) return;
        if (node.getClip() != null) {
            var clip = node.localToScene(node.getClip().getBoundsInParent());
            limit = limit == null ? clip : intersect(limit, clip);
            if (limit == null) return;
        }
        if (node instanceof Region region && isSurface(region)) { add(region, limit, found); return; }
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) collect(child, limit, found);
    }
    private boolean isSurface(Region region) {
        for (String style : region.getStyleClass()) if (surfaces.contains(style)) return true;
        return false;
    }
    private void add(Region region, Bounds limit, List<double[]> found) {
        var layout = region.getLayoutBounds();
        if (layout.getWidth() <= 0 || layout.getHeight() <= 0) return;
        Bounds onScene = region.localToScene(layout), visible = limit == null ? onScene : intersect(onScene, limit);
        if (visible == null) return;
        Bounds bounds = sceneToLocal(onScene);
        double radius = 0;
        var background = region.getBackground();
        if (background != null && !background.getFills().isEmpty())
            radius = background.getFills().getFirst().getRadii().getTopLeftHorizontalRadius() * bounds.getWidth() / layout.getWidth();
        if (visible.equals(onScene)) found.add(new double[] { bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight(), radius });
        else {
            Bounds shown = sceneToLocal(visible);
            found.add(new double[] { bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight(), radius,
                shown.getMinX(), shown.getMinY(), shown.getWidth(), shown.getHeight() });
        }
    }
    private static Bounds intersect(Bounds a, Bounds b) {
        double minX = Math.max(a.getMinX(), b.getMinX()), minY = Math.max(a.getMinY(), b.getMinY());
        double maxX = Math.min(a.getMaxX(), b.getMaxX()), maxY = Math.min(a.getMaxY(), b.getMaxY());
        return maxX <= minX || maxY <= minY ? null : new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }
    private static boolean same(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            double[] x = a.get(i), y = b.get(i);
            if (x.length != y.length) return false;
            for (int j = 0; j < x.length; j++) if (Math.abs(x[j] - y[j]) > .25) return false;
        }
        return true;
    }
    /** Test hook: number of surface regions currently showing the blurred backdrop. */
    int frostedRegions() { return frost.isVisible() ? regions.size() : 0; }
    boolean frostRendered() { return frost.getImage() != null; }
    /** Test hook: the full-quality render of the current theme and size is on screen and nothing is pending. */
    boolean settled() {
        return shownFrame != null && shown == requested.get() && shownFrame.quality() == 1 && shownFrame.light().equals(painted.lighting())
            && !renderQueued && resized.getStatus() != javafx.animation.Animation.Status.RUNNING;
    }
    /** Test hook: pixel size of the rendered field. */
    double[] fieldSize() { var image = waves.getImage(); return image == null ? new double[2] : new double[] { image.getWidth(), image.getHeight() }; }
    /** Test hook: the wave layer alone (without the blurred copy), as drawn on screen. */
    ImageView wavesView() { return waves; }
}
