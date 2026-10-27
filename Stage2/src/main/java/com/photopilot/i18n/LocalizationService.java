package com.photopilot.i18n;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/** Per-application localization facade. No global locale changes and no UI dependency. */
public final class LocalizationService {
    private static final Logger LOG = Logger.getLogger(LocalizationService.class.getName());
    private static final ResourceBundle.Control UTF8 = new ResourceBundle.Control() {
        @Override public Locale getFallbackLocale(String baseName, Locale locale) { return null; }
        @Override public List<String> getFormats(String baseName) { return FORMAT_PROPERTIES; }
        @Override public ResourceBundle newBundle(String base, Locale locale, String format,
                ClassLoader loader, boolean reload) throws IOException {
            String name = toResourceName(toBundleName(base, locale), "properties");
            try (InputStream input = loader.getResourceAsStream(name)) {
                return input == null ? null : new PropertyResourceBundle(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
    };
    private final LocalePreferenceRepository preferences;
    private final Map<Language, ResourceBundle> bundles = new EnumMap<>(Language.class);
    private final Map<Language, Set<String>> ownKeys = new EnumMap<>(Language.class);
    private final ResourceBundle english;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Language language = Language.ENGLISH;
    private volatile boolean preferenceFailure;
    private volatile String preferenceFailureKey = "settings.readFailed";

    public LocalizationService(LocalePreferenceRepository preferences) { this(preferences, "i18n.messages"); }
    public LocalizationService(LocalePreferenceRepository preferences, String baseName) {
        this.preferences = Objects.requireNonNull(preferences);
        english = ResourceBundle.getBundle(baseName, Locale.ROOT, UTF8);
        for (Language supported : Language.values()) {
            bundles.put(supported, ResourceBundle.getBundle(baseName, supported.locale(), UTF8));
            try {
                ResourceBundle direct = UTF8.newBundle(baseName, supported.locale(), "java.properties", getClass().getClassLoader(), false);
                ownKeys.put(supported, direct == null ? Set.of() : direct.keySet());
            } catch (IOException error) { throw new UncheckedIOException("Cannot load localization resources", error); }
            catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
        }
        try {
            String tag = preferences.load(); language = Language.fromTag(tag);
            if (tag != null && !Language.supports(tag)) diagnose("preference.invalid", "Unsupported language preference; using English");
        }
        catch (IOException | RuntimeException error) { preferenceFailure = true; diagnose("preference.read", error.getClass().getSimpleName()); }
    }
    public static LocalizationService english() { return new LocalizationService(LocalePreferenceRepository.transientPreference()); }
    public Language language() { return language; }
    public boolean preferenceFailure() { return preferenceFailure; }
    public String preferenceFailureKey() { return preferenceFailureKey; }
    /** Explicit override affects this service only. It never writes preferences. */
    public synchronized void useLanguage(String tag) { change(Language.fromTag(tag)); }
    /** Applies immediately even if saving fails; callers display the failure and can retry. */
    public synchronized boolean selectLanguage(String tag) {
        Language selected = Language.fromTag(tag);
        try { preferences.save(selected.tag()); preferenceFailure = false; }
        catch (IOException | RuntimeException error) { preferenceFailureKey = "settings.saveFailed"; preferenceFailure = true; diagnose("preference.write", error.getClass().getSimpleName()); }
        change(selected);
        return !preferenceFailure;
    }
    private void change(Language selected) {
        language = selected;
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException error) { diagnose("listener", error.getClass().getSimpleName()); }
        }
    }
    public String text(String key, Object... arguments) { return text(language, key, arguments); }
    public String text(Language requested, String key, Object... arguments) {
        ResourceBundle bundle = bundles.get(requested);
        String pattern;
        if (requested != Language.ENGLISH && !ownKeys.get(requested).contains(key) && english.containsKey(key)) {
            diagnose("fallback." + requested.tag() + "." + key, "Using English translation: " + key);
        }
        if (bundle.containsKey(key)) pattern = bundle.getString(key);
        else if (english.containsKey(key)) pattern = english.getString(key);
        else { diagnose("missing." + key, "Missing translation: " + key); return "[!" + key + "!]"; }
        Object[] values = arguments.clone();
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof LocalizedMessage message) values[i] = message.render(this, requested);
        }
        return new MessageFormat(pattern, requested.locale()).format(values);
    }
    public Subscription listen(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }
    public int listenerCount() { return listeners.size(); }
    private void diagnose(String code, String detail) { if (reported.add(code)) LOG.warning(code + ": " + detail); }
    @FunctionalInterface public interface Subscription extends AutoCloseable { @Override void close(); }
}
