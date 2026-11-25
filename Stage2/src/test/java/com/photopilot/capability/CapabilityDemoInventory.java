package com.photopilot.capability;

import com.photopilot.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Test data resembling the demonstration inventory: three bodies (one with incomplete specifications and the mount
 * recorded as "Sony E" while everything else says "E"), an isolated 16 mm prime, zooms that join into continuous
 * 20–400 mm coverage, one variable-aperture zoom and no filters. Values are fixtures only; production code has none.
 */
public final class CapabilityDemoInventory {
    private CapabilityDemoInventory() { }
    public static final UUID A7IV = id("a7iv"), A7V = id("a7v"), A7RV = id("a7rv");
    public static final UUID L2070 = id("20-70"), L2470 = id("24-70"), L35 = id("35"), L55 = id("55"), T50400 = id("50-400"), T70180 = id("70-180"), V16 = id("16");

    public static UUID id(String name) { return UUID.nameUUIDFromBytes(("capability-demo-" + name).getBytes(StandardCharsets.UTF_8)); }

    public static Camera camera(UUID id, String brand, String model, int quantity, String mount, Double width, Double height, Double mp, Double fps, Boolean ibis, Set<String> af) {
        return new Camera(id, brand, model, quantity, Optional.empty(), Set.of(), Map.of(), Optional.empty(), mount,
            Optional.ofNullable(width), Optional.ofNullable(height), Set.of(), Set.of(), Optional.ofNullable(mp), Optional.ofNullable(fps), Optional.ofNullable(ibis), af);
    }
    public static Lens lens(UUID id, String brand, String model, String mount, double min, double max, double aperture, Double longEnd, Boolean af, Boolean ois, Double filter) {
        return new Lens(id, brand, model, 1, Optional.empty(), Set.of(), Map.of(), Optional.empty(), mount, min, max, aperture,
            Optional.ofNullable(filter), Optional.ofNullable(af), Optional.ofNullable(ois), Optional.ofNullable(longEnd));
    }

    public static List<Equipment> items() {
        return List.of(
            camera(A7IV, "Sony", "A7 IV", 1, "E", 35.9, 23.9, 33., 10., true, Set.of("AF-S", "AF-C")),
            camera(A7RV, "Sony", "A7R V", 1, "E", 35.7, 23.8, 61., 10., true, Set.of("AF-S", "AF-C")),
            camera(A7V, "Sony", "A7 V", 1, "Sony E", null, null, null, null, null, Set.of()),
            lens(L2070, "Sony", "FE 20-70mm F4 G", "E", 20, 70, 4, 4., true, false, 72.),
            lens(L2470, "Sony", "FE 24-70mm F2.8 GM II", "E", 24, 70, 2.8, 2.8, true, false, 82.),
            lens(L35, "Sony", "FE 35mm F1.4 GM", "E", 35, 35, 1.4, null, true, false, 67.),
            lens(L55, "Sony", "FE 55mm F1.8 ZA", "E", 55, 55, 1.8, null, true, false, 49.),
            lens(T50400, "Tamron", "50-400mm F4.5-6.3 Di III VC VXD", "E", 50, 400, 4.5, 6.3, true, true, 67.),
            lens(T70180, "Tamron", "70-180mm F2.8 Di III VXD G2", "E", 70, 180, 2.8, 2.8, true, false, 67.),
            lens(V16, "Viltrox", "AF 16mm F1.8 FE", "E", 16, 16, 1.8, null, true, false, null));
    }
}
