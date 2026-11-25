package com.photopilot.model;

import java.util.Set;

/**
 * The explicit, deliberately small table of camera mount systems that the inventory capability report recognises in
 * recorded mount labels ({@code service.MountNormalization}). A label names one of these systems only when its text
 * establishes it: owner-qualified ("Sony E"), a self-describing name ("Micro Four Thirds"), or a bare designation ("E")
 * recorded on equipment whose own brand owns the system. Designations whose fit depends on the direction of mounting
 * (EF-S, RF-S) and ambiguous names (Sony A / Alpha, M42) are intentionally absent, so they are never merged with
 * another label. The table is reference data for the read-only report only; F07 matching keeps its exact-label rule.
 */
public enum MountSystem {
    SONY_E("Sony E", Set.of("sony"), Set.of("e", "fe"), Set.of()),
    CANON_RF("Canon RF", Set.of("canon"), Set.of("rf"), Set.of()),
    CANON_EF("Canon EF", Set.of("canon"), Set.of("ef"), Set.of()),
    CANON_EF_M("Canon EF-M", Set.of("canon"), Set.of("ef m"), Set.of()),
    NIKON_Z("Nikon Z", Set.of("nikon"), Set.of("z"), Set.of()),
    NIKON_F("Nikon F", Set.of("nikon"), Set.of("f"), Set.of()),
    FUJIFILM_X("Fujifilm X", Set.of("fujifilm", "fuji"), Set.of("x"), Set.of()),
    FUJIFILM_G("Fujifilm G", Set.of("fujifilm", "fuji"), Set.of("g"), Set.of()),
    L_MOUNT("L-Mount", Set.of("leica", "panasonic", "sigma"), Set.of("l"), Set.of()),
    LEICA_M("Leica M", Set.of("leica"), Set.of("m"), Set.of()),
    MICRO_FOUR_THIRDS("Micro Four Thirds", Set.of("olympus", "om system", "om digital solutions", "panasonic"),
        Set.of("micro four thirds", "micro 4/3", "m4/3", "m43", "mft"), Set.of("micro four thirds", "micro 4/3", "m4/3", "m43", "mft")),
    PENTAX_K("Pentax K", Set.of("pentax", "ricoh"), Set.of("k"), Set.of());

    private final String displayName;
    private final Set<String> owners, designations, selfDescribing;

    MountSystem(String displayName, Set<String> owners, Set<String> designations, Set<String> selfDescribing) {
        this.displayName = displayName; this.owners = owners; this.designations = designations; this.selfDescribing = selfDescribing;
    }

    /** Proper name of the system (not translated). */
    public String displayName() { return displayName; }
    /** Lower-case brand names that own the system and may therefore record its bare designation. */
    public Set<String> owners() { return owners; }
    /** Lower-case normalized designations ("e", "ef m"). */
    public Set<String> designations() { return designations; }
    /** Designations that identify the system on their own, whatever the recording brand. */
    public Set<String> selfDescribing() { return selfDescribing; }
}
