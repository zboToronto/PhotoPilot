package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.i18n.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** F05 content within FeaturePane: shared theme, asynchronous service calls, no business rules. */
final class InventoryPane extends VBox {
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Label detailsTitle = FeaturePane.label("", "section-title");
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final TextField search = new TextField();
    private final ComboBox<EquipmentType> filter = new ComboBox<>();
    private final TableView<Equipment> table = new TableView<>();
    private final Label count = FeaturePane.label("", "secondary-text");
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox feedbackBox = new VBox(8, detailsTitle, feedback);
    private final VBox editor = new VBox(16);
    private final FlowPane fields = new FlowPane(16, 14);
    private final ComboBox<EquipmentType> type = new ComboBox<>();
    private final Map<String, TextField> inputs = new LinkedHashMap<>();
    private final CheckBox duplicate = new CheckBox();
    private final VBox confirmation = new VBox(12);
    private final VBox controls = new VBox(16);
    private Equipment editing;
    private UUID newId;
    private long generation;
    private boolean active;
    private boolean busy;

    InventoryPane(PhotoPilotFacade app, ExecutorService operations) { this(app, operations, LocalizationService.english()); }
    InventoryPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20);
        this.app = app; this.operations = operations;
        this.localization = localization; texts = new FxLocalizer(this, localization);
        texts.text(count.textProperty(), "gear.loading");
        texts.text(detailsTitle.textProperty(), "gear.details");
        texts.text(duplicate.textProperty(), "gear.duplicateReview");
        texts.inputMenu(search);
        getStyleClass().add("inventory-content");
        texts.text(search.promptTextProperty(), "gear.searchPrompt"); search.setId("gear-search");
        texts.text(search.accessibleTextProperty(), "gear.searchAccessible"); search.setPrefWidth(260);
        filter.getItems().add(null);
        filter.getItems().addAll(EquipmentType.values());
        texts.choices(filter, kind -> localization.text("gear.type." + kind.name()), () -> localization.text("gear.allTypes"));
        texts.text(filter.promptTextProperty(), "gear.allTypes");
        texts.choices(type, kind -> localization.text("gear.type." + kind.name()));
        filter.getSelectionModel().selectFirst(); filter.setId("gear-filter"); texts.text(filter.accessibleTextProperty(), "gear.filterAccessible");
        Button refresh = button("gear.refresh", "gear-refresh", this::refresh, true);
        search.setOnAction(event -> refresh()); filter.setOnAction(event -> refresh());
        FlowPane query = new FlowPane(12, 10, search, filter, refresh);
        table.setId("gear-table"); texts.text(table.accessibleTextProperty(), "gear.tableAccessible");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("gear.column.type", 100, item -> localization.text("gear.type." + item.type().name()));
        column("gear.column.equipment", 220, item -> item.brand() + " " + item.model());
        column("gear.column.quantity", 80, item -> String.valueOf(item.quantity()));
        table.setPrefHeight(220); table.setMinHeight(180);
        table.setPlaceholder(texts.label("gear.empty", "secondary-text"));
        Button edit = button("gear.editSelected", "gear-edit", this::editSelected, false);
        Button remove = button("gear.removeSelected", "gear-remove", this::requestRemoval, false);
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        FlowPane actions = new FlowPane(12, 10, button("gear.add", "gear-add", () -> openEditor(null), true), edit, remove);
        controls.getChildren().addAll(query, count, table, actions, confirmation, editor);
        feedbackBox.getStyleClass().add("feedback-box");
        editor.getStyleClass().add("feedback-box"); confirmation.getStyleClass().add("feedback-box");
        hide(editor); hide(confirmation); hide(feedbackBox);
        getChildren().addAll(controls, feedbackBox);
        type.getItems().setAll(EquipmentType.values()); type.setId("gear-type");
        type.setOnAction(event -> buildFields(Map.of()));
        duplicate.setWrapText(true); duplicate.setId("gear-duplicate-confirm");
    }
    private void column(String title, double width, Function<Equipment, String> value) {
        TableColumn<Equipment, String> column = new TableColumn<>();
        texts.text(column.textProperty(), title);
        column.setCellValueFactory(cell -> {
            var property = new javafx.beans.property.SimpleStringProperty();
            texts.bind(property, () -> value.apply(cell.getValue())); return property;
        });
        column.setPrefWidth(width); table.getColumns().add(column);
    }
    private Button button(String text, String id, Runnable action, boolean primary) {
        Button button = texts.button(text); button.setId(id);
        button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(event -> action.run()); return button;
    }
    void activate(boolean active) {
        this.active = active; generation++;
        if (active) { hide(editor); hide(confirmation); hide(feedbackBox); refresh(); }
    }
    void refresh() {
        String query = search.getText();
        int selected = filter.getSelectionModel().getSelectedIndex();
        EquipmentType kind = selected <= 0 ? null : EquipmentType.values()[selected - 1];
        run(() -> app.inventory().list(query, kind), inventory -> {
            table.getItems().setAll(inventory.items());
            texts.text(count.textProperty(), "gear.count", inventory.items().size());
        });
    }
    private void editSelected() {
        var item = table.getSelectionModel().getSelectedItem();
        if (item != null) openEditor(item);
    }
    private void openEditor(Equipment item) {
        hide(confirmation); hide(feedbackBox); editing = item; newId = UUID.randomUUID();
        type.setValue(item == null ? EquipmentType.CAMERA : item.type()); type.setDisable(item != null);
        duplicate.setSelected(false); hide(duplicate);
        buildFields(item == null ? Map.of() : EquipmentForm.values(item));
        Label title = texts.label(item == null ? "gear.add" : "gear.edit", "empty-title");
        Label identity = texts.label("gear.identity", "secondary-text", item == null ? newId : item.id());
        Label note = texts.label("gear.review", "secondary-text");
        Label evidence = texts.label(item == null ? "gear.manual" : "gear.verified", "secondary-text", item == null ? "" : item.verifiedFields());
        editor.getChildren().setAll(title, identity, new VBox(6, texts.label("gear.type", "section-title"), type), note, fields, evidence, duplicate,
            new FlowPane(12, 10, button("gear.save", "gear-save", this::save, true), button("button.cancel", "gear-cancel", () -> { hide(editor); hide(feedbackBox); }, false)));
        show(editor);
    }
    private void buildFields(Map<String, String> values) {
        fields.getChildren().clear(); inputs.clear();
        if (type.getValue() == null) return;
        field("brand", "gear.field.brand", true, values.getOrDefault("brand", ""), "");
        field("model", "gear.field.model", true, values.getOrDefault("model", ""), "");
        field("quantity", "gear.field.quantity", true, values.getOrDefault("quantity", "1"), "gear.positiveInteger");
        field("weightGrams", "gear.field.weightGrams", false, values.getOrDefault("weightGrams", ""), "gear.unknown");
        for (var spec : type.getValue().fields()) field(spec.key(), "gear.field." + spec.key(), spec.required(),
            values.getOrDefault(spec.key(), ""), spec.kind().equals("boolean") ? "gear.boolean" : spec.required() ? "gear.required" : "gear.unknown");
    }
    private void field(String key, String label, boolean required, String value, String prompt) {
        TextField input = new TextField(value); input.setId("gear-field-" + key); if (!prompt.isEmpty()) texts.text(input.promptTextProperty(), prompt);
        texts.inputMenu(input);
        texts.bind(input.accessibleTextProperty(), () -> localization.text(label) + (required ? " *" : ""));
        Label caption = FeaturePane.label("", "section-title");
        texts.bind(caption.textProperty(), () -> required ? localization.text("gear.requiredLabel", localization.text(label)) : localization.text(label)); caption.setLabelFor(input);
        VBox field = new VBox(7, caption, input); field.setMinWidth(0);
        field.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(field); inputs.put(key, input);
    }
    private void save() {
        Map<String, String> values = new LinkedHashMap<>(); inputs.forEach((key, value) -> values.put(key, value.getText()));
        var parsed = EquipmentForm.parse(type.getValue(), editing == null ? newId : editing.id(), values, editing);
        if (!parsed.isSuccess()) { message(() -> String.join("; ", parsed.errors(localization))); return; }
        Equipment item = parsed.value().orElseThrow(); boolean adding = editing == null, accepted = duplicate.isSelected();
        Equipment expected = editing;
        run(() -> adding ? app.inventory().add(item, accepted) : app.inventory().update(item, accepted, expected), saved -> {
            hide(editor); message(() -> localization.text("gear.saved", saved.brand(), saved.model())); refresh();
        });
    }
    private void requestRemoval() {
        Equipment item = table.getSelectionModel().getSelectedItem(); if (item == null) return;
        hide(editor); hide(feedbackBox);
        run(() -> app.inventory().affectedSessions(item.id()), sessions -> {
            Label warning = FeaturePane.label("", "feedback-text");
            texts.bind(warning.textProperty(), () -> localization.text("gear.removeConfirmation", item.brand(), item.model(), item.quantity(),
                sessions.isEmpty() ? localization.text("gear.noReferences") : localization.text("gear.references", sessions)));
            confirmation.getChildren().setAll(warning, new FlowPane(12, 10,
                button("gear.confirmRemoval", "gear-confirm-remove", () -> run(() -> app.inventory().remove(item.id(), true, item), removed -> {
                    hide(confirmation); message(() -> localization.text("gear.removed")); refresh();
                }), true), button("button.cancel", "gear-cancel-remove", () -> hide(confirmation), false)));
            show(confirmation);
        });
    }
    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success) {
        long request = ++generation; busy = true; controls.setDisable(true);
        Task<Result<T>> task = new Task<>() { @Override protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(event -> {
            if (!active || request != generation) return;
            busy = false; controls.setDisable(false);
            var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else {
                message(() -> String.join("; ", result.errors(localization)));
                if (result.hasError("error.gear.duplicate")) show(duplicate);
            }
        });
        task.setOnFailed(event -> {
            if (active && request == generation) { busy = false; controls.setDisable(false); message(() -> localization.text("gear.operationFailed")); }
        });
        operations.execute(task);
    }
    private void message(Supplier<String> text) { texts.bind(feedback.textProperty(), text); show(feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
    // Package-private acceptance-test observations; use real controls for actions.
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    TableView<Equipment> table() { return table; }
}
