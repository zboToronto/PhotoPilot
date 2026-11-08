package com.photopilot.ui.gui;

import com.photopilot.config.StorageConfiguration;
import com.photopilot.repository.JsonLocalePreferenceRepository;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Application-wide appearance: the user's theme colour, appearance mode and surface opacity, kept in the existing
 * preferences.json beside the language and applied live to every open window by swapping one generated token sheet.
 * Changing appearance never rebuilds views, reloads data or touches workspace, AI or photo state.
 */
final class ThemeManager {
    static final String ACCENT_KEY = "appearance.accent", MODE_KEY = "appearance.mode", OPACITY_KEY = "appearance.opacity",
        HUE_KEY = "appearance.customHue";
    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String BASE_SHEET = Objects.requireNonNull(PhotoPilotGUI.class.getResource("photopilot.css")).toExternalForm();
    private static final String SHEET_PREFIX = "data:text/css;base64,";
    private static final long SYSTEM_POLL_SECONDS = 15, FOCUS_RECHECK_MILLIS = 2000;

    private Theme.Accent accent = Theme.DEFAULT.accent();
    private volatile Theme.Mode mode = Theme.DEFAULT.mode();
    private int opacity = Theme.DEFAULT_OPACITY;
    // The custom hue is remembered separately, so picking a preset and then Custom again restores it.
    private boolean custom;
    private int customHue = Theme.DEFAULT_CUSTOM_HUE;
    private Theme theme = Theme.DEFAULT;
    private String sheet = theme.stylesheetUrl();
    private JsonLocalePreferenceRepository store;
    // pending: a slider value previewed live but not saved yet (saved when the drag ends, after a pause, or on shutdown).
    private boolean readFailure, saveFailure, followSystem, tracking, applyPending, pending;
    private volatile Boolean systemDark;
    private volatile boolean systemChecked;
    private volatile long lastSystemCheck;
    private ScheduledExecutorService system;
    private final List<WeakReference<Runnable>> listeners = new ArrayList<>();
    private final List<WeakReference<ObservableList<String>>> sheetLists = new ArrayList<>();
    private final Map<Window, Boolean> windows = new WeakHashMap<>();
    private final Map<Scene, Boolean> scenes = new WeakHashMap<>();

    /** Package-private for isolated persistence tests; the application uses the shared instance. */
    ThemeManager() { }
    static ThemeManager get() { return INSTANCE; }

    /**
     * Normal GUI startup only: restores the saved appearance and enables Follow System detection. Without this call
     * (tests, embedded panes) appearance stays in memory and Follow System falls back to Light.
     */
    void initialize(Map<String, String> environment) {
        try { open(StorageConfiguration.resolve(environment).configurationDirectory()); }
        catch (RuntimeException unreadable) { readFailure = true; }
        followSystem = true;
        if (mode == Theme.Mode.SYSTEM) { systemDark = detectSystemDark().orElse(null); systemChecked = true; lastSystemCheck = System.nanoTime(); }
        system = Executors.newSingleThreadScheduledExecutor(runnable -> { var thread = new Thread(runnable, "photopilot-appearance"); thread.setDaemon(true); return thread; });
        system.scheduleWithFixedDelay(() -> { if (mode == Theme.Mode.SYSTEM) checkSystem(); }, SYSTEM_POLL_SECONDS, SYSTEM_POLL_SECONDS, TimeUnit.SECONDS);
        update();
    }
    /** Reads appearance from preferences.json in the configuration directory; nothing is written until a change. */
    void open(java.nio.file.Path configuration) {
        store = new JsonLocalePreferenceRepository(configuration.resolve("preferences.json"), configuration.resolve("settings.properties"));
        try { restore(store.values()); readFailure = false; }
        catch (IOException | RuntimeException unreadable) { readFailure = true; }
        update();
    }
    /** Normal shutdown: a slider value still waiting for its save is written first. */
    void shutdown() { flush(); if (system != null) system.shutdownNow(); system = null; }
    private void restore(Map<String, String> values) {
        String accentId = values.get(ACCENT_KEY);
        if (Theme.Accent.CUSTOM_ID.equals(accentId)) custom = true;
        else Theme.Accent.parse(accentId).ifPresent(value -> accent = value);
        Theme.Mode.parse(values.get(MODE_KEY)).ifPresent(value -> mode = value);
        // Invalid numbers keep the defaults; the stored value is left untouched until the next save.
        try { if (values.containsKey(OPACITY_KEY)) opacity = Math.max(0, Math.min(100, Integer.parseInt(values.get(OPACITY_KEY).strip()))); }
        catch (NumberFormatException invalid) { /* default opacity */ }
        try { if (values.containsKey(HUE_KEY)) customHue = Math.floorMod(Integer.parseInt(values.get(HUE_KEY).strip()), 360); }
        catch (NumberFormatException invalid) { /* default custom hue */ }
    }

