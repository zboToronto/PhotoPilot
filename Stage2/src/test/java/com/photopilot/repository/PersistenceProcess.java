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
