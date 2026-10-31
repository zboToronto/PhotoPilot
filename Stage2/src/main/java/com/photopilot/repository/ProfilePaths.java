package com.photopilot.repository;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Refuse existing links/reparse points, including ancestors; never follow a profile name as a path. */
final class ProfilePaths {
    private ProfilePaths() { }
    static void safe(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            try {
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Linked or special storage paths are unsupported: " + current);
            } catch (NoSuchFileException missing) { return; }
        }
    }
    static void business(StorageScope scope, boolean required) throws IOException {
        safe(scope.directory());
        if (required && !Files.isDirectory(scope.directory(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Registered profile directory is missing: " + scope.directory());
        for (String file : new String[]{"inventory.json", "inventory.json.lock", "sessions", "sessions/sessions.json", "sessions/sessions.lock"})
            safe(scope.directory().resolve(file));
    }
    static void tree(Path directory) throws IOException {
        safe(directory);
        try (var paths = Files.walk(directory)) {
            var iterator = paths.iterator(); int count = 0;
            while (iterator.hasNext()) {
                if (++count > 10000) throw new IOException("Profile tree is too large to validate safely.");
                safe(iterator.next());
            }
        }
    }
}