    Theme theme() { return theme; }
    Theme.Accent accent() { return accent; }
    /** True when the Custom colour (not a preset) is selected. */
    boolean custom() { return custom; }
    int customHue() { return customHue; }
    Theme.Mode mode() { return mode; }
    int opacity() { return opacity; }
    boolean readFailure() { return readFailure; }
    boolean saveFailure() { return saveFailure; }
    /** True when Follow System is selected but the operating system's choice could not be read (Light is used). */
    boolean systemUnavailable() { return followSystem && mode == Theme.Mode.SYSTEM && systemChecked && systemDark == null; }

    /** Each change applies immediately and is saved automatically; it stays applied even if saving fails. */
    boolean select(Theme.Accent value) { accent = Objects.requireNonNull(value); custom = false; update(); return save(); }
    /** Selects the Custom colour with the remembered hue. */
    boolean selectCustom() { custom = true; update(); return save(); }
    boolean select(Theme.Mode value) {
        mode = Objects.requireNonNull(value);
        if (mode == Theme.Mode.SYSTEM && followSystem) checkSystemAsync(true);
        update(); return save();
    }
    /** Live slider preview; persisted by {@link #save()} when the drag ends or the value settles. */
    void previewOpacity(int value) {
        int next = Math.max(0, Math.min(100, value));
        if (next != opacity) { opacity = next; pending = true; }
        update();
    }
    /** Live custom hue preview (selects Custom); persisted like {@link #previewOpacity(int)}. */
    void previewCustomHue(int value) {
        int next = Math.floorMod(value, 360);
        if (next != customHue || !custom) { customHue = next; custom = true; pending = true; }
        update();
    }
    boolean save() {
        if (store == null) { pending = false; return true; }
        try {
            store.merge(Map.of(ACCENT_KEY, custom ? Theme.Accent.CUSTOM_ID : accent.id, MODE_KEY, mode.id,
                OPACITY_KEY, Integer.toString(opacity), HUE_KEY, Integer.toString(customHue)));
            saveFailure = false; readFailure = false; pending = false;
        } catch (IOException | RuntimeException failed) { saveFailure = true; }
        return !saveFailure;
    }
    /** Saves a previewed slider value that has not been saved yet; true when nothing is left unsaved. */
    boolean flush() { return !pending || save(); }
    boolean pending() { return pending; }

    /** Weakly held: the caller keeps a strong reference for as long as it wants updates, so views never leak. */
    void listen(Runnable listener) { listeners.add(new WeakReference<>(Objects.requireNonNull(listener))); }

    private void update() {
        boolean dark = mode == Theme.Mode.DARK || mode == Theme.Mode.SYSTEM && Boolean.TRUE.equals(systemDark);
        var next = custom ? Theme.custom(customHue, mode, dark, opacity) : new Theme(accent, mode, dark, opacity);
        if (next.equals(theme)) return;
        theme = next; sheet = next.stylesheetUrl();
        scheduleApply();
        notifyListeners();
    }
    private void notifyListeners() {
        listeners.removeIf(reference -> reference.get() == null);
        for (var reference : List.copyOf(listeners)) {
            var listener = reference.get();
            if (listener != null) listener.run();
        }
    }

    /**
     * Called wherever photopilot.css is attached (scene or dialog pane): places the current token sheet right away,
     * so even the first frame of a dialog uses the chosen appearance, and keeps that list current afterwards.
     */
    void style(ObservableList<String> sheets) {
        place(sheets);
        sheetLists.removeIf(reference -> reference.get() == null);
        if (sheetLists.stream().noneMatch(reference -> reference.get() == sheets)) sheetLists.add(new WeakReference<>(sheets));
    }

