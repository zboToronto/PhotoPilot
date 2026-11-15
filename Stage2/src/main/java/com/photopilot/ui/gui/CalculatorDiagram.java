package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * Base of the calculator visualizations: a resizable native canvas that redraws at most once per frame, only when its
 * model, size, theme or font changed (nothing runs while idle). Colours are the current theme's tokens and text uses the
 * interface font resolved by the stylesheet, so Light/Dark, the theme colour, Font Size, Bold Text and the Chinese font
 * apply as they do to controls. Subclasses only draw: every number they show was calculated before it reached them.
 */
abstract class CalculatorDiagram extends Region {
    /** The theme colours a diagram uses, taken from the same tokens as photopilot.css. */
    record Palette(Color text, Color muted, Color subtle, Color accent, Color accentText, Color onAccent, Color hairline, Color track, Color surface, Color danger, Color focus) {
        static Palette of(Theme theme) {
            var tokens = theme.tokens();
            return new Palette(Color.web(tokens.get("-pp-text")), Color.web(tokens.get("-pp-text-muted")), Color.web(tokens.get("-pp-text-subtle")),
                Color.web(tokens.get("-pp-accent")), Color.web(tokens.get("-pp-accent-text")), Color.web(tokens.get("-pp-on-accent")), Color.web(tokens.get("-pp-hairline")),
                Color.web(tokens.get("-pp-track")), Color.web(tokens.get("-pp-input")), Color.web(tokens.get("-pp-danger")), Color.web(tokens.get("-pp-focus-ring")));
        }
        Color accent(double opacity) { return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), opacity); }
        Color text(double opacity) { return new Color(text.getRed(), text.getGreen(), text.getBlue(), opacity); }
    }

    protected final LocalizationService localization;
    private final Canvas canvas = new Canvas();
    // Never shown: they only carry the stylesheet's font (family, size, weight) for text drawn on the canvas.
    private final Label regular = FeaturePane.label("", "calc-diagram-text"), strong = FeaturePane.label("", "calc-diagram-strong");
    private final Text measure = new Text();
    // Held strongly here; ThemeManager keeps listeners weakly, so a discarded diagram is simply collected.
    private final Runnable themeChanged = this::redraw;
    private final double preferredHeight;
    private Theme painted;
    private Palette palette;
    private boolean stale;
    private String staleText = "";

    CalculatorDiagram(LocalizationService localization, double preferredHeight) {
        this.localization = localization; this.preferredHeight = preferredHeight;
        getStyleClass().add("calc-diagram");
        canvas.setManaged(false);
        for (Label probe : new Label[] { regular, strong }) { probe.setManaged(false); probe.setVisible(false); probe.fontProperty().addListener(observable -> redraw()); }
        getChildren().addAll(canvas, regular, strong);
        setMinWidth(0);
        ThemeManager.get().listen(themeChanged);
    }

    /** Schedules one repaint with the next layout pass; several model changes within a frame cost one draw. */
    final void redraw() { requestLayout(); }
    /** Marks the drawing as not matching the inputs (they are invalid): it is dimmed and labelled, never left looking current. */
    final void stale(boolean value, String text) {
        if (stale == value && staleText.equals(text)) return;
        stale = value; staleText = text; redraw();
    }
    final boolean stale() { return stale; }

    /** Height for a given width: a diagram that draws two views side by side may stack them when it is narrow. */
    protected double heightFor(double width) { return preferredHeight; }
    @Override public javafx.geometry.Orientation getContentBias() { return javafx.geometry.Orientation.HORIZONTAL; }
    @Override protected double computePrefWidth(double height) { return 420; }
    @Override protected double computeMinWidth(double height) { return 0; }
    @Override protected double computePrefHeight(double width) { return heightFor(width > 0 ? width : getWidth()); }
    @Override protected double computeMinHeight(double width) { return computePrefHeight(width); }
    @Override protected double computeMaxHeight(double width) { return computePrefHeight(width); }

    @Override protected void layoutChildren() {
        double width = Math.floor(getWidth()), height = Math.floor(getHeight());
        if (canvas.getWidth() != width || canvas.getHeight() != height) { canvas.setWidth(width); canvas.setHeight(height); }
        var theme = ThemeManager.get().theme();
        if (palette == null || !theme.equals(painted)) { painted = theme; palette = Palette.of(theme); }
        var g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        if (width < 120 || height < 80) return;
        g.save();
        g.setLineWidth(1); g.setFont(font()); g.setTextBaseline(VPos.BASELINE); g.setTextAlign(TextAlignment.LEFT);
        if (stale) g.setGlobalAlpha(0.28);
        draw(g, width, height);
        g.restore();
        if (stale) {
            // A text banner, not only the dimming: the drawing below is the last valid one, not the current input.
            g.save(); g.setFont(strongFont());
            double textWidth = width(staleText, strongFont()), boxWidth = Math.min(width - 16, textWidth + 28), boxHeight = strongFont().getSize() + 16;
            double x = (width - boxWidth) / 2, y = (height - boxHeight) / 2;
            g.setFill(palette.surface()); g.fillRoundRect(x, y, boxWidth, boxHeight, 12, 12);
            g.setStroke(palette.danger()); g.setLineDashes(5, 4); g.strokeRoundRect(x + .5, y + .5, boxWidth - 1, boxHeight - 1, 12, 12);
            g.setFill(palette.text()); g.setTextAlign(TextAlignment.CENTER); g.setTextBaseline(VPos.CENTER);
            g.fillText(staleText, width / 2, height / 2, boxWidth - 20);
            g.restore();
        }
    }

    /** Draws the current model. The context arrives with the regular font, a 1 px line and baseline-left text. */
    protected abstract void draw(GraphicsContext g, double width, double height);

    protected final Palette palette() { return palette; }
    protected final Font font() { return regular.getFont(); }
    protected final Font strongFont() { return strong.getFont(); }
    protected final double width(String text, Font font) { measure.setFont(font); measure.setText(text); return measure.getLayoutBounds().getWidth(); }
    protected final double lineHeight(Font font) { return Math.ceil(font.getSize() * 1.3); }

    /** Text with an alignment and colour; {@code maxWidth} squeezes a label that would not fit rather than clipping it. */
    protected final void text(GraphicsContext g, String value, double x, double y, TextAlignment align, Color colour, Font font, double maxWidth) {
        g.setFont(font); g.setFill(colour); g.setTextAlign(align);
        if (maxWidth > 0) g.fillText(value, x, y, maxWidth); else g.fillText(value, x, y);
    }
    protected final void text(GraphicsContext g, String value, double x, double y, TextAlignment align, Color colour) { text(g, value, x, y, align, colour, font(), 0); }
    /**
     * Text kept inside [left, right]: centred on x when it fits there, otherwise shifted to the nearer edge, and
     * squeezed when it is wider than the whole span.
     */
    protected final void textWithin(GraphicsContext g, String value, double x, double y, double left, double right, Color colour, Font font) {
        double half = width(value, font) / 2, centre = Math.max(left + half, Math.min(right - half, x));
        if (2 * half > right - left) centre = (left + right) / 2;
        text(g, value, centre, y, TextAlignment.CENTER, colour, font, right - left);
    }
    /** A crisp 1 px line on the pixel grid. */
    protected static void line(GraphicsContext g, double x0, double y0, double x1, double y1) {
        if (x0 == x1) { double x = Math.floor(x0) + .5; g.strokeLine(x, y0, x, y1); }
        else if (y0 == y1) { double y = Math.floor(y0) + .5; g.strokeLine(x0, y, x1, y); }
        else g.strokeLine(x0, y0, x1, y1);
    }
    /** The standing reference figure (see MotionBlurPreview.Figure), feet at (x, groundY), drawn with the current fill. */
    protected static void figure(GraphicsContext g, double x, double groundY, double height) {
        var head = com.photopilot.application.MotionBlurPreview.Figure.HEAD;
        g.fillOval(x + (head[0] - head[2]) * height, groundY - (head[1] + head[2]) * height, 2 * head[2] * height, 2 * head[2] * height);
        for (double[] part : com.photopilot.application.MotionBlurPreview.Figure.PARTS)
            g.fillRoundRect(x + part[0] * height, groundY - (part[1] + part[3]) * height, part[2] * height, part[3] * height, 2 * part[4] * height, 2 * part[4] * height);
    }
    /** A small camera seen from the side, its lens front at (lensX, y). */
    protected static void camera(GraphicsContext g, double lensX, double y) {
        g.fillRoundRect(lensX - 30, y - 11, 20, 22, 5, 5);     // body
        g.fillRect(lensX - 25, y - 15, 8, 5);                  // viewfinder
        g.fillRoundRect(lensX - 11, y - 7, 11, 14, 3, 3);      // lens
    }
}
