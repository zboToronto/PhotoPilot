package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * In-app settings; changing language or appearance updates views in place and never navigates or rebuilds feature forms.
 * Appearance order: Theme Color (five presets, then Custom with its hue slider), Appearance Mode, Transparency, then
 * the existing Language setting. Every choice is saved automatically; only a failed save is reported.
 */
final class ApplicationSettingsPane extends VBox {
    /** A slider moved by keyboard or scroll is saved once it has rested this long; a drag is saved when released. */
    static final Duration SAVE_DELAY = Duration.millis(400);
    private final FxLocalizer texts;
    private final ThemeManager appearance = ThemeManager.get();
    private final ToggleGroup accents = new ToggleGroup(), modes = new ToggleGroup();
    private final ToggleButton customSwatch = new ToggleButton();
    private final Slider transparency = new Slider(0, 100, ThemeManager.get().opacity());
    private final Slider hue = new Slider(0, 359, ThemeManager.get().customHue());
    private final Circle customDot = new Circle(6.5);
    private final HBox hueRow = new HBox(8);
    private final Label transparencyValue = FeaturePane.label("", "settings-value");
    private final Label systemNote = FeaturePane.label("", "feedback-text");
    private final Label appearanceFeedback = FeaturePane.label("", "feedback-text");
    private final PauseTransition pendingSave = new PauseTransition(SAVE_DELAY);
    // Held strongly here; ThemeManager keeps listeners weakly, so a disposed pane is simply collected.
    private final Runnable themeChanged = this::synchronize;
    private boolean synchronizingAppearance;

    ApplicationSettingsPane(LocalizationService localization) {
        super(16); setId("application-settings"); getStyleClass().add("page"); setMaxWidth(1180); setMinWidth(0);
        texts = new FxLocalizer(this, localization);
        ComboBox<Language> language = new ComboBox<>();
        language.getItems().setAll(Language.values());
        language.setValue(localization.language());
        language.setId("settings-language");
        // One width for every language name, so switching language never resizes or moves the selector.
        language.setPrefWidth(168); language.setMinWidth(Region.USE_PREF_SIZE);
        texts.text(language.accessibleTextProperty(), "settings.language");
        Label label = texts.label("settings.language", "settings-row-label"); label.setLabelFor(language);
        Label feedback = FeaturePane.label("", "feedback-text"); feedback.setId("settings-feedback");
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty()); feedback.visibleProperty().bind(feedback.managedProperty());
        boolean[] synchronizing = { false };
        texts.onRefresh(() -> {
            synchronizing[0] = true;
            try { language.setValue(localization.language()); }
            finally { synchronizing[0] = false; }
        });
        if (localization.preferenceFailure()) texts.text(feedback.textProperty(), localization.preferenceFailureKey());
        // Saved automatically by the existing language preference; no message after a successful save.
        language.setOnAction(event -> {
            if (synchronizing[0]) return;
            if (localization.selectLanguage(language.getValue().tag())) texts.bind(feedback.textProperty(), () -> "");
            else texts.text(feedback.textProperty(), "settings.saveFailed");
        });
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(12, label, space, language); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("settings-row");

