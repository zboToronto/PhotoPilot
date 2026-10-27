package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import java.util.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.Property;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.input.Clipboard;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** View-owned bindings. Only visible windows subscribe; closing/detaching releases subscriptions. */
final class FxLocalizer implements AutoCloseable {
    private final LocalizationService localization;
    private final Map<Property<String>, Supplier<String>> bindings = new WeakHashMap<>();
    private final List<Runnable> refreshActions = new ArrayList<>();
    private LocalizationService.Subscription subscription;
    private Window window;
    private Scene scene;
    private boolean closed;
    private final ChangeListener<Boolean> showing = (o, old, value) -> updateSubscription();
    private final ChangeListener<Window> windowChanged = (o, old, value) -> attachWindow(value);

    FxLocalizer(Node owner, LocalizationService localization) {
        this.localization = localization;
        owner.sceneProperty().addListener((o, old, value) -> attachScene(value));
        attachScene(owner.getScene());
    }
    LocalizationService service() { return localization; }
    private void attachScene(Scene value) {
        if (scene != null) scene.windowProperty().removeListener(windowChanged);
        scene = value;
        if (scene != null) scene.windowProperty().addListener(windowChanged);
        attachWindow(scene == null ? null : scene.getWindow());
    }
    private void attachWindow(Window value) {
        if (window != null) window.showingProperty().removeListener(showing);
        window = value;
        if (window != null) window.showingProperty().addListener(showing);
        updateSubscription();
    }
    private void updateSubscription() {
        if (subscription != null) { subscription.close(); subscription = null; }
        if (!closed && window != null && window.isShowing()) {
            subscription = localization.listen(this::refresh);
            refresh();
        }
    }
    private void refresh() {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> { if (!closed && subscription != null) refresh(); }); return; }
        // Snapshot permits dynamic controls to register bindings from a refresh callback.
        new ArrayList<>(bindings.entrySet()).forEach(entry -> {
            Property<String> property = entry.getKey();
            if (property != null) property.setValue(entry.getValue().get());
        });
        refreshActions.forEach(Runnable::run);
    }
    void bind(Property<String> property, Supplier<String> value) { bindings.put(property, value); property.setValue(value.get()); }
    void text(Property<String> property, String key, Object... args) { bind(property, () -> localization.text(key, args)); }
    void onRefresh(Runnable action) { refreshActions.add(action); action.run(); }
    void useReadableFont(Region root) {
        String initialStyle = root.getStyle();
        String chinese = List.of("Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "WenQuanYi Micro Hei")
            .stream().filter(Font.getFamilies()::contains).findFirst().orElse(Font.getDefault().getFamily());
        onRefresh(() -> root.setStyle(localization.language() == com.photopilot.i18n.Language.SIMPLIFIED_CHINESE
            ? initialStyle + "; -fx-font-family: \"" + chinese + "\";" : initialStyle));
    }
    Label label(String key, String style, Object... args) {
        Label label = FeaturePane.label("", style); text(label.textProperty(), key, args); return label;
    }
    Button button(String key) { Button button = new Button(); text(button.textProperty(), key); return button; }
    void inputMenu(TextInputControl input) {
        ContextMenu menu = new ContextMenu();
        MenuItem undo = item("edit.undo", input::undo), redo = item("edit.redo", input::redo);
        MenuItem cut = item("edit.cut", input::cut), copy = item("edit.copy", input::copy);
        MenuItem paste = item("edit.paste", input::paste), select = item("edit.selectAll", input::selectAll);
        undo.disableProperty().bind(input.undoableProperty().not().or(input.editableProperty().not()));
        redo.disableProperty().bind(input.redoableProperty().not().or(input.editableProperty().not()));
        copy.disableProperty().bind(input.selectedTextProperty().isEmpty());
        cut.disableProperty().bind(input.selectedTextProperty().isEmpty().or(input.editableProperty().not()));
        menu.setOnShowing(event -> paste.setDisable(!input.isEditable() || !Clipboard.getSystemClipboard().hasString()));
        menu.getItems().setAll(undo, redo, new SeparatorMenuItem(), cut, copy, paste, new SeparatorMenuItem(), select);
        input.setContextMenu(menu);
    }
    private MenuItem item(String key, Runnable action) {
        MenuItem item = new MenuItem(); text(item.textProperty(), key); item.setOnAction(event -> action.run()); return item;
    }
    <T> void choices(ComboBox<T> box, java.util.function.Function<T, String> display) {
        choices(box, display, () -> "");
    }
    <T> void choices(ComboBox<T> box, java.util.function.Function<T, String> display, Supplier<String> emptyText) {
        box.setConverter(new StringConverter<>() {
            public String toString(T value) { return value == null ? emptyText.get() : display.apply(value); }
            public T fromString(String value) { throw new UnsupportedOperationException("Read-only selection"); }
        });
        box.setCellFactory(list -> cell(display, emptyText, false));
        box.setButtonCell(cell(display, emptyText, true));
    }
    private <T> ListCell<T> cell(java.util.function.Function<T, String> display, Supplier<String> emptyText, boolean button) {
        return new ListCell<>() {
            @Override protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                bind(textProperty(), () -> empty || value == null ? button || !empty ? emptyText.get() : "" : display.apply(value));
            }
        };
    }
    @Override public void close() {
        closed = true;
        if (subscription != null) subscription.close(); subscription = null;
        if (window != null) window.showingProperty().removeListener(showing);
        if (scene != null) scene.windowProperty().removeListener(windowChanged);
        bindings.clear();
        refreshActions.clear();
    }
}
