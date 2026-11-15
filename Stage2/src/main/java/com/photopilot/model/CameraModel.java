package com.photopilot.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One Sony or Nikon camera from the ExifTool-tested camera list, with PhotoPilot's series grouping and what is known
 * about reading its shutter count. {@code id} is the name exactly as ExifTool lists it (the EXIF Model without the make,
 * for example ILCE-7RM5 or Z 6_2); {@code displayName} is the common product name derived from it.
 */
public record CameraModel(Brand brand, String id, String displayName, Series series, List<String> aliases, Support support) {
    public CameraModel {
        Objects.requireNonNull(brand); Objects.requireNonNull(id); Objects.requireNonNull(displayName);
        Objects.requireNonNull(series); aliases = List.copyOf(aliases); Objects.requireNonNull(support);
    }

    public enum Brand {
        SONY("Sony"), NIKON("Nikon");
        private final String label;
        Brand(String label) { this.label = label; }
        public String label() { return label; }
        /** The brand of an EXIF Make value ("SONY", "NIKON CORPORATION", "NIKON"); anything else is not supported. */
        public static Optional<Brand> fromMake(String make) {
            String value = make == null ? "" : make.strip().toUpperCase(Locale.ROOT);
            if (value.startsWith("SONY")) return Optional.of(SONY);
            if (value.startsWith("NIKON")) return Optional.of(NIKON);
            return Optional.empty();
        }
    }

    /** Product families; series belong to exactly one. */
    public enum Family {
        SONY_ALPHA_E_MOUNT, SONY_ALPHA_A_MOUNT, SONY_CINEMA_LINE, SONY_ZV, SONY_CYBER_SHOT, SONY_MAVICA, SONY_OTHER,
        NIKON_Z, NIKON_DSLR, NIKON_1, NIKON_COOLPIX, NIKON_KEYMISSION, NIKON_OTHER
    }

    /** Series in display order within each brand. */
    public enum Series {
        SONY_ALPHA_1(Brand.SONY, Family.SONY_ALPHA_E_MOUNT), SONY_ALPHA_9(Brand.SONY, Family.SONY_ALPHA_E_MOUNT),
        SONY_ALPHA_7(Brand.SONY, Family.SONY_ALPHA_E_MOUNT), SONY_ALPHA_7R(Brand.SONY, Family.SONY_ALPHA_E_MOUNT),
        SONY_ALPHA_7S(Brand.SONY, Family.SONY_ALPHA_E_MOUNT), SONY_ALPHA_7C(Brand.SONY, Family.SONY_ALPHA_E_MOUNT),
        SONY_ALPHA_6000(Brand.SONY, Family.SONY_ALPHA_E_MOUNT), SONY_ALPHA_5000_3000(Brand.SONY, Family.SONY_ALPHA_E_MOUNT),
        SONY_NEX(Brand.SONY, Family.SONY_ALPHA_E_MOUNT), SONY_QX(Brand.SONY, Family.SONY_ALPHA_E_MOUNT),
        SONY_A_MOUNT_SLT_ILCA(Brand.SONY, Family.SONY_ALPHA_A_MOUNT), SONY_A_MOUNT_DSLR(Brand.SONY, Family.SONY_ALPHA_A_MOUNT),
        SONY_CINEMA_LINE(Brand.SONY, Family.SONY_CINEMA_LINE), SONY_ZV(Brand.SONY, Family.SONY_ZV),
        SONY_RX(Brand.SONY, Family.SONY_CYBER_SHOT), SONY_CYBER_SHOT(Brand.SONY, Family.SONY_CYBER_SHOT),
        SONY_MAVICA(Brand.SONY, Family.SONY_MAVICA), SONY_OTHER(Brand.SONY, Family.SONY_OTHER),
        NIKON_Z_FULL_FRAME(Brand.NIKON, Family.NIKON_Z), NIKON_Z_DX(Brand.NIKON, Family.NIKON_Z),
        NIKON_D_SINGLE_DIGIT(Brand.NIKON, Family.NIKON_DSLR), NIKON_D_TWO_DIGIT(Brand.NIKON, Family.NIKON_DSLR),
        NIKON_D_THREE_DIGIT(Brand.NIKON, Family.NIKON_DSLR), NIKON_D_FOUR_DIGIT(Brand.NIKON, Family.NIKON_DSLR),
        NIKON_DF(Brand.NIKON, Family.NIKON_DSLR), NIKON_1(Brand.NIKON, Family.NIKON_1),
        NIKON_COOLPIX(Brand.NIKON, Family.NIKON_COOLPIX), NIKON_KEYMISSION(Brand.NIKON, Family.NIKON_KEYMISSION),
        NIKON_OTHER(Brand.NIKON, Family.NIKON_OTHER);
        private final Brand brand; private final Family family;
        Series(Brand brand, Family family) { this.brand = brand; this.family = family; }
        public Brand brand() { return brand; }
        public Family family() { return family; }
    }

    /**
     * The four distinctions the checker keeps apart: every catalogued model is documented by ExifTool; a counter may
     * additionally be documented for it, verified with a real photo, or unavailable / not established.
     */
    public enum Level {
        /** Extraction checked by PhotoPilot with a real photograph against an independent tool. */
        VERIFIED,
        /** ExifTool's MakerNotes definitions name this model (or a table it uses) for a shutter counter. */
        DOCUMENTED,
        /** No model-specific evidence either way: a counter may be read if the camera writes one. */
        UNVERIFIED,
        /** ExifTool defines no shutter counter for this model. */
        UNAVAILABLE
    }

    /**
     * @param field the documented source (for example "Sony Tag9050c ShutterCount"), empty when none is known
     * @param kind what that field counts
     * @param condition optional message key for a documented restriction (for example "only photos taken with the mechanical shutter")
     * @param evidence message key explaining where the level comes from
     */
    public record Support(Level level, Optional<String> field, Optional<CounterKind> kind, Optional<String> condition, String evidence) {
        public Support {
            Objects.requireNonNull(level); Objects.requireNonNull(field); Objects.requireNonNull(kind);
            Objects.requireNonNull(condition); Objects.requireNonNull(evidence);
        }
        public Support verified() { return new Support(Level.VERIFIED, field, kind, condition, "shutter.evidence.verified"); }
    }

    /** "α7R V (ILCE-7RM5)", or just the name when it equals the ID. */
    public String label() { return displayName.equals(id) ? id : displayName + " (" + id + ")"; }
}
