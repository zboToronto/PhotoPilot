package com.photopilot.service;

import com.photopilot.model.GearCapabilityAnalysis.*;
import com.photopilot.model.MountSystem;
import com.photopilot.validation.SpecificationValues;
import java.text.Normalizer;
import java.util.*;

/**
 * Conservative, deterministic reading of recorded mount labels for the inventory capability report only. Stored values
 * are never rewritten, and the F07 compatibility validator does not use this class (it keeps its exact-label rule), so
 * session matching is unaffected.
 *
 * <p>Rules, in order, for one camera label and one lens label:
 * <ol>
 * <li>a blank or placeholder label (SpecificationValues) is UNKNOWN;</li>
 * <li>identical text after Unicode, case and whitespace normalization is SAME_LABEL;</li>
 * <li>labels that differ only in hyphens or a trailing "mount" / "卡口" ("E", "E-mount") are EQUIVALENT_LABELS;</li>
 * <li>labels that both establish a {@link MountSystem} are EQUIVALENT_LABELS (same system) or DIFFERENT_SYSTEMS;</li>
 * <li>a known system against its bare designation recorded without establishing context is POSSIBLE_ALIAS;</li>
 * <li>anything else is DIFFERENT_LABELS.</li>
 * </ol>
 * A label establishes a system when it is owner-qualified ("Sony E"), self-describing ("Micro Four Thirds"), or a bare
 * designation ("E") on equipment whose own brand owns the system. A bare "X" on a third-party item therefore never
 * becomes Fujifilm X. Equal mount labels never establish image-circle, electronic or autofocus compatibility.
 */
public final class MountNormalization {
    private MountNormalization() { }

    public static MountReading read(String recorded, String brand) {
        String text = recorded == null ? "" : recorded;
        if (SpecificationValues.unknown(text)) return new MountReading(text, false, "", Optional.empty());
        String key = key(text);
        return new MountReading(text, true, key, system(key, brand));
    }

    public static MountComparison compare(MountReading camera, MountReading lens) {
        return new MountComparison(relation(camera, lens), camera, lens);
    }

    static Relation relation(MountReading camera, MountReading lens) {
        if (!camera.known() || !lens.known()) return Relation.UNKNOWN;
        if (strict(camera.recorded()).equals(strict(lens.recorded()))) return Relation.SAME_LABEL;
        if (camera.key().equals(lens.key())) return Relation.EQUIVALENT_LABELS;
        if (camera.system().isPresent() && lens.system().isPresent())
            return camera.system().equals(lens.system()) ? Relation.EQUIVALENT_LABELS : Relation.DIFFERENT_SYSTEMS;
        if (camera.system().isPresent() || lens.system().isPresent()) {
            MountSystem known = camera.system().or(lens::system).orElseThrow();
            MountReading other = camera.system().isPresent() ? lens : camera;
            return known.designations().contains(other.key()) ? Relation.POSSIBLE_ALIAS : Relation.DIFFERENT_LABELS;
        }
        return Relation.DIFFERENT_LABELS;
    }

    /** Unicode compatibility forms, case and runs of whitespace only. */
    public static String strict(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** {@link #strict} plus hyphen/underscore runs as spaces and a trailing "mount" or "卡口" removed. */
    public static String key(String value) {
        String text = strict(value).replaceAll("[-_‐‑‒–—]+", " ").replaceAll("\\s+", " ").strip();
        if (text.endsWith("卡口")) text = text.substring(0, text.length() - 2).strip();
        if (text.endsWith(" mount")) text = text.substring(0, text.length() - " mount".length()).strip();
        return text;
    }

    private static Optional<MountSystem> system(String key, String brand) {
        String owner = strict(brand);
        for (MountSystem system : MountSystem.values())
            for (String designation : system.designations()) {
                if (key.equals(designation) && (system.selfDescribing().contains(designation) || owns(system, owner))) return Optional.of(system);
                for (String name : system.owners()) if (key.equals(name + " " + designation)) return Optional.of(system);
            }
        return Optional.empty();
    }

    private static boolean owns(MountSystem system, String brand) {
        return system.owners().stream().anyMatch(name -> brand.equals(name) || brand.startsWith(name + " "));
    }
}
