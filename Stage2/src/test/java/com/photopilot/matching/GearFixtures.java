package com.photopilot.matching;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Isolated F05-model fixtures for F07 tests; never written to a real user's inventory. */
public final class GearFixtures {
    private GearFixtures() { }
    public static Camera camera(String brand, String model, String mount, int quantity, Double weight, Set<String> batteries, Set<String> storage, Double burst, Boolean stabilized) {
        return new Camera(UUID.randomUUID(), brand, model, quantity, Optional.ofNullable(weight), Set.of(), Map.of(), Optional.empty(), mount,
            Optional.of(35.9), Optional.of(24.0), batteries, storage, Optional.of(45.0), Optional.ofNullable(burst), Optional.ofNullable(stabilized), Set.of("AF-C"));
    }
    public static Lens lens(String brand, String model, String mount, double min, double max, double aperture, Double filter, Double weight, Boolean autofocus) {
        return new Lens(UUID.randomUUID(), brand, model, 1, Optional.ofNullable(weight), Set.of(), Map.of(), Optional.empty(), mount, min, max, aperture,
            Optional.ofNullable(filter), Optional.ofNullable(autofocus), Optional.of(true));
    }
    public static Filter filter(String model, double diameter, Double weight) {
        return new Filter(UUID.randomUUID(), "B+W", model, 1, Optional.ofNullable(weight), Set.of(), Map.of(), Optional.empty(), diameter, Optional.empty());
    }
    public static Battery battery(String model, String type, int quantity, Double weight) {
        return new Battery(UUID.randomUUID(), "Sony", model, quantity, Optional.ofNullable(weight), Set.of(), Map.of(), Optional.empty(), type, 2280, Optional.of(500));
    }
    public static StorageMedia card(String model, String format, int quantity, double capacity, Double speed, Double weight) {
        return new StorageMedia(UUID.randomUUID(), "SanDisk", model, quantity, Optional.ofNullable(weight), Set.of(), Map.of(), Optional.empty(), format, capacity, Optional.ofNullable(speed));
    }

    /** Stage 2 airshow acceptance inventory: two compatible bodies, several lenses, one incompatible and one incomplete item. */
    public static final class Airshow {
        public final Camera a1 = camera("Sony", "A1", "Sony E", 1, 737.0, Set.of("NP-FZ100"), Set.of("SD", "CFexpress A"), 30.0, true);
        public final Camera a7 = camera("Sony", "A7 IV", "Sony E", 1, 658.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        public final Lens tele = lens("Sony", "FE 200-600mm G", "Sony E", 200, 600, 5.6, 95.0, 2115.0, true);
        public final Lens zoom = lens("Sony", "FE 100-400mm GM", "Sony E", 100, 400, 4.5, 77.0, 1395.0, true);
        public final Lens standard = lens("Sony", "FE 24-105mm G", "Sony E", 24, 105, 4, 77.0, 663.0, true);
        public final Lens canon = lens("Canon", "EF 100-400mm L II", "Canon EF", 100, 400, 4.5, 77.0, 1640.0, true);
        public final Lens vintage = lens("Vintage", "300mm f/4", "unknown", 300, 300, 4, null, null, false);
        public final Battery fz = battery("NP-FZ100", "NP-FZ100", 4, 83.0);
        public final Battery canonBattery = battery("LP-E6NH", "LP-E6NH", 2, 80.0);
        public final StorageMedia sd = card("Extreme 128GB", "SD", 2, 128, 200.0, 2.0);
        public final Filter cpl = filter("77mm CPL", 77, 30.0);
        public List<Equipment> items() { return List.of(a1, a7, tele, zoom, standard, canon, vintage, fz, canonBattery, sd, cpl); }
        public GearInventory inventory() { return new GearInventory(items()); }
    }

    public static CapabilityNeed need(String capability, Double min, Double max, int priority, boolean mandatory) {
        return CapabilityNeed.of(capability, Optional.ofNullable(min), Optional.ofNullable(max), priority, mandatory, "");
    }
    /** Fast aircraft, strong telephoto, two bodies, minimal lens changes, portability matters. */
    public static EquipmentRequirementProfile airshowProfile() {
        return new EquipmentRequirementProfile(List.of(need("focalLength", 100.0, 600.0, 1, true), need("cameraCount", 2.0, 2.0, 2, true),
            need("burstRate", 10.0, null, 2, true), need("autofocus", null, null, 2, true), need("lensCount", null, 2.0, 3, true),
            need("totalWeight", null, 6000.0, 4, false)), true);
    }
    public static ShootRequirement airshowRequirement() {
        return new ShootRequirement("Airshow with two bodies, minimal lens changes", "Aircraft at an airshow", List.of("Fast jets"), Optional.empty(),
            Optional.of(LocalDate.of(2026, 11, 14)), "afternoon", Optional.empty(), Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false),
            Optional.empty(), "minimize weight", List.of(), Map.of("telephoto reach", 1, "low weight", 2), Map.of(), List.of(), true);
    }

    /** Agent whose model replies with {@code reply(context)}; contexts are recorded. No network. */
    public static PhotoPilotAgent agent(Path root, List<AgentContext> seen, Function<AgentContext, Result<ModelReply>> reply) {
        return new PhotoPilotAgent(context -> { seen.add(context); return reply.apply(context); }, new ToolRegistry(Map.of()),
            new HistoryRetriever(new JsonSessionRepository(root)), 8);
    }
    public static Result<ModelReply> json(String text) { return Result.success(new ModelReply(Optional.of(text), List.of(), List.of())); }
    /** Valid ranking reply listing labels C1..Cn in the given order. */
    public static String ranking(int... order) {
        var entries = new StringJoiner(",");
        for (int label : order) entries.add("{\"candidate\":\"C" + label + "\",\"explanation\":\"Trade-off note for C" + label + ".\"}");
        return "{\"ranking\":[" + entries + "],\"summary\":\"Compared verified candidates.\"}";
    }
}