    /** Starts following every window, so the main window, chooser, dialogs and menus all share the current sheet. */
    void track() {
        if (tracking || !Platform.isFxApplicationThread()) return;
        tracking = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) for (Window window : change.getAddedSubList()) attach(window);
        });
        Window.getWindows().forEach(this::attach);
    }
    private void attach(Window window) {
        if (windows.put(window, Boolean.TRUE) == null) {
            window.sceneProperty().addListener((property, old, scene) -> attach(scene));
            if (window instanceof Stage) window.focusedProperty().addListener((property, old, focused) -> {
                if (focused && mode == Theme.Mode.SYSTEM && followSystem) checkSystemAsync(false);
            });
        }
        attach(window.getScene());
    }
    private void attach(Scene scene) {
        if (scene == null) return;
        if (scenes.put(scene, Boolean.TRUE) == null) scene.rootProperty().addListener((property, old, root) -> applyTo(scene));
        applyTo(scene);
    }
    /** Coalesces slider drags to one stylesheet swap (and one CSS pass) per pulse. */
    private void scheduleApply() {
        if (applyPending || !tracking && sheetLists.isEmpty()) return;
        applyPending = true;
        Platform.runLater(() -> {
            applyPending = false;
            new ArrayList<>(scenes.keySet()).forEach(this::applyTo);
            sheetLists.removeIf(reference -> reference.get() == null);
            for (var reference : List.copyOf(sheetLists)) { var sheets = reference.get(); if (sheets != null) place(sheets); }
        });
    }
    private void applyTo(Scene scene) {
        place(scene.getStylesheets());
        if (scene.getRoot() != null) place(scene.getRoot().getStylesheets());
    }
    /** The token sheet sits directly after photopilot.css wherever that file is loaded (scene or dialog pane). */
    private void place(ObservableList<String> sheets) {
        int base = sheets.indexOf(BASE_SHEET), current = -1;
        for (int i = 0; i < sheets.size(); i++) if (sheets.get(i).startsWith(SHEET_PREFIX)) { current = i; break; }
        if (base < 0) { if (current >= 0) sheets.remove(current); return; }
        if (current == base + 1) { if (!sheets.get(current).equals(sheet)) sheets.set(current, sheet); return; }
        if (current >= 0) sheets.remove(current);
        sheets.add(sheets.indexOf(BASE_SHEET) + 1, sheet);
    }

    /** Test seam: the operating system reports this appearance (null: unknown), exactly as the background poll applies it. */
    void systemReported(Boolean dark) { systemChecked = true; systemDark = dark; update(); notifyListeners(); }
    private void checkSystemAsync(boolean force) {
        var executor = system;
        if (executor == null || !force && System.nanoTime() - lastSystemCheck < TimeUnit.MILLISECONDS.toNanos(FOCUS_RECHECK_MILLIS)) return;
        lastSystemCheck = System.nanoTime();
        try { executor.execute(this::checkSystem); } catch (RejectedExecutionException closed) { /* application is stopping */ }
    }
    private void checkSystem() {
        lastSystemCheck = System.nanoTime();
        Boolean detected = detectSystemDark().orElse(null);
        boolean first = !systemChecked;
        systemChecked = true;
        if (Objects.equals(detected, systemDark) && !first) return;
        systemDark = detected;
        // update() recolours when the effective mode changed; listeners also refresh the "unavailable" note.
        Platform.runLater(() -> { update(); notifyListeners(); });
    }
    /** JavaFX 21 exposes no platform appearance, so the OS setting is read directly; empty when unavailable. */
    static Optional<Boolean> detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                var match = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").matcher(run("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"));
                return match.find() ? Optional.of(Long.parseLong(match.group(1), 16) == 0) : Optional.empty();
            }
            if (os.contains("mac")) return Optional.of(run("defaults", "read", "-g", "AppleInterfaceStyle").toLowerCase(Locale.ROOT).contains("dark"));
            String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            return scheme.isBlank() ? Optional.empty() : Optional.of(scheme.contains("dark"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } catch (IOException | RuntimeException unavailable) { return Optional.empty(); }
    }
    private static String run(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(2, TimeUnit.SECONDS)) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally { process.destroy(); }
    }
}
