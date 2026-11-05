package com.photopilot.service;

import java.util.List;
import java.util.Locale;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.model.CapabilityNeed;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GearNames;

/**
 * F06 structured-output DTO: the exact JSON shape requested from the model. Every value is untrusted;
 * {@link RequirementService} converts it into an unreviewed draft and the domain validates it.
 */
public record CapabilityExtraction(List<Need> needs, List<String> tradeOffs, List<String> unsupportedNeeds,
        List<String> clarificationQuestions) {
    public record Need(String capability, Double minimum, Double maximum, Integer priority, Boolean mandatory, String rationale) { }

    /** Provider-neutral JSON Schema; the capability enum is exactly the deterministically checkable set. */
    public static final String SCHEMA = schema();
    private static String schema() {
        var capability = new JsonArray(); CapabilityNeed.SUPPORTED.keySet().forEach(capability::add);
        var need = JsonParser.parseString("""
            {"type": "object", "properties": {
              "capability": {"type": "string"},
              "minimum": {"type": ["number", "null"], "description": "Lower bound in the capability's unit, or null."},
              "maximum": {"type": ["number", "null"], "description": "Upper bound in the capability's unit, or null."},
              "priority": {"type": "integer", "description": "1 = most important ... 10 = least important."},
              "mandatory": {"type": "boolean", "description": "true only for hard requirements; false for preferences."},
              "rationale": {"type": "string", "description": "One sentence: why this shoot needs it. No brand or product names."}},
             "required": ["capability", "minimum", "maximum", "priority", "mandatory", "rationale"]}
            """).getAsJsonObject();
        need.getAsJsonObject("properties").getAsJsonObject("capability").add("enum", capability);
        var root = JsonParser.parseString("""
            {"type": "object", "properties": {
              "needs": {"type": "array"},
              "tradeOffs": {"type": "array", "items": {"type": "string"}, "description": "Short explanations of priorities and trade-offs between needs."},
              "unsupportedNeeds": {"type": "array", "items": {"type": "string"}, "description": "Relevant capabilities that none of the listed capabilities can express."},
              "clarificationQuestions": {"type": "array", "items": {"type": "string"}, "description": "At most four questions about materially missing information."}},
             "required": ["needs", "tradeOffs", "unsupportedNeeds", "clarificationQuestions"]}
            """).getAsJsonObject();
        root.getAsJsonObject("properties").getAsJsonObject("needs").add("items", need);
        return root.toString();
    }

    /** A draft in the same shape, sent as evidence when the user answers clarification questions. */
    static JsonObject from(EquipmentRequirementProfile draft) {
        var out = new JsonObject(); var needs = new JsonArray();
        for (CapabilityNeed need : draft.needs()) {
            var item = new JsonObject();
            item.addProperty("capability", need.capability());
            item.addProperty("minimum", need.minimum().orElse(null)); item.addProperty("maximum", need.maximum().orElse(null));
            item.addProperty("priority", need.priority()); item.addProperty("mandatory", need.hardConstraint());
            item.addProperty("rationale", need.rationale());
            needs.add(item);
        }
        out.add("needs", needs);
        var tradeOffs = new JsonArray(); draft.tradeOffs().forEach(tradeOffs::add); out.add("tradeOffs", tradeOffs);
        return out;
    }

    /** Brand and product-line words; F06 states capabilities, product choice belongs to F07 and the user's own gear. */
    static final List<List<String>> PRODUCT_WORDS = List.of(List.of("sony"), List.of("canon"), List.of("nikon"), List.of("nikkor"),
        List.of("fujifilm"), List.of("fujinon"), List.of("panasonic"), List.of("lumix"), List.of("olympus"), List.of("zuiko"),
        List.of("om", "system"), List.of("leica"), List.of("hasselblad"), List.of("pentax"), List.of("ricoh"), List.of("sigma"), List.of("tamron"),
        List.of("tokina"), List.of("zeiss"), List.of("samyang"), List.of("rokinon"), List.of("viltrox"), List.of("laowa"), List.of("voigtlander"),
        List.of("gopro"), List.of("dji"), List.of("insta", "360"), List.of("sandisk"), List.of("lexar"), List.of("prograde"), List.of("eos"),
        List.of("g", "master"), List.of("blackmagic"), List.of("red", "komodo"));

    /** Deterministic check: does the text name a brand or product line (token-exact, case-insensitive)? */
    static boolean namesProduct(String text) {
        var tokens = GearNames.tokens(java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT));
        for (List<String> words : PRODUCT_WORDS)
            for (int i = 0; i + words.size() <= tokens.size(); i++)
                if (tokens.subList(i, i + words.size()).equals(words)) return true;
        return false;
    }
}
