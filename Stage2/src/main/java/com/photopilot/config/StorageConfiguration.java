package com.photopilot.config;

import com.photopilot.repository.StorageScope;
import java.nio.file.*;
import java.util.*;

/** Central path policy; default paths are independent of launch directory and create no files. */
public record StorageConfiguration(StorageScope scope, Path configurationDirectory, Optional<Path> legacyDirectory) {
    public static StorageConfiguration resolve(Map<String, String> environment) {
        return resolve(environment, Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath());
    }
    public static StorageConfiguration resolve(Map<String, String> environment, Path home, Path workingDirectory) {
        Path base = home.toAbsolutePath().normalize().resolve(".photopilot");
        Path data = configured(environment, "PHOTOPILOT_DATA_DIR", base.resolve("data"), workingDirectory);
        Path config = configured(environment, "PHOTOPILOT_CONFIG_DIR", base, workingDirectory);
        Path legacy = workingDirectory.toAbsolutePath().normalize().resolve("data");
        boolean oldData = !environment.containsKey("PHOTOPILOT_DATA_DIR") && !data.equals(legacy)
            && (Files.exists(legacy.resolve("inventory.json")) || Files.exists(legacy.resolve("sessions")));
        return new StorageConfiguration(StorageScope.legacy(data), config, oldData ? Optional.of(legacy) : Optional.empty());
    }
    private static Path configured(Map<String, String> env, String key, Path fallback, Path cwd) {
        if (!env.containsKey(key)) return fallback;
        String value = Objects.requireNonNull(env.get(key), key);
        if (value.isBlank()) throw new IllegalArgumentException(key + " must not be blank.");
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : cwd.resolve(path)).toAbsolutePath().normalize();
    }
}
