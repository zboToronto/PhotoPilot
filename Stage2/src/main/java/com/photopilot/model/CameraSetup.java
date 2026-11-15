package com.photopilot.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Generic camera parameters shared by the optical calculators (depth of field, hyperfocal distance, field of view and
 * motion blur). No camera model is ever required: the values are the actual sensor dimensions in millimetres, the
 * optional pixel dimensions of the image recorded from that sensor area, and how the circle of confusion is chosen.
 * A format preset only fills in typical dimensions; the stored numbers are what the calculations use.
 */
public record CameraSetup(String formatId, double sensorWidthMm, double sensorHeightMm, OptionalInt imageWidthPx, OptionalInt imageHeightPx,
                          CocPolicy cocPolicy, double customCocMm) {
    /** {@link #formatId} of dimensions that were entered by hand or copied from an inventory camera. */
    public static final String CUSTOM = "custom";

    /**
     * How the circle of confusion (CoC) is chosen. Acceptable sharpness is a convention about print size, viewing
     * distance and eyesight, not a property of the sensor, so the policy is always shown and can be overridden.
     * The default divides the sensor diagonal by 1500 (about 0.029 mm for 36 x 24 mm); 1730 is a stricter convention.
     */
    public enum CocPolicy {
        DIAGONAL_1500(1500), DIAGONAL_1730(1730), CUSTOM(0);
        private final double divisor;
        CocPolicy(double divisor) { this.divisor = divisor; }
        /** The diagonal divisor, or 0 for an explicitly entered value. */
        public double divisor() { return divisor; }
    }

    /** A common sensor format with typical dimensions; sensors sold under one format name differ slightly between cameras. */
    public record Format(String id, double widthMm, double heightMm) { }

    public static final List<Format> FORMATS = List.of(
        new Format("fullFrame", 36.0, 24.0),
        new Format("apsc", 23.5, 15.6),
        new Format("apscCanon", 22.3, 14.9),
        new Format("microFourThirds", 17.3, 13.0),
        new Format("oneInch", 13.2, 8.8),
        new Format("mediumFormat", 43.8, 32.9));
    public static final double DEFAULT_CUSTOM_COC_MM = 0.03;
    public static final CameraSetup DEFAULT = of(FORMATS.getFirst());

    public CameraSetup {
        Objects.requireNonNull(formatId, "formatId"); Objects.requireNonNull(cocPolicy, "cocPolicy");
        Objects.requireNonNull(imageWidthPx, "imageWidthPx"); Objects.requireNonNull(imageHeightPx, "imageHeightPx");
        positive(sensorWidthMm, "sensorWidthMm"); positive(sensorHeightMm, "sensorHeightMm"); positive(customCocMm, "customCocMm");
        if (imageWidthPx.isPresent() && imageWidthPx.getAsInt() <= 0 || imageHeightPx.isPresent() && imageHeightPx.getAsInt() <= 0)
            throw new IllegalArgumentException("Image pixel dimensions must be positive.");
    }
    private static void positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive and finite.");
    }

    public static CameraSetup of(Format format) {
        return new CameraSetup(format.id(), format.widthMm(), format.heightMm(), OptionalInt.empty(), OptionalInt.empty(), CocPolicy.DIAGONAL_1500, DEFAULT_CUSTOM_COC_MM);
    }
    /** The preset these dimensions came from, if they still are that preset's. */
    public Optional<Format> format() {
        return FORMATS.stream().filter(f -> f.id().equals(formatId) && f.widthMm() == sensorWidthMm && f.heightMm() == sensorHeightMm).findFirst();
    }
    public double diagonalMm() { return Math.hypot(sensorWidthMm, sensorHeightMm); }
    /** The circle of confusion in millimetres under the chosen policy. */
    public double cocMm() { return cocPolicy == CocPolicy.CUSTOM ? customCocMm : diagonalMm() / cocPolicy.divisor(); }

    /** A preset's dimensions. Pixel dimensions describe one particular camera, so they are cleared with the sensor. */
    public CameraSetup withFormat(Format format) {
        return new CameraSetup(format.id(), format.widthMm(), format.heightMm(), OptionalInt.empty(), OptionalInt.empty(), cocPolicy, customCocMm);
    }
    public CameraSetup withSensor(double widthMm, double heightMm) {
        return new CameraSetup(CUSTOM, widthMm, heightMm, imageWidthPx, imageHeightPx, cocPolicy, customCocMm);
    }
    public CameraSetup withImage(OptionalInt widthPx, OptionalInt heightPx) {
        return new CameraSetup(formatId, sensorWidthMm, sensorHeightMm, widthPx, heightPx, cocPolicy, customCocMm);
    }
    public CameraSetup withCoc(CocPolicy policy, double customMm) {
        return new CameraSetup(formatId, sensorWidthMm, sensorHeightMm, imageWidthPx, imageHeightPx, policy, customMm);
    }
}
