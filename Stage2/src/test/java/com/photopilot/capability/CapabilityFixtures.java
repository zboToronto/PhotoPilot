package com.photopilot.capability;

import com.google.gson.*;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Stage 2 F06 acceptance fixtures: the sunset aircraft request, its F03 extraction and a capability-only Gemini reply. */
public final class CapabilityFixtures {
    private CapabilityFixtures() { }
    public static final String REQUEST = "I want to photograph fast-moving aircraft around sunset for approximately three hours. "
        + "I prefer using two camera bodies, minimizing lens changes, and keeping the overall equipment weight reasonable.";
    /** F03 structured output for {@link #REQUEST}; no numeric weight limit, date or place was stated. */
    public static final String REQUIREMENT_REPLY = """
        {"subject":"Fast-moving aircraft","goals":["Aircraft in flight around sunset"],"date":null,"startTime":null,
         "timeWindow":"around sunset","location":null,"durationMinutes":180,"cameraCount":2,"lensChangesAllowed":false,
         "maxWeightGrams":null,"portability":"keep the overall equipment weight reasonable","gearConstraints":["minimize lens changes"],
         "priorities":[{"name":"reasonable weight","rank":2},{"name":"capture fast aircraft","rank":1}],"fixedConstraints":[],
         "clarificationQuestions":[],"flaggedFields":[]}
        """;
    /** Capability-only F06 output: no brand, product or owned-equipment names. */
    public static final String PROPOSAL = """
        {"needs":[
          {"capability":"focalLength","minimum":300,"maximum":500,"priority":1,"mandatory":true,"rationale":"Telephoto coverage of approximately 300-500 mm is desirable for distant aircraft."},
          {"capability":"autofocus","minimum":null,"maximum":null,"priority":1,"mandatory":true,"rationale":"Fast subjects need reliable continuous autofocus on every lens."},
          {"capability":"burstRate","minimum":10,"maximum":null,"priority":2,"mandatory":true,"rationale":"A high frame rate catches the decisive moment of a fast pass."},
          {"capability":"maxAperture","minimum":null,"maximum":5.6,"priority":3,"mandatory":false,"rationale":"Fading sunset light favours a brighter maximum aperture."},
          {"capability":"stabilization","minimum":null,"maximum":null,"priority":4,"mandatory":false,"rationale":"Stabilization helps hand-held telephoto panning in low light."},
          {"capability":"cameraCount","minimum":2,"maximum":2,"priority":2,"mandatory":true,"rationale":"Two bodies, as requested, remove the need for lens changes."},
          {"capability":"lensCount","minimum":null,"maximum":2,"priority":3,"mandatory":false,"rationale":"One lens per body keeps lens changes to a minimum."},
          {"capability":"totalWeight","minimum":null,"maximum":6000,"priority":4,"mandatory":false,"rationale":"A reasonable total weight keeps three hours of hand-holding manageable."},
          {"capability":"storageCapacity","minimum":256,"maximum":null,"priority":5,"mandatory":false,"rationale":"Bursts over three hours need ample card space."},
          {"capability":"batteryCount","minimum":4,"maximum":null,"priority":5,"mandatory":false,"rationale":"Two bodies shooting bursts for three hours need spares."}],
         "tradeOffs":["Telephoto reach competes with keeping the total weight reasonable.","Two bodies avoid lens changes but add weight."],
         "unsupportedNeeds":["Weather sealing in case the evening turns damp."],
         "clarificationQuestions":["How far from the flight line will you stand?"]}
        """;

    public static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    /** Session whose requirement went through the real F03 parse and explicit confirmation. */
    public static UUID confirmedSession(ApplicationBootstrap app, FakeGemini gemini) {
        var id = ok(app.facade().sessions().create("Sunset aircraft", LocalDate.of(2026, 11, 14))).id();
        gemini.reply(REQUIREMENT_REPLY);
        var draft = ok(app.facade().parse(id, REQUEST));
        ok(app.facade().planning().confirm(id, draft));
        return id;
    }

    /** F06 reply with only {@code needs} given (other sections empty). */
    public static String reply(String needs) {
        return "{\"needs\":" + needs + ",\"tradeOffs\":[],\"unsupportedNeeds\":[],\"clarificationQuestions\":[]}";
    }
    public static String need(String capability, Object minimum, Object maximum, Object priority, Object mandatory, String rationale) {
        var item = new JsonObject();
        item.addProperty("capability", capability);
        item.add("minimum", value(minimum)); item.add("maximum", value(maximum));
        item.add("priority", value(priority)); item.add("mandatory", value(mandatory));
        item.addProperty("rationale", rationale);
        return item.toString();
    }
    private static JsonElement value(Object value) {
        return value == null ? JsonNull.INSTANCE : value instanceof Boolean flag ? new JsonPrimitive(flag) : new JsonPrimitive((Number) value);
    }
}
