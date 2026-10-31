package com.photopilot.repository;

import com.photopilot.config.ApplicationBootstrap;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StorageScopeTest {
    @TempDir Path root;

    @Test void scopeIsExplicitNormalizedAndSideEffectFree() {
        Path data = root.resolve("unused/../data");
        var legacy = StorageScope.legacy(data); UUID id = UUID.randomUUID();
        var profile = StorageScope.forProfile(data, id);
        assertEquals(root.resolve("data/inventory.json"), legacy.inventoryFile());
        assertEquals(root.resolve("data/sessions"), legacy.sessionsDirectory());
        assertTrue(legacy.profileId().isEmpty());
        assertEquals(id, profile.profileId().orElseThrow());
        assertEquals(root.resolve("data/profiles").resolve(id.toString()), profile.directory());
        assertNotEquals(profile.directory(), StorageScope.forProfile(data, UUID.randomUUID()).directory());
        assertFalse(Files.exists(root.resolve("data")));
        assertThrows(NullPointerException.class, () -> StorageScope.forProfile(data, null));
    }

    @Test void startupUsesStableDefaultAndExplicitRegisteredScope() throws Exception {
        Path data = root.resolve("data");
        var environment = Map.of("PHOTOPILOT_DATA_DIR", data.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
        StorageScope first;
        try (var app = new ApplicationBootstrap(environment)) {
            first = app.storageScope(); assertTrue(first.profileId().isPresent());
            assertTrue(Files.exists(data.resolve("profiles.json")));
            assertFalse(Files.exists(first.inventoryFile()));
            assertTrue(app.facade().createSession("Default", LocalDate.of(2026, 11, 1)).isSuccess());
            assertTrue(Files.exists(first.sessionsDirectory().resolve("sessions.json")));
        }
        try (var app = new ApplicationBootstrap(environment)) { assertEquals(first, app.storageScope()); }
        var registry = new JsonProfileRepository(data); var selected = StorageScope.forProfile(data, registry.create("Second").id());
        try (var app = new ApplicationBootstrap(environment, selected)) {
            assertEquals(selected, app.storageScope());
            assertTrue(app.facade().sessions().search("").value().orElseThrow().isEmpty());
            assertTrue(app.facade().inventory().list("").value().orElseThrow().items().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> new ApplicationBootstrap(environment, StorageScope.forProfile(data, UUID.randomUUID())));
    }
}
