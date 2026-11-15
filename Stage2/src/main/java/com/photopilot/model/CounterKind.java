package com.photopilot.model;

/**
 * What a camera counter field counts, only as far as ExifTool documents it (TagNames notes and the Sony.pm / Nikon.pm
 * definitions of the bundled release). Counters of different kinds are never interchangeable.
 */
public enum CounterKind {
    /** Sony Tag9050a/b/c ShutterCount: "total number of image exposures made by the camera". */
    TOTAL_EXPOSURES,
    /** Sony Tag9050d ShutterCount: "total number of mechanical shutter actuations". */
    MECHANICAL_ACTUATIONS,
    /** Nikon MakerNotes 0x00a7 ShutterCount: shots so far, "includes both mechanical and electronic shutter activations". */
    SHOTS_INCLUDING_ELECTRONIC,
    /** Nikon MakerNotes 0x0037 MechanicalShutterCount: named by ExifTool, without further notes. */
    MECHANICAL_BY_NAME,
    /** A field named ShutterCount whose meaning ExifTool does not describe further (older Sony DSLR/SLT tables, Nikon ShotInfo). */
    SHUTTER_COUNT,
    /** ImageCount: images recorded, never treated as shutter actuations. */
    IMAGE_COUNT,
    /** ShutterCount2 / ShutterCount3: related Sony fields without documented meaning, used only as a cross-check. */
    RELATED
}
