package com.photopilot.repository;

import com.photopilot.service.SessionService;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Child JVM harness for real OS locks and process termination; never production behavior. */
public final class PersistenceProcess {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]); Path ready = Path.of(args[2]);
        switch (args[0]) {
            case "profile-interrupt" -> new JsonProfileRepository(root) {
                protected void movePrepared(Path staging, Path destination) throws IOException {
                    Files.writeString(ready, "ready"); System.in.read(); super.movePrepared(staging, destination);
                }
            }.initializeDefault();
            case "profile-seed", "profile-check" -> {
                var environment = java.util.Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
                try (var manager = new com.photopilot.application.ProfileManager(environment)) {
                    if (args[0].equals("profile-seed")) {
                        UUID first = manager.activeProfile().value().orElseThrow().id();
                        var gear = ProfileManagerTest.camera("Process A");
                        ProfileManagerTest.ok(manager.activeContext().facade().inventory().add(gear, false));
                        UUID second = ProfileManagerTest.ok(manager.createProfile("Process B")).id();
                        ProfileManagerTest.ok(manager.selectProfile(second, true));
                        UUID session = ProfileManagerTest.ok(manager.activeContext().facade().sessions().create("Process B", java.time.LocalDate.now())).id();
                        Files.write(ready, java.util.List.of(first.toString(), second.toString(), gear.id().toString(), session.toString()));
                    } else {
                        var ids = Files.readAllLines(ready).stream().map(UUID::fromString).toList();
                        if (!manager.activeProfile().value().orElseThrow().id().equals(ids.get(0))) throw new AssertionError("Default identity changed");
                        var inventory = ProfileManagerTest.ok(manager.activeContext().facade().inventory().list(""));
                        if (inventory.find(ids.get(2)).isEmpty() || manager.activeContext().facade().sessions().open(ids.get(3)).isSuccess()) throw new AssertionError("A isolation failed");
                        ProfileManagerTest.ok(manager.selectProfile(ids.get(1), true));
                        if (!ProfileManagerTest.ok(manager.activeContext().facade().inventory().list("")).items().isEmpty()) throw new AssertionError("B inventory leaked");
                        ProfileManagerTest.ok(manager.activeContext().facade().sessions().open(ids.get(3)));
                    }
                }
            }
            case "lock" -> AtomicFiles.locked(root, () -> { Files.writeString(ready, "ready"); System.in.read(); return true; });
            case "interrupt" -> {
                var repository = new JsonSessionRepository(root) {
                    protected void replace(Path temporary) throws IOException {
                        Files.writeString(ready, "ready"); System.in.read(); super.replace(temporary);
                    }
                };
                var session = repository.find(UUID.fromString(args[3])).value().orElseThrow();
                var saved = repository.save(session.edit("Uncommitted", session.date(), session.location(), ""));
                if (!saved.isSuccess()) throw new IllegalStateException(saved.errors().toString());
            }
            case "edit" -> {
                var service = new SessionService(new JsonSessionRepository(root));
                var session = service.open(UUID.fromString(args[3])).value().orElseThrow();
                var saved = service.update(session, "Other process", session.date(), session.location(), "Other process edit");
                if (!saved.isSuccess()) throw new IllegalStateException(saved.errors().toString());
            }
            default -> throw new IllegalArgumentException("Unknown test mode");
        }
    }
}
