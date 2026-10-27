package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.io.PrintStream;
import java.util.*;

/** F05 command parsing and output; all mutations go through the shared facade service. */
final class InventoryCommands {
    private final PhotoPilotFacade app;
    private final PrintStream output;
    InventoryCommands(PhotoPilotFacade app, PrintStream output) { this.app = app; this.output = output; }
    int execute(String line) {
        try {
            var words = tokenize(line);
            String action = words.size() < 2 ? "list" : words.get(1).toLowerCase(Locale.ROOT);
            if (action.equals("help")) { help(); return 0; }
            if (action.equals("list")) {
                var options = options(words, 2, Set.of("query", "type"));
                EquipmentType type = options.containsKey("type") ? type(options.get("type")) : null;
                var result = app.inventory().list(options.getOrDefault("query", ""), type);
                if (!report(result)) return 2;
                var items = result.value().orElseThrow().items();
                output.println("Inventory: " + items.size() + " item(s)");
                items.forEach(this::show);
                return 0;
            }
            if (action.equals("remove")) {
                if (words.size() < 3) throw new IllegalArgumentException("Use gear remove <UUID> [--confirm].");
                UUID id = UUID.fromString(words.get(2));
                var opts = options(words, 3, Set.of("--confirm"));
                var affected = app.inventory().affectedSessions(id);
                if (!report(affected)) return 2;
                if (!affected.value().orElseThrow().isEmpty()) output.println("Affected sessions: " + affected.value().orElseThrow() + ". Historical snapshots are preserved.");
                var result = app.inventory().remove(id, opts.containsKey("--confirm"));
                if (!report(result)) return 2;
                output.println("Removed " + id); return 0;
            }
            if (!action.equals("add") && !action.equals("edit")) throw new IllegalArgumentException("Unknown gear action. Use gear help.");
            if (words.size() < 3) throw new IllegalArgumentException("Supply an equipment type for add or UUID for edit. Use gear help.");
            Equipment original = null;
            EquipmentType kind;
            UUID id;
            if (action.equals("edit")) {
                id = UUID.fromString(words.get(2));
                var loaded = app.inventory().list("");
                if (!report(loaded)) return 2;
                original = loaded.value().orElseThrow().find(id).orElseThrow(() -> new IllegalArgumentException("Equipment not found."));
                kind = original.type();
            } else { kind = type(words.get(2)); id = UUID.randomUUID(); }
            var allowed = new HashSet<>(List.of("brand", "model", "quantity", "weightGrams", "--confirm-duplicate"));
            if (original == null) allowed.add("id");
            kind.fields().forEach(field -> allowed.add(field.key()));
            var fields = options(words, 3, allowed);
            boolean duplicateAccepted = fields.remove("--confirm-duplicate") != null;
            if (fields.containsKey("id")) id = UUID.fromString(fields.remove("id"));
            var parsed = EquipmentForm.parse(kind, id, fields, original);
            if (!report(parsed)) return 2;
            var item = parsed.value().orElseThrow();
            var result = original == null ? app.inventory().add(item, duplicateAccepted) : app.inventory().update(item, duplicateAccepted, original);
            if (!report(result)) return 2;
            output.println("Saved " + item.id()); show(item); return 0;
        } catch (IllegalArgumentException error) { output.println("Error: " + error.getMessage()); return 2; }
    }
    private boolean report(Result<?> result) {
        result.warnings().forEach(warning -> output.println("Warning: " + warning));
        if (!result.isSuccess()) { result.errors().forEach(error -> output.println("Error: " + error)); return false; }
        return true;
    }
    private void show(Equipment item) {
        output.printf("%s | %s | %s %s | qty %d%n", item.id(), item.type(), item.brand(), item.model(), item.quantity());
        output.println("  " + EquipmentForm.values(item));
        output.println("  Verified fields: " + item.verifiedFields() + "; remaining fields are unverified; blank optional values are unknown.");
    }
    private static EquipmentType type(String text) { return EquipmentType.valueOf(text.replace("-", "").replace("_", "").toUpperCase(Locale.ROOT)); }
    private static Map<String, String> options(List<String> words, int start, Set<String> allowed) {
        var result = new LinkedHashMap<String, String>();
        for (int i = start; i < words.size(); i++) {
            String word = words.get(i); int split = word.indexOf('=');
            String key = split < 0 ? word : word.substring(0, split);
            if (!allowed.contains(key) || (split < 0 && !key.startsWith("--")) || (split >= 0 && key.startsWith("--"))) throw new IllegalArgumentException("Unknown or malformed option: " + key);
            if (result.putIfAbsent(key, split < 0 ? "true" : word.substring(split + 1)) != null) throw new IllegalArgumentException("Repeated option: " + key);
        }
        return result;
    }
    static List<String> tokenize(String line) {
        List<String> words = new ArrayList<>(); StringBuilder word = new StringBuilder(); char quote = 0;
        for (char c : line.toCharArray()) {
            if (quote != 0) { if (c == quote) quote = 0; else word.append(c); }
            else if (c == '"' || c == '\'') quote = c;
            else if (Character.isWhitespace(c)) { if (!word.isEmpty()) { words.add(word.toString()); word.setLength(0); } }
            else word.append(c);
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote.");
        if (!word.isEmpty()) words.add(word.toString());
        return words;
    }
    private void help() {
        output.println("gear list [query=\"text\"] [type=camera|lens|filter|battery|storagemedia]");
        output.println("gear add <type> brand=\"brand\" model=\"model\" quantity=1 <spec=value ...> [id=<UUID>] [--confirm-duplicate]");
        output.println("gear edit <UUID> <field=value ...> [--confirm-duplicate] | gear remove <UUID> [--confirm]");
        output.println("Optional common field: weightGrams. Use an empty value to clear an optional field. Unchanged edit fields are preserved.");
        for (var type : EquipmentType.values()) output.println(type.name().toLowerCase(Locale.ROOT) + ": " + type.fields().stream().map(f -> f.key() + (f.required() ? "*" : "")).toList());
        output.println("* required; booleans true/false or blank (unknown); multiple values comma separated. F05 aliases gear list.");
    }
}
