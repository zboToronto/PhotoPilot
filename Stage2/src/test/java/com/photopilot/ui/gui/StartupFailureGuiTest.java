package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GUI startup failures end with the startup error (smoke mode: the recorded failure) instead of an exception inside a
 * JavaFX handler, which would leave the splash open (its close request is consumed until startup finishes).
 */
class StartupFailureGuiTest {
    @TempDir Path root;

    /** Child-JVM entry: the java launcher's JavaFX-runtime check applies only to a main class that is itself an Application. */
    public static void main(String[] args) { javafx.application.Application.launch(PhotoPilotGUI.class, args); }

    /** The real startup in a child JVM (smoke mode, isolated storage, no API key); the CLI reports the same value as a configuration error. */
    @Test void invalidImportLimitEndsStartupInsteadOfLeavingTheSplashOpen() throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"), StartupFailureGuiTest.class.getName(), "--smoke-test");
        builder.environment().remove("LLM_API_KEY");
        builder.environment().putAll(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(),
            "PHOTOPILOT_LIVE_GEMINI", "0", "PHOTOPILOT_IMPORT_MAX_MB", "lots"));
        Path log = root.resolve("gui.log");
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.ISO_8859_1);
            assertTrue(exited, () -> "startup must end instead of leaving the splash open:\n" + output);
            assertNotEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PHOTOPILOT_IMPORT_MAX_MB must be a whole number from 1 to 100"), output);
            assertFalse(output.contains("GUI_SMOKE_OK"), output);
            assertFalse(Files.exists(root.resolve("data")), "no storage is opened for an invalid configuration");
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }

    /** Profile recovery: the chosen identity opened, but reading it fails (registry damaged or changed by another process). */
    @Test void recoveredUserThatCannotBeReadIsAStartupFailure() throws Exception {
        var env = Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
        try (var profiles = new ProfileManager(env)) {
            Files.writeString(root.resolve("data").resolve("profiles.json"), "{");
            assertFalse(profiles.activeProfile().isSuccess());
            assertThrows(NoSuchElementException.class, () -> profiles.activeProfile().value().orElseThrow(),
                "what the recovery branch evaluated before: not caught by its IllegalArgumentException handler");
            var failure = assertThrows(IllegalArgumentException.class, () -> PhotoPilotGUI.activeProfile(profiles));
            assertFalse(failure.getMessage().isBlank());
        }
    }
}
