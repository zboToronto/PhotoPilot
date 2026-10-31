package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.state.SessionState;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Explicit v1 constructor mappings: disk input cannot bypass defensive copies or invariants.
 * Serialization retains the existing field schema. This is not an AI-output decoder.
 */
final class SessionDomainAdapters {
    private SessionDomainAdapters() { }
    static GsonBuilder register(GsonBuilder builder) {
        builder.registerTypeAdapter(PhotographySession.class, (JsonDeserializer<PhotographySession>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new PhotographySession(
                read(o, "id", c, new TypeToken<UUID>() {}),
                read(o, "title", c, new TypeToken<String>() {}),
                read(o, "date", c, new TypeToken<LocalDate>() {}),
                read(o, "location", c, new TypeToken<Optional<GeoLocation>>() {}),
                read(o, "notes", c, new TypeToken<String>() {}),
                read(o, "createdAt", c, new TypeToken<Instant>() {}),
                read(o, "updatedAt", c, new TypeToken<Instant>() {}),
                read(o, "state", c, new TypeToken<SessionState>() {}),
                read(o, "requirements", c, new TypeToken<Optional<ShootRequirement>>() {}),
                read(o, "plans", c, new TypeToken<List<ShootPlan>>() {}),
                read(o, "reviews", c, new TypeToken<List<PostShootReview>>() {}),
                read(o, "profile", c, new TypeToken<Optional<EquipmentRequirementProfile>>() {}),
                read(o, "statistics", c, new TypeToken<Optional<ExifStatistics>>() {}),
                read(o, "savedIdeas", c, new TypeToken<List<ActivitySuggestion>>() {}));
        });
        builder.registerTypeAdapter(ShootRequirement.class, (JsonDeserializer<ShootRequirement>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new ShootRequirement(
                read(o, "originalText", c, new TypeToken<String>() {}),
                read(o, "subject", c, new TypeToken<String>() {}),
                read(o, "goals", c, new TypeToken<List<String>>() {}),
                read(o, "time", c, new TypeToken<Optional<ZonedDateTime>>() {}),
                read(o, "location", c, new TypeToken<Optional<GeoLocation>>() {}),
                read(o, "duration", c, new TypeToken<Duration>() {}),
                read(o, "cameraCount", c, new TypeToken<Integer>() {}),
                read(o, "lensChangesAllowed", c, new TypeToken<Boolean>() {}),
                read(o, "maxWeightGrams", c, new TypeToken<Optional<Double>>() {}),
                read(o, "gearConstraints", c, new TypeToken<List<String>>() {}),
                read(o, "priorities", c, new TypeToken<Map<String, Integer>>() {}),
                read(o, "fixedConstraints", c, new TypeToken<Map<String, String>>() {}),
                read(o, "clarificationQuestions", c, new TypeToken<List<String>>() {}),
                read(o, "confirmed", c, new TypeToken<Boolean>() {}));
        });
        builder.registerTypeAdapter(ShootPlan.class, (JsonDeserializer<ShootPlan>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new ShootPlan(
                read(o, "id", c, new TypeToken<UUID>() {}),
                read(o, "revision", c, new TypeToken<Integer>() {}),
                read(o, "approved", c, new TypeToken<Boolean>() {}),
                read(o, "provisionalReasons", c, new TypeToken<List<String>>() {}),
                read(o, "loadout", c, new TypeToken<Optional<Loadout>>() {}),
                read(o, "settings", c, new TypeToken<Map<String, String>>() {}),
                read(o, "windows", c, new TypeToken<List<ShootingWindow>>() {}),
                read(o, "checklist", c, new TypeToken<List<String>>() {}),
                read(o, "preparation", c, new TypeToken<List<String>>() {}),
                read(o, "shotPriorities", c, new TypeToken<List<String>>() {}),
                read(o, "fixedConstraints", c, new TypeToken<Map<String, String>>() {}),
                read(o, "calculations", c, new TypeToken<List<CalculationResult>>() {}));
        });
        builder.registerTypeAdapter(PostShootReview.class, (JsonDeserializer<PostShootReview>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new PostShootReview(
                read(o, "id", c, new TypeToken<UUID>() {}),
                read(o, "createdAt", c, new TypeToken<Instant>() {}),
                read(o, "statistics", c, new TypeToken<ExifStatistics>() {}),
                read(o, "comparedPlanId", c, new TypeToken<UUID>() {}),
                read(o, "comparedRevision", c, new TypeToken<Integer>() {}),
                read(o, "planDifferences", c, new TypeToken<List<String>>() {}),
                read(o, "lessons", c, new TypeToken<List<String>>() {}),
                read(o, "limitations", c, new TypeToken<List<String>>() {}));
        });
        builder.registerTypeAdapter(EquipmentRequirementProfile.class, (JsonDeserializer<EquipmentRequirementProfile>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new EquipmentRequirementProfile(
                read(o, "needs", c, new TypeToken<List<CapabilityNeed>>() {}),
                read(o, "reviewed", c, new TypeToken<Boolean>() {}));
        });
        builder.registerTypeAdapter(ExifStatistics.class, (JsonDeserializer<ExifStatistics>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new ExifStatistics(
                read(o, "importedCount", c, new TypeToken<Integer>() {}),
                read(o, "usableCount", c, new TypeToken<Integer>() {}),
                read(o, "missingCounts", c, new TypeToken<Map<String, Integer>>() {}),
                read(o, "focalLengthCounts", c, new TypeToken<Map<Double, Integer>>() {}),
                read(o, "shutterCounts", c, new TypeToken<Map<Double, Integer>>() {}),
                read(o, "apertureCounts", c, new TypeToken<Map<Double, Integer>>() {}),
                read(o, "isoCounts", c, new TypeToken<Map<Integer, Integer>>() {}),
                read(o, "cameraCounts", c, new TypeToken<Map<String, Integer>>() {}),
                read(o, "lensCounts", c, new TypeToken<Map<String, Integer>>() {}),
                read(o, "limitations", c, new TypeToken<List<String>>() {}));
        });
        builder.registerTypeAdapter(ActivitySuggestion.class, (JsonDeserializer<ActivitySuggestion>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new ActivitySuggestion(
                read(o, "subject", c, new TypeToken<String>() {}),
                read(o, "setting", c, new TypeToken<String>() {}),
                read(o, "estimatedDuration", c, new TypeToken<Duration>() {}),
                read(o, "estimatedTravelKm", c, new TypeToken<Optional<Double>>() {}),
                read(o, "rationale", c, new TypeToken<String>() {}),
                read(o, "limitations", c, new TypeToken<List<String>>() {}));
        });
        builder.registerTypeAdapter(GeoLocation.class, (JsonDeserializer<GeoLocation>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new GeoLocation(
                read(o, "label", c, new TypeToken<String>() {}),
                read(o, "latitude", c, new TypeToken<Double>() {}),
                read(o, "longitude", c, new TypeToken<Double>() {}),
                read(o, "approximate", c, new TypeToken<Boolean>() {}),
                read(o, "fromDevice", c, new TypeToken<Boolean>() {}),
                read(o, "consentGrantedAt", c, new TypeToken<Optional<Instant>>() {}),
                read(o, "coordinatesPresent", c, new TypeToken<Boolean>() {}));
        });
        builder.registerTypeAdapter(CapabilityNeed.class, (JsonDeserializer<CapabilityNeed>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new CapabilityNeed(
                read(o, "capability", c, new TypeToken<String>() {}),
                read(o, "minimum", c, new TypeToken<Optional<Double>>() {}),
                read(o, "maximum", c, new TypeToken<Optional<Double>>() {}),
                read(o, "unit", c, new TypeToken<String>() {}),
                read(o, "priority", c, new TypeToken<Integer>() {}),
                read(o, "hardConstraint", c, new TypeToken<Boolean>() {}),
                read(o, "rationale", c, new TypeToken<String>() {}));
        });
        builder.registerTypeAdapter(Loadout.class, (JsonDeserializer<Loadout>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new Loadout(
                read(o, "id", c, new TypeToken<UUID>() {}),
                read(o, "gear", c, new TypeToken<List<GearSnapshot>>() {}),
                read(o, "cameraLensAssignments", c, new TypeToken<Map<UUID, UUID>>() {}),
                read(o, "compatibility", c, new TypeToken<CompatibilityReport>() {}),
                read(o, "tradeOffs", c, new TypeToken<List<String>>() {}),
                read(o, "unmetNeeds", c, new TypeToken<List<String>>() {}));
        });
        builder.registerTypeAdapter(CompatibilityReport.class, (JsonDeserializer<CompatibilityReport>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new CompatibilityReport(
                read(o, "compatible", c, new TypeToken<Boolean>() {}),
                read(o, "unverified", c, new TypeToken<List<String>>() {}),
                read(o, "violations", c, new TypeToken<List<String>>() {}),
                read(o, "unmetNeeds", c, new TypeToken<List<String>>() {}));
        });
        builder.registerTypeAdapter(GearSnapshot.class, (JsonDeserializer<GearSnapshot>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new GearSnapshot(
                read(o, "sourceGearId", c, new TypeToken<UUID>() {}),
                read(o, "type", c, new TypeToken<String>() {}),
                read(o, "brand", c, new TypeToken<String>() {}),
                read(o, "model", c, new TypeToken<String>() {}),
                read(o, "selectedQuantity", c, new TypeToken<Integer>() {}),
                read(o, "capturedAt", c, new TypeToken<Instant>() {}),
                read(o, "specs", c, new TypeToken<Map<String, String>>() {}),
                read(o, "verifiedFields", c, new TypeToken<Set<String>>() {}),
                read(o, "sources", c, new TypeToken<Map<String, URI>>() {}));
        });
        builder.registerTypeAdapter(CalculationResult.class, (JsonDeserializer<CalculationResult>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new CalculationResult(
                read(o, "values", c, new TypeToken<Map<String, Double>>() {}),
                read(o, "units", c, new TypeToken<Map<String, String>>() {}),
                read(o, "estimated", c, new TypeToken<Boolean>() {}),
                read(o, "assumptions", c, new TypeToken<List<String>>() {}),
                read(o, "input", c, new TypeToken<CalculationInput>() {}),
                read(o, "localizedAssumptions", c, new TypeToken<List<LocalizedMessage>>() {}));
        });
        builder.registerTypeAdapter(CalculationInput.class, (JsonDeserializer<CalculationInput>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new CalculationInput(
                read(o, "operation", c, new TypeToken<CalculationKind>() {}),
                read(o, "parameters", c, new TypeToken<Map<String, Double>>() {}),
                read(o, "units", c, new TypeToken<Map<String, String>>() {}));
        });
        builder.registerTypeAdapter(ShootingWindow.class, (JsonDeserializer<ShootingWindow>) (json, type, c) -> {
            JsonObject o = json.getAsJsonObject();
            return new ShootingWindow(
                read(o, "start", c, new TypeToken<ZonedDateTime>() {}),
                read(o, "end", c, new TypeToken<ZonedDateTime>() {}),
                read(o, "arrival", c, new TypeToken<ZonedDateTime>() {}),
                read(o, "conditions", c, new TypeToken<String>() {}),
                read(o, "estimated", c, new TypeToken<Boolean>() {}));
        });
        return builder;
    }
    private static <T> T read(JsonObject object, String field, JsonDeserializationContext context, TypeToken<T> type) {
        if (!object.has(field)) throw new JsonParseException("Missing session field: " + field);
        JsonElement value = object.get(field);
        if (type.getRawType() == Optional.class && value.isJsonNull()) {
            // Gson's tree adapter bypasses custom adapters for JSON null.
            @SuppressWarnings("unchecked") T empty = (T) Optional.empty();
            return empty;
        }
        return Objects.requireNonNull(context.deserialize(value, type.getType()), "Null session field: " + field);
    }
}
