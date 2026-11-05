package com.photopilot.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Local, offline key configuration. Never logs, persists, or verifies credentials with a provider. */
public final class LLMConfiguration {
    public static final String API_KEY_VARIABLE = "LLM_API_KEY";
    public enum KeyStatus { CONFIGURED, MISSING, PLACEHOLDER, INVALID, UNREADABLE_FILE }

    private final String apiKey;
    private final KeyStatus keyStatus;
    private final Optional<Path> envFile;

    private LLMConfiguration(String apiKey, KeyStatus keyStatus) {
        this(apiKey, keyStatus, Optional.empty());
    }
    private LLMConfiguration(String apiKey, KeyStatus keyStatus, Optional<Path> envFile) {
        this.apiKey = apiKey;
        this.keyStatus = keyStatus;
        this.envFile = envFile;
    }

    public static LLMConfiguration unconfigured() {
        return new LLMConfiguration("", KeyStatus.MISSING);
    }

    /** Both GUI and CLI supply System.getenv() through their shared bootstrap. */
    public static LLMConfiguration load(Map<String, String> environment) {
        return load(environment, projectRoot(Path.of("")));
    }

    /** Explicit root supports embedding/tests without changing global environment or user.dir. */
    public static LLMConfiguration load(Map<String, String> environment, Path projectRoot) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(projectRoot, "projectRoot");
        String systemKey = environment.get(API_KEY_VARIABLE);
        // A nonblank environment value always wins, even if invalid; never silently switch identities.
        if (systemKey != null && !systemKey.isBlank()) return validate(systemKey);
        Path file = projectRoot.resolve(".env").toAbsolutePath().normalize();
        var loaded = fromFile(file);
        return new LLMConfiguration(loaded.apiKey, loaded.keyStatus, Optional.of(file));
    }

    private static LLMConfiguration fromFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            String key = null;
            for (String line : content.split("\\R")) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) continue;
                int equals = entry.indexOf('=');
                String name = (equals < 0 ? entry : entry.substring(0, equals)).strip();
                if (!name.equals(API_KEY_VARIABLE)) continue;
                if (equals < 0 || key != null) return invalid();
                key = entry.substring(equals + 1).strip();
                if (key.startsWith("\"") || key.startsWith("'")) {
                    if (key.length() < 2 || key.charAt(key.length() - 1) != key.charAt(0)) return invalid();
                    key = key.substring(1, key.length() - 1);
                }
            }
            return key == null ? unconfigured() : validate(key);
        } catch (NoSuchFileException missing) {
            return unconfigured();
        } catch (IOException | SecurityException unreadable) {
            // Do not retain exception messages/causes that might expose file content or private paths.
            return new LLMConfiguration("", KeyStatus.UNREADABLE_FILE);
        }
    }

    /** The repository root owns .env; Stage2 is the documented Maven working directory. */
    static Path projectRoot(Path workingDirectory) {
        Path directory = workingDirectory.toAbsolutePath().normalize();
        if (directory.getFileName() != null && directory.getFileName().toString().equals("Stage2")
                && Files.isRegularFile(directory.resolve("pom.xml"))) {
            return directory.getParent();
        }
        return directory;
    }

    private static LLMConfiguration validate(String value) {
        String key = value.strip();
        if (key.isEmpty()) return unconfigured();
        if (key.equalsIgnoreCase("YOUR_LOCAL_API_KEY_HERE")) {
            return new LLMConfiguration("", KeyStatus.PLACEHOLDER);
        }
        // Local syntax only: no provider-specific prefix/length assumption or network validation.
        // '.' is allowed because newer Google API keys contain one.
        if (!key.matches("[A-Za-z0-9._-]+")) return invalid();
        return new LLMConfiguration(key, KeyStatus.CONFIGURED);
    }

    private static LLMConfiguration invalid() { return new LLMConfiguration("", KeyStatus.INVALID); }

    public KeyStatus keyStatus() { return keyStatus; }
    public boolean isApiKeyConfigured() { return keyStatus == KeyStatus.CONFIGURED; }
    /**
     * The .env file consulted because no LLM_API_KEY environment variable was set; empty when the variable decided.
     * Its location only (it follows the launch directory), never its content.
     */
    public Optional<Path> envFile() { return envFile; }

    /** Future transport boundary only. Never display, log, serialize, or put the result in a URI. */
    public String requireApiKey() {
        if (!isApiKeyConfigured()) {
            throw new IllegalStateException("LLM_API_KEY is not configured: " + keyStatus);
        }
        return apiKey;
    }

    @Override public String toString() {
        return "LLMConfiguration[keyStatus=" + keyStatus + ", apiKey=<redacted>]";
    }
}