        systemNote.setId("settings-system-note"); texts.text(systemNote.textProperty(), "settings.systemUnavailable");
        appearanceFeedback.setId("settings-appearance-feedback");
        for (Label note : new Label[] { systemNote, appearanceFeedback }) note.visibleProperty().bind(note.managedProperty());
        appearanceFeedback.managedProperty().bind(appearanceFeedback.textProperty().isNotEmpty());
        pendingSave.setOnFinished(event -> report(appearance.save()));
        var appearanceSection = texts.label("settings.appearance", "settings-section-title"); appearanceSection.setId("settings-appearance");
        VBox group = new VBox(4, texts.label("menu.appSettings", "page-title"), appearanceSection,
            row("settings.themeColor", swatches(), null), customHue(), new Separator(),
            row("settings.appearanceMode", modes(), null), new Separator(),
            row("settings.transparency", transparency(), transparency), new Separator(),
            row, systemNote, appearanceFeedback, feedback);
        group.getStyleClass().add("surface-panel");
        getChildren().add(group);
        appearance.listen(themeChanged);
        synchronize();
    }

    private HBox row(String key, Node control, Control labelled) {
        Label label = texts.label(key, "settings-row-label"); label.setWrapText(true); label.setMinWidth(90);
        if (labelled != null) label.setLabelFor(labelled);
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(12, label, space, control); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("settings-row");
        return row;
    }
    /** 1. Five theme colour swatches, then the Custom colour; the selected one carries a ring and a check mark. */
    private HBox swatches() {
        var box = new HBox(6); box.setId("settings-theme"); box.setAlignment(Pos.CENTER_RIGHT); box.setMinWidth(Region.USE_PREF_SIZE);
        for (Theme.Accent accent : Theme.Accent.values()) {
            var swatch = swatch(new ToggleButton(), "settings-theme-" + accent.id, accent.key, new Circle(11, accent.swatch));
            swatch.setToggleGroup(accents); swatch.setUserData(accent);
            swatch.setOnAction(event -> {
                swatch.setSelected(true);
                if (!synchronizingAppearance) report(appearance.select(accent));
            });
            box.getChildren().add(swatch);
        }
        // Custom is a user-configurable choice, not a sixth preset: set apart by a hairline and drawn as a spectrum
        // disc whose centre previews the chosen colour.
        var divider = new Region(); divider.getStyleClass().add("swatch-divider");
        var spectrum = new Circle(11, spectrumPaint());
        customDot.getStyleClass().add("custom-swatch-centre");
        var swatch = swatch(customSwatch, "settings-theme-custom", "settings.customColor", spectrum, customDot);
        swatch.getStyleClass().add("custom-swatch"); swatch.setToggleGroup(accents);
        swatch.setOnAction(event -> {
            swatch.setSelected(true);
            if (!synchronizingAppearance) report(appearance.selectCustom());
        });
        box.getChildren().addAll(divider, swatch);
        return box;
    }
    private ToggleButton swatch(ToggleButton swatch, String id, String key, Circle dot, Node... over) {
        dot.getStyleClass().add("swatch-dot");
        var check = AppIcons.outline(AppIcons.CHECK, "swatch-check");
        swatch.setId(id); swatch.getStyleClass().setAll("theme-swatch");
        var face = new StackPane(dot); face.getChildren().addAll(over); face.getChildren().add(check);
        swatch.setGraphic(face);
        check.visibleProperty().bind(swatch.selectedProperty());
        texts.text(swatch.accessibleTextProperty(), key);
        var tip = new Tooltip(); texts.text(tip.textProperty(), key); swatch.setTooltip(tip);
        return swatch;
    }
    private static Paint spectrumPaint() {
        var stops = new java.util.ArrayList<Stop>();
        for (int degrees = 0; degrees <= 360; degrees += 30) stops.add(new Stop(degrees / 360.0, Theme.Family.custom(degrees).swatch()));
        return new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, stops);
    }
    /** Shown beneath the swatches while Custom is selected: a pastel hue spectrum and a preview of the accent it gives. */
    private HBox customHue() {
        hue.setId("settings-custom-hue"); hue.getStyleClass().addAll("appearance-slider", "hue-slider");
        hue.setPrefWidth(220); hue.setMinWidth(110); hue.setBlockIncrement(5);
        texts.text(hue.accessibleTextProperty(), "settings.customHueAccessible");
        var caption = texts.label("settings.customHue", "settings-caption"); caption.setLabelFor(hue); caption.setMinWidth(Region.USE_PREF_SIZE);
        // Filled with the live accent token, so it shows exactly the colour buttons and selections now use.
        var preview = new Region(); preview.setId("settings-custom-preview"); preview.getStyleClass().add("custom-preview");
        hue.valueProperty().addListener((property, old, value) -> {
            if (synchronizingAppearance) return;
            appearance.previewCustomHue(degrees());
            saveWhenSettled(hue);
        });
        hue.valueChangingProperty().addListener((property, was, changing) -> { if (!changing && !synchronizingAppearance) saveNow(); });
        hueRow.setId("settings-custom-row"); hueRow.getStyleClass().add("settings-subrow");
        hueRow.getChildren().setAll(caption, hue, preview); hueRow.setAlignment(Pos.CENTER_RIGHT);
        hueRow.visibleProperty().bind(hueRow.managedProperty());
        return hueRow;
    }
    private int degrees() { return (int) Math.round(hue.getValue()); }
    /** 2. Light / Dark / Follow System as one compact segmented control. */
    private HBox modes() {
        var box = new HBox(); box.setId("settings-mode"); box.getStyleClass().add("segmented"); box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Region.USE_PREF_SIZE); box.setMinWidth(Region.USE_PREF_SIZE);
        for (Theme.Mode mode : Theme.Mode.values()) {
            var segment = new ToggleButton(); segment.setId("settings-mode-" + mode.id); segment.getStyleClass().setAll("segment");
            texts.text(segment.textProperty(), mode.key); segment.setToggleGroup(modes); segment.setUserData(mode);
            segment.setMinWidth(Region.USE_PREF_SIZE);
            segment.setOnAction(event -> {
                segment.setSelected(true);
                if (!synchronizingAppearance) report(appearance.select(mode));
            });
            box.getChildren().add(segment);
        }
        return box;
    }
    /** 3. Surface opacity from transparent (0%) to opaque (100%); previews live and is saved when the drag ends. */
    private HBox transparency() {
        transparency.setId("settings-transparency"); transparency.getStyleClass().add("appearance-slider");
        transparency.setPrefWidth(220); transparency.setMinWidth(110); transparency.setBlockIncrement(5);
        texts.text(transparency.accessibleTextProperty(), "settings.transparency");
        transparencyValue.setId("settings-transparency-value");
        texts.bind(transparencyValue.textProperty(), () -> texts.service().text("settings.percent", percent()));
        transparency.valueProperty().addListener((property, old, value) -> {
            transparencyValue.setText(texts.service().text("settings.percent", percent()));
            if (synchronizingAppearance) return;
            appearance.previewOpacity(percent());
            saveWhenSettled(transparency);
        });
        transparency.valueChangingProperty().addListener((property, was, changing) -> {
            if (!changing && !synchronizingAppearance) saveNow();
        });
        Label transparent = texts.label("settings.transparent", "settings-caption"), opaque = texts.label("settings.opaque", "settings-caption");
        transparent.setMinWidth(Region.USE_PREF_SIZE); opaque.setMinWidth(Region.USE_PREF_SIZE);
        var box = new HBox(8, transparent, transparency, opaque, transparencyValue);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }
    private int percent() { return (int) Math.round(transparency.getValue()); }
    /** Drags are saved once on release; keyboard and scroll steps are saved after they settle, not on every step. */
    private void saveWhenSettled(Slider slider) { if (slider.isValueChanging()) pendingSave.stop(); else pendingSave.playFromStart(); }
    private void saveNow() { pendingSave.stop(); report(appearance.save()); }
    /** Automatic saving stays silent; only a failure is shown, and it stays until a later save succeeds. */
    private void report(boolean saved) {
        if (saved) appearanceFeedback.setText("");
        else texts.text(appearanceFeedback.textProperty(), "settings.appearanceSaveFailed");
    }
    /** Reflects the current appearance (also when Follow System changes it) without saving anything. */
    private void synchronize() {
        synchronizingAppearance = true;
        try {
            for (Toggle toggle : accents.getToggles())
                toggle.setSelected(toggle == customSwatch ? appearance.custom() : !appearance.custom() && toggle.getUserData() == appearance.accent());
            for (Toggle toggle : modes.getToggles()) toggle.setSelected(toggle.getUserData() == appearance.mode());
            if (!transparency.isValueChanging() && percent() != appearance.opacity()) transparency.setValue(appearance.opacity());
            if (!hue.isValueChanging() && degrees() != appearance.customHue()) hue.setValue(appearance.customHue());
        } finally { synchronizingAppearance = false; }
        customDot.setFill(Theme.Family.custom(appearance.customHue()).swatch());
        hueRow.setManaged(appearance.custom());
        systemNote.setManaged(appearance.systemUnavailable());
    }
    /** Called when the view is discarded: a slider value still waiting for its save is written now. */
    void dispose() {
        pendingSave.stop();
        if (appearance.pending()) appearance.save();
        texts.close();
    }
}
