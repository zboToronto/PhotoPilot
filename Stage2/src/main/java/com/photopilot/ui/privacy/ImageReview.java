package com.photopilot.ui.privacy;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.BoundedImages;

/**
 * F04 local privacy review of one selected image: the decoded pixels stay on this computer while the user chooses a
 * crop and redaction regions (in original-image pixel coordinates). Never persisted and never sent anywhere;
 * {@link ImagePrivacyBoundary#sanitize} produces the separate copy that may later be approved.
 *
 * <p>Only a bounded working image is held: the original when its longest side is at most
 * {@link ImagePrivacyBoundary#WORKING_MAX_EDGE}, otherwise every {@link #step()}-th pixel of every {@code step}-th row
 * (working pixel (i, j) is original pixel (i·step, j·step)). {@link #width()}, {@link #height()} and all regions stay in
 * original pixels, so the GUI overlay and CLI coordinates do not change with subsampling.
 */
public final class ImageReview {
    /** What the user says the image shows; purchase screenshots get an explicit personal-data warning. */
    public enum Kind { PHOTO, LABEL, SCREENSHOT }
    /** Regions smaller than this are treated as accidental clicks. */
    public static final int MIN_REGION = 4;

    /** Identifies the original file so a crop decoded again from it in {@code sanitize} is from the same content. */
    record Fingerprint(long size, FileTime modified, byte[] sha256) {
        Fingerprint { Objects.requireNonNull(modified); sha256 = sha256.clone(); }
        @Override public byte[] sha256() { return sha256.clone(); }
    }

    private final Path source;
    private final BoundedImages.Header header;
    private final Kind kind;
    private final int width, height, step;
    private final Fingerprint fingerprint;
    private BufferedImage image;
    private Rectangle crop;
    private final List<Rectangle> redactions = new ArrayList<>();
    private Path copy;

    ImageReview(Path source, BoundedImages.Header header, Kind kind, int step, BufferedImage working, Fingerprint fingerprint) {
        this.source = Objects.requireNonNull(source);
        this.header = Objects.requireNonNull(header);
        this.kind = Objects.requireNonNull(kind);
        this.image = Objects.requireNonNull(working);
        if (step < 1 || (step > 1 && fingerprint == null)) throw new IllegalArgumentException("A subsampled review needs its source fingerprint");
        this.step = step;
        this.fingerprint = fingerprint;
        this.width = header.width();
        this.height = header.height();
    }

    public String fileName() { return source.getFileName() == null ? source.toString() : source.getFileName().toString(); }
    /** "PNG" or "JPEG", detected from the content. */
    public String format() { return header.format().name(); }
    public Kind kind() { return kind; }
    /** Original width in pixels; regions use these coordinates. */
    public int width() { return width; }
    /** Original height in pixels; regions use these coordinates. */
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

    /** Downscaled local preview of the working pixels (for display only; never larger than the working image). */
    public synchronized Optional<BufferedImage> preview(int maxEdge) {
        return image == null ? Optional.empty() : Optional.of(scaled(image, maxEdge));
    }

    /** The working image (at most WORKING_MAX_EDGE per side). */
    synchronized BufferedImage pixels() {
        if (image == null) throw new IllegalStateException("released");
        return image;
    }
    /** Subsampling of {@link #pixels()}: 1 means the working image is the original at full resolution. */
    int step() { return step; }
    Path file() { return source; }
    BoundedImages.Header header() { return header; }
    /** Present when {@link #step()} &gt; 1 (the only case in which the file is read again). */
    Optional<Fingerprint> fingerprint() { return Optional.ofNullable(fingerprint); }
    synchronized Optional<Path> copy() { return Optional.ofNullable(copy); }
    synchronized void copy(Path value) { copy = value; }
    /** Drops the decoded pixels; the user's original file is never touched. */
    synchronized void release() { image = null; crop = null; redactions.clear(); }

    private Rectangle clip(int x, int y, int w, int h) {
        var region = new Rectangle(x, y, w, h).intersection(new Rectangle(0, 0, width, height));
        return region.width < MIN_REGION || region.height < MIN_REGION ? new Rectangle() : region;
    }

    /** Copy whose longest side is at most {@code maxEdge}; smaller images are copied unchanged (never upscaled). */
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
