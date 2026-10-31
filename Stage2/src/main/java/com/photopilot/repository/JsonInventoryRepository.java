package com.photopilot.repository;

import com.photopilot.i18n.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import static java.nio.file.StandardOpenOption.*;

/** Versioned JSON storage with a process lock, optimistic revisions and atomic replacement. */
public class JsonInventoryRepository implements InventoryRepository {
    private final Path file;
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().setStrictness(Strictness.STRICT).create();
    private record Document(int schemaVersion, List<EquipmentData> items) { }

    public JsonInventoryRepository(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath().normalize(); }

    @Override public Result<GearInventory> load() {
        try { return Result.success(read()); }
        catch (IOException | RuntimeException error) { return Result.failureKey("error.inventory.read", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
    }
    private GearInventory read() throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes((int) MAX_BYTES + 1);
        } catch (NoSuchFileException error) { return new GearInventory(List.of()); }
        if (bytes.length > MAX_BYTES) throw new IOException("Inventory exceeds the 10 MiB limit.");
        String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        // Inspect tokens first: reject duplicate keys, unknown fields and trailing content.
        JsonReader reader = new JsonReader(new StringReader(source));
        reader.setStrictness(Strictness.STRICT);
        JsonElement tree = readElement(reader, 0);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON content.");
        JsonObject root = tree.getAsJsonObject();
        keys(root, Set.of("schemaVersion", "items"));
        if (!root.has("schemaVersion") || !root.get("schemaVersion").toString().equals("1")) throw new IOException("Unsupported inventory schema version.");
        for (JsonElement entry : root.getAsJsonArray("items")) {
            JsonObject item = entry.getAsJsonObject();
            keys(item, Set.of("type","id","brand","model","quantity","weightGrams","verifiedFields","sources","verifiedAt","specs"));
            for (String field : List.of("type", "id", "brand", "model")) string(item.get(field));
            if (!item.get("quantity").isJsonPrimitive() || !item.get("quantity").getAsJsonPrimitive().isNumber()) throw new IOException("Quantity must be a JSON integer.");
            item.get("quantity").getAsBigDecimal().intValueExact();
            JsonElement weight = item.get("weightGrams");
            if (weight != null && !weight.isJsonNull() && (!weight.isJsonPrimitive() || !weight.getAsJsonPrimitive().isNumber())) throw new IOException("Weight must be a JSON number or null.");
            for (JsonElement field : item.getAsJsonArray("verifiedFields")) string(field);
            for (String map : List.of("specs", "sources")) for (JsonElement value : item.getAsJsonObject(map).asMap().values()) string(value);
            if (item.has("verifiedAt") && !item.get("verifiedAt").isJsonNull()) string(item.get("verifiedAt"));
        }
        Document document = JSON.fromJson(tree, Document.class);
        if (document.items() == null) throw new IOException("Inventory items are missing.");
        List<Equipment> items = new ArrayList<>();
        for (EquipmentData data : document.items()) {
            var result = data.toEquipment();
            if (!result.isSuccess()) throw new IOException(String.join("; ", result.errors()));
            items.add(result.value().orElseThrow());
        }
        return new GearInventory(items, digest(bytes));
    }
    private static void keys(JsonObject object, Set<String> allowed) throws IOException {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IOException("Unknown inventory field: " + key);
    }
    private static void string(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected a JSON string in equipment data.");
    }
    static JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 20) throw new IOException("JSON nesting is too deep.");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON field: " + key);
                    object.add(key, readElement(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readElement(reader, depth + 1));
                reader.endArray(); return array;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Malformed inventory JSON.");
        }
    }
    @Override public Result<GearInventory> save(GearInventory inventory) {
        if (inventory == null) return Result.failureKey("error.inventory.required");
        return locked(() -> {
            var current = read();
            if (!current.revision().equals(inventory.revision())) return Result.failureKey("error.inventory.changed");
            write(inventory);
            return Result.success(inventory);
        });
    }
    @Override public <T> Result<T> transact(Function<GearInventory, Result<T>> change) {
        return locked(() -> {
            var inventory = read();
            var result = change.apply(inventory);
            if (result.isSuccess()) write(inventory);
            return result;
        });
    }
    @FunctionalInterface private interface Operation<T> { Result<T> run() throws IOException; }
    private <T> Result<T> locked(Operation<T> operation) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), CREATE, WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) return Result.failureKey("error.inventory.busy");
                return operation.run();
            }
        } catch (OverlappingFileLockException error) { return Result.failureKey("error.inventory.busy"); }
        catch (IOException | RuntimeException error) { return Result.failureKey("error.inventory.write", Objects.toString(error.getMessage(), error.getClass().getSimpleName())); }
    }
    private void write(GearInventory inventory) throws IOException {
        // Revalidate even when called directly through the persistence port.
        new GearInventory(inventory.items());
        byte[] bytes = JSON.toJson(new Document(1, inventory.items().stream().map(EquipmentData::from).toList())).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Inventory exceeds the 10 MiB limit.");
        Path temporary = Files.createTempFile(file.getParent(), "inventory-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            replace(temporary);
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Protected seam for deterministic disk-failure tests. No non-atomic fallback. */
    protected void replace(Path temporary) throws IOException {
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
