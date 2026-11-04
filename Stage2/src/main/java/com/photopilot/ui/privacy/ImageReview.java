package com.photopilot.ui.privacy;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * F04 local privacy review of one selected image: the decoded pixels stay on this computer while the user chooses a
 * crop and redaction regions (in original-image pixel coordinates). Never persisted and never sent anywhere;
 * {@link ImagePrivacyBoundary#sanitize} produces the separate copy that may later be approved.
 */
public final class ImageReview {
    /** What the user says the image shows; purchase screenshots get an explicit personal-data warning. */
    public enum Kind { PHOTO, LABEL, SCREENSHOT }
    /** Regions smaller than this are treated as accidental clicks. */
    public static final int MIN_REGION = 4;

    private final Path source;
    private final String format;
    private final Kind kind;
    private final int width, height;
    private BufferedImage image;
    private Rectangle crop;
    private final List<Rectangle> redactions = new ArrayList<>();
    private Path copy;

    ImageReview(Path source, String format, Kind kind, BufferedImage image) {
        this.source = Objects.requireNonNull(source);
        this.format = Objects.requireNonNull(format);
        this.kind = Objects.requireNonNull(kind);
        this.image = Objects.requireNonNull(image);
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public String fileName() { return source.getFileName() == null ? source.toString() : source.getFileName().toString(); }
    public String format() { return format; }
    public Kind kind() { return kind; }
    public int width() { return width; }
    public int height() { return height; }
    public synchronized boolean released() { return image == null; }

    /** Sets the crop rectangle (clipped to the image); false when the region is empty or too small. */
    public synchronized boolean crop(int x, int y, int w, int h) {
        var region = clip(x, y, w, h);
        if (region.isEmpty()) return false;
        crop = region; return true;
    }
    /** Adds a region whose pixels are replaced with solid black in the sanitized copy. */
    public synchronized boolean redact(int x, int y, int w, int h) {
        var region = clip(x, y, w, h);
        if (region.isEmpty()) return false;
        redactions.add(region); return true;
    }
    /** Removes the most recent redaction, otherwise the crop; false when there was nothing to undo. */
    public synchronized boolean undo() {
        if (!redactions.isEmpty()) { redactions.removeLast(); return true; }
        if (crop != null) { crop = null; return true; }
        return false;
    }
    public synchronized void clearRegions() { crop = null; redactions.clear(); }
    public synchronized Optional<Rectangle> cropRegion() { return Optional.ofNullable(crop).map(Rectangle::new); }
    public synchronized List<Rectangle> redactions() { return redactions.stream().map(Rectangle::new).toList(); }

    /** Downscaled local preview of the original pixels (for display only). */
    public synchronized Optional<BufferedImage> preview(int maxEdge) {
        return image == null ? Optional.empty() : Optional.of(scaled(image, maxEdge));
    }

    synchronized BufferedImage pixels() {
        if (image == null) throw new IllegalStateException("released");
        return image;
    }
    synchronized Optional<Path> copy() { return Optional.ofNullable(copy); }
    synchronized void copy(Path value) { copy = value; }
    /** Drops the decoded pixels; the user's original file is never touched. */
    synchronized void release() { image = null; crop = null; redactions.clear(); }

    private Rectangle clip(int x, int y, int w, int h) {
        var region = new Rectangle(x, y, w, h).intersection(new Rectangle(0, 0, width, height));
        return region.width < MIN_REGION || region.height < MIN_REGION ? new Rectangle() : region;
    }

    static BufferedImage scaled(BufferedImage source, int maxEdge) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxEdge) return copyOf(source, source.getWidth(), source.getHeight());
        double factor = (double) maxEdge / longest;
        return copyOf(source, Math.max(1, (int) Math.round(source.getWidth() * factor)), Math.max(1, (int) Math.round(source.getHeight() * factor)));
    }
    private static BufferedImage copyOf(BufferedImage source, int w, int h) {
        var target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, w, h, null);
        } finally { graphics.dispose(); }
        return target;
    }
}
