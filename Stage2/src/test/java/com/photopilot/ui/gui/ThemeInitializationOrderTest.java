package com.photopilot.ui.gui;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Theme's static initialization in a fresh JVM, reached in every order a caller can reach it. Each scenario runs in its
 * own child JVM, because a class initialized (or failed) earlier in the shared test JVM hides an order dependency. The
 * child makes the scenario's first access, then builds the complete palette (every accent, the default theme, every
 * preset and representative custom themes in both modes at several transparencies, the hue spectrum) and prints its
 * SHA-256; every order must give the same palette as this JVM. Found 2026-11-24 (Stage2OptRe2 remediation): an Accent
 * constant called Theme's static contrast helpers while Theme.DEFAULT needed Accent.BLUE, so touching Accent (or a
 * custom Family) first failed Theme's initialization for the rest of the JVM.
 */
class ThemeInitializationOrderTest {
    @TempDir Path root;
    private static final int[] CUSTOM_HUES = { 0, 30, 60, 110, 150, 200, 224, 262, 300, 359 };
    private static final int[] OPACITIES = { 0, 40, 62, 100 };

    /** Child-JVM entry: one scenario, then the palette check; one result line (or the failure with its stack trace). */
    public static void main(String[] args) {
        String scenario = args[0];
        try {
            String detail = run(scenario, args);
            System.out.println("THEME_ORDER_OK " + scenario + " palette=" + palette() + (detail.isEmpty() ? "" : " " + detail));
            System.out.flush();
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("THEME_ORDER_FAILED " + scenario);
            failure.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static String run(String scenario, String[] args) throws Exception {
        switch (scenario) {
            case "theme-then-accent" -> {
                check(Theme.DEFAULT.accent() == Theme.Accent.BLUE, "default accent");
                check(Theme.Accent.values().length == 5, "five accents");
            }
            case "accent-then-theme" -> {
                var blue = Theme.Accent.BLUE;
                check(blue.family != null && blue.lightFill != null, "Accent.BLUE is complete");
                check(Theme.DEFAULT.accent() == blue, "Theme.DEFAULT uses Accent.BLUE");
            }
            case "accent-constant" -> {
                Color fill = Theme.Accent.TEAL.lightFill;
                check(fill != null && fill.equals(Theme.Accent.TEAL.family.lightFill()), "Accent.TEAL fields");
            }
            case "accent-values" -> check(Arrays.stream(Theme.Accent.values()).map(accent -> accent.id).toList()
                .equals(List.of("blue", "sage", "peach", "rose", "teal")), "accent order and ids");
            case "accent-parse" -> {
                // The preference reader's first access: stored ids, including the merged Lavender choice.
                check(Theme.Accent.parse("lavender").orElseThrow() == Theme.Accent.BLUE, "lavender opens as Cool Blue");
                check(Theme.Accent.parse("teal").orElseThrow() == Theme.Accent.TEAL && Theme.Accent.parse("neon").isEmpty(), "stored ids");
            }
            case "family-first" -> {
                var family = Theme.Family.custom(Theme.DEFAULT_CUSTOM_HUE);
                check(family == Theme.Family.custom(262) && family.lightFill() != null, "generated family is cached");
            }
            case "mode-first" -> check(Theme.Mode.parse("dark").orElseThrow() == Theme.Mode.DARK, "mode ids");
            case "custom-first" -> check(Theme.custom(190, Theme.Mode.DARK, true, 25).tokens().size() > 40, "custom theme tokens");
            case "manager-after-accent" -> {
                check(Theme.Accent.SAGE.id.equals("sage"), "Accent.SAGE");
                check(new ThemeManager().theme().equals(Theme.DEFAULT) && ThemeManager.get().theme().equals(Theme.DEFAULT), "ThemeManager defaults");
            }
            case "manager-first" -> {
                check(ThemeManager.get().theme().equals(Theme.DEFAULT), "ThemeManager defaults");
                check(Theme.Accent.values().length == 5, "five accents");
            }
            case "preferences" -> {
                // A saved appearance read after Accent was touched first, as a preference deserializer would.
                check(Theme.Accent.parse("rose").isPresent(), "stored id");
                var manager = new ThemeManager(); manager.open(Path.of(args[1]));
                check(!manager.readFailure(), "preferences readable");
                return "accent=" + (manager.custom() ? Theme.Accent.CUSTOM_ID : manager.accent().id) + " hue=" + manager.customHue()
                    + " mode=" + manager.mode().id + " opacity=" + manager.opacity() + " font=" + manager.fontSize() + " bold=" + manager.boldText()
                    + " sheet=" + sha256(manager.theme().stylesheet());
            }
            case "repeated" -> {
                check(Theme.Accent.ROSE.family != null, "Accent.ROSE");
                String first = palette();
                for (int i = 0; i < 25; i++) {
                    check(first.equals(palette()), "the palette is the same on access " + i);
                    check(Theme.Family.custom(200) == Theme.Family.custom(200) && Theme.DEFAULT == Theme.DEFAULT, "cached values stay the same objects");
                }
                return "accesses=26";
            }
            case "concurrent" -> { return concurrent(); }
            case "gui-normal", "gui-accent-first" -> {
                if (scenario.equals("gui-accent-first")) check(Theme.Accent.values().length == 5, "five accents");
                // Normal application startup (smoke mode) in this JVM; returns when the application has exited.
                javafx.application.Application.launch(PhotoPilotGUI.class, "--smoke-test");
            }
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        return "";
    }
    /** First access from six threads released together; a class-initialization deadlock shows as threads that never finish. */
    private static String concurrent() throws Exception {
        List<Callable<Object>> firsts = List.of(() -> Theme.DEFAULT, () -> Theme.Accent.BLUE, () -> Theme.Accent.values(),
            () -> Theme.Family.custom(262), () -> ThemeManager.get().theme(), () -> Theme.custom(150, Theme.Mode.LIGHT, false, 0).tokens());
        var start = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < firsts.size(); i++) {
            var first = firsts.get(i);
            var thread = new Thread(() -> {
                try { start.await(); Objects.requireNonNull(first.call()); } catch (Throwable failure) { failures.add(failure); }
            }, "theme-first-access-" + i);
            thread.setDaemon(true); thread.start(); threads.add(thread);
        }
        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (var thread : threads) thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        var stuck = threads.stream().filter(Thread::isAlive).toList();
        if (!stuck.isEmpty()) {
            var dump = new StringBuilder("class initialization deadlock: " + stuck.size() + " threads never finished");
            for (var thread : stuck) { dump.append("\n").append(thread.getName()).append(" ").append(thread.getState());
                for (var frame : thread.getStackTrace()) dump.append("\n    at ").append(frame); }
            throw new AssertionError(dump.toString());
        }
        if (!failures.isEmpty()) { var failure = new AssertionError(failures.size() + " threads failed"); failures.forEach(failure::addSuppressed); throw failure; }
        return "threads=" + threads.size();
    }
    private static void check(boolean condition, String what) { if (!condition) throw new AssertionError(what); }

    /** SHA-256 of every palette value: accents, generated families, default theme, spectrum and complete token sheets. */
    static String palette() {
        var text = new StringBuilder();
        for (Theme.Accent accent : Theme.Accent.values()) {
            text.append(accent.id).append(' ').append(accent.key).append(' ').append(accent.hue).append(' ').append(accent.secondHue).append(' ').append(accent.chroma);
            for (Color c : List.of(accent.swatch, accent.lightFill, accent.lightOnFill, accent.lightText, accent.darkFill, accent.darkOnFill, accent.darkText))
                text.append(' ').append(exact(c));
            text.append(' ').append(family(accent.family)).append('\n');
        }
        for (int hue : CUSTOM_HUES) text.append(hue).append(' ').append(family(Theme.Family.custom(hue))).append('\n');
        text.append(Theme.HUE_SPECTRUM).append('\n').append(Theme.DEFAULT).append('\n').append(Theme.DEFAULT.stylesheet());
        for (int opacity : OPACITIES) for (Theme.Mode mode : Theme.Mode.values()) for (boolean dark : new boolean[] { false, true }) {
            for (Theme.Accent accent : Theme.Accent.values()) text.append(new Theme(accent, mode, dark, opacity).stylesheet());
            for (int hue : CUSTOM_HUES) text.append(Theme.custom(hue, mode, dark, opacity).stylesheet());
        }
        return sha256(text.toString());
    }
    private static String family(Theme.Family f) {
        var text = new StringBuilder().append(f.hue()).append(' ').append(f.secondHue()).append(' ').append(f.chroma());
        for (Color c : List.of(f.swatch(), f.lightFill(), f.lightOnFill(), f.lightText(), f.darkFill(), f.darkOnFill(), f.darkText())) text.append(' ').append(exact(c));
        return text.toString();
    }
    private static String exact(Color c) { return c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + c.getOpacity(); }
    static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- parent side ---------------------------------------------------------------------------------------------------
    record Child(int exit, String output) { }

    private Child child(Map<String, String> environment, long seconds, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"), ThemeInitializationOrderTest.class.getName()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().remove("LLM_API_KEY"); builder.environment().remove("GEMINI_API_KEY");
        builder.environment().putAll(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(),
            "PHOTOPILOT_LIVE_GEMINI", "0"));
        builder.environment().putAll(environment);
        Path log = Files.createTempFile(root, "child-", ".log");
        var process = builder.redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(seconds, TimeUnit.SECONDS);
            if (!exited) {
                // A deadlocked child: ask the JVM for its thread dump before it is stopped, so the report shows where.
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(), Long.toString(process.pid()), "Thread.print")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start().waitFor(20, TimeUnit.SECONDS);
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            System.out.println("[child " + String.join(" ", args) + "] " + (exited ? "exit " + process.exitValue() : "did not finish in " + seconds + " s") + "\n" + output.strip());
            return new Child(exited ? process.exitValue() : -1, exited ? output : "did not finish in " + seconds + " s (deadlock?)\n" + output);
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }

    /**
     * The palette as the application builds it (Theme first, as PhotoPilotGUI's startup reaches it), from its own fresh
     * JVM, so the reference never depends on what this shared test JVM happened to initialize first.
     */
    private static String reference;
    private String reference() throws Exception {
        if (reference == null) {
            var result = child(Map.of(), 60, "theme-then-accent");
            assertEquals(0, result.exit(), "the application's own order:\n" + result.output());
            var line = result.output().lines().filter(l -> l.startsWith("THEME_ORDER_OK theme-then-accent palette=")).findFirst().orElseThrow();
            reference = line.substring(line.indexOf('=') + 1).strip();
        }
        return reference;
    }

    @ParameterizedTest(name = "fresh JVM, first access: {0}")
    @ValueSource(strings = { "theme-then-accent", "accent-then-theme", "accent-constant", "accent-values", "accent-parse", "family-first",
        "mode-first", "custom-first", "manager-after-accent", "manager-first", "repeated" })
    void everyFirstAccessOrderInitializesTheSamePalette(String scenario) throws Exception {
        var result = child(Map.of(), 60, scenario);
        assertEquals(0, result.exit(), scenario + ":\n" + result.output());
        assertTrue(result.output().contains("THEME_ORDER_OK " + scenario + " palette=" + reference()), scenario + " palette differs from the application order's:\n" + result.output());
    }

    @Test void thisTestJvmBuildsTheSamePaletteWhateverItInitializedFirst() throws Exception {
        assertEquals(reference(), palette(), "the shared test JVM's palette (its classes were initialized by whichever test ran first)");
    }

    @Test void concurrentFirstAccessFromSeveralThreadsNeitherDeadlocksNorFails() throws Exception {
        // Three fresh JVMs: the threads race for the first initialization differently each time.
        for (int run = 1; run <= 3; run++) {
            var result = child(Map.of(), 60, "concurrent");
            assertEquals(0, result.exit(), "run " + run + ":\n" + result.output());
            assertTrue(result.output().contains("THEME_ORDER_OK concurrent palette=" + reference() + " threads=6"), "run " + run + ":\n" + result.output());
        }
    }

    @Test void savedAppearancePreferencesLoadAfterAccentWasTouchedFirst() throws Exception {
        Path custom = Files.createDirectories(root.resolve("prefs-custom")), legacy = Files.createDirectories(root.resolve("prefs-legacy"));
        String customJson = "{\"schemaVersion\":1,\"values\":{\"language\":\"zh-CN\",\"appearance.accent\":\"custom\",\"appearance.customHue\":\"190\","
            + "\"appearance.mode\":\"dark\",\"appearance.opacity\":\"25\",\"appearance.fontSize\":\"110\",\"appearance.boldText\":\"true\"}}";
        String legacyJson = "{\"schemaVersion\":1,\"values\":{\"language\":\"en\",\"appearance.accent\":\"lavender\",\"appearance.mode\":\"light\",\"appearance.opacity\":\"40\"}}";
        Files.writeString(custom.resolve("preferences.json"), customJson); Files.writeString(legacy.resolve("preferences.json"), legacyJson);
        var expected = new ThemeManager(); expected.open(custom);
        var restored = child(Map.of(), 60, "preferences", custom.toString());
        assertEquals(0, restored.exit(), restored.output());
        assertTrue(restored.output().contains("accent=custom hue=190 mode=dark opacity=25 font=110 bold=true sheet=" + sha256(expected.theme().stylesheet())), restored.output());
        var older = child(Map.of(), 60, "preferences", legacy.toString());
        assertEquals(0, older.exit(), older.output());
        assertTrue(older.output().contains("accent=blue hue=" + Theme.DEFAULT_CUSTOM_HUE + " mode=light opacity=40 font=100 bold=false sheet="
            + sha256(new Theme(Theme.Accent.BLUE, Theme.Mode.LIGHT, false, 40).stylesheet())), "a saved Lavender choice opens as Cool Blue:\n" + older.output());
        assertEquals(customJson, Files.readString(custom.resolve("preferences.json")), "reading never rewrites the file");
        assertEquals(legacyJson, Files.readString(legacy.resolve("preferences.json")), "reading never rewrites the file");
    }

    @Test void normalApplicationStartupSucceedsInEitherOrder() throws Exception {
        for (String scenario : List.of("gui-normal", "gui-accent-first")) {
            var result = child(Map.of(), 180, scenario);
            assertEquals(0, result.exit(), scenario + ":\n" + result.output());
            assertTrue(result.output().contains("GUI_SMOKE_OK"), scenario + ":\n" + result.output());
            assertTrue(result.output().contains("THEME_ORDER_OK " + scenario + " palette=" + reference()), scenario + ":\n" + result.output());
        }
    }
}
