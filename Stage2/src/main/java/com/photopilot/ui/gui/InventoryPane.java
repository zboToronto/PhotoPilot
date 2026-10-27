package com.photopilot.ui.gui;

import com.photopilot.application.*;
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
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();
    private final TableView<Equipment> table = new TableView<>();
    private final Label count = FeaturePane.label("Loading inventory…", "secondary-text");
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox feedbackBox = new VBox(8, FeaturePane.label("Inventory details", "section-title"), feedback);
    private final VBox editor = new VBox(16);
    private final FlowPane fields = new FlowPane(16, 14);
    private final ComboBox<EquipmentType> type = new ComboBox<>();
    private final Map<String, TextField> inputs = new LinkedHashMap<>();
    private final CheckBox duplicate = new CheckBox("I reviewed the possible duplicates and want to save this entry.");
    private final VBox confirmation = new VBox(12);
    private final VBox controls = new VBox(16);
    private Equipment editing;
    private UUID newId;
    private long generation;
    private boolean active;
    private boolean busy;

    InventoryPane(PhotoPilotFacade app, ExecutorService operations) {
        super(20);
        this.app = app; this.operations = operations;
        getStyleClass().add("inventory-content");
        search.setPromptText("Search brand, model, mount, or ID"); search.setId("gear-search");
        search.setAccessibleText("Search inventory"); search.setPrefWidth(260);
        filter.getItems().add("All types");
        for (var kind : EquipmentType.values()) filter.getItems().add(kind.toString());
        filter.getSelectionModel().selectFirst(); filter.setId("gear-filter"); filter.setAccessibleText("Filter equipment type");
        Button refresh = button("Search / Refresh", "gear-refresh", this::refresh, true);
        search.setOnAction(event -> refresh()); filter.setOnAction(event -> refresh());
        FlowPane query = new FlowPane(12, 10, search, filter, refresh);
        table.setId("gear-table"); table.setAccessibleText("Owned equipment inventory");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("Type", 100, item -> item.type().toString());
        column("Equipment", 220, item -> item.brand() + " " + item.model());
        column("Quantity", 80, item -> String.valueOf(item.quantity()));
        table.setPrefHeight(220); table.setMinHeight(180);
        table.setPlaceholder(FeaturePane.label("No matching gear. Add equipment or change your search.", "secondary-text"));
        Button edit = button("Edit selected", "gear-edit", this::editSelected, false);
        Button remove = button("Remove selected", "gear-remove", this::requestRemoval, false);
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        FlowPane actions = new FlowPane(12, 10, button("Add equipment", "gear-add", () -> openEditor(null), true), edit, remove);
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
        TableColumn<Equipment, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width); table.getColumns().add(column);
    }
    private static Button button(String text, String id, Runnable action, boolean primary) {
        Button button = new Button(text); button.setId(id);
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
            count.setText(inventory.items().size() + " item(s) · Optional blank values are unknown; manual entries are unverified.");
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
        Label title = FeaturePane.label(item == null ? "Add equipment" : "Edit equipment", "empty-title");
        Label identity = FeaturePane.label("ID: " + (item == null ? newId : item.id()), "secondary-text");
        Label note = FeaturePane.label("Review your entry before saving. * Required. Optional fields may stay blank; true / false fields accept blank for unknown.", "secondary-text");
        Label evidence = FeaturePane.label(item == null ? "Manual entry · Specifications are unverified."
            : "Verified fields: " + item.verifiedFields() + ". Editing a verified value clears its verification.", "secondary-text");
        editor.getChildren().setAll(title, identity, new VBox(6, FeaturePane.label("Equipment type", "section-title"), type), note, fields, evidence, duplicate,
            new FlowPane(12, 10, button("Save equipment", "gear-save", this::save, true), button("Cancel", "gear-cancel", () -> { hide(editor); hide(feedbackBox); }, false)));
        show(editor);
    }
    private void buildFields(Map<String, String> values) {
        fields.getChildren().clear(); inputs.clear();
        if (type.getValue() == null) return;
        field("brand", "Brand *", values.getOrDefault("brand", ""), "");
        field("model", "Model *", values.getOrDefault("model", ""), "");
        field("quantity", "Quantity *", values.getOrDefault("quantity", "1"), "Positive integer");
        field("weightGrams", "Weight (g)", values.getOrDefault("weightGrams", ""), "Unknown");
        for (var spec : type.getValue().fields()) field(spec.key(), spec.label() + (spec.required() ? " *" : ""),
            values.getOrDefault(spec.key(), ""), spec.kind().equals("boolean") ? "true / false / unknown" : spec.required() ? "Required" : "Unknown");
    }
    private void field(String key, String label, String value, String prompt) {
        TextField input = new TextField(value); input.setId("gear-field-" + key); input.setPromptText(prompt); input.setAccessibleText(label);
        Label caption = FeaturePane.label(label, "section-title"); caption.setLabelFor(input);
        VBox field = new VBox(7, caption, input); field.setMinWidth(0);
        field.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(field); inputs.put(key, input);
    }
    private void save() {
        Map<String, String> values = new LinkedHashMap<>(); inputs.forEach((key, value) -> values.put(key, value.getText()));
        var parsed = EquipmentForm.parse(type.getValue(), editing == null ? newId : editing.id(), values, editing);
        if (!parsed.isSuccess()) { message(String.join("; ", parsed.errors())); return; }
        Equipment item = parsed.value().orElseThrow(); boolean adding = editing == null, accepted = duplicate.isSelected();
        Equipment expected = editing;
        run(() -> adding ? app.inventory().add(item, accepted) : app.inventory().update(item, accepted, expected), saved -> {
            hide(editor); message("Saved " + saved.brand() + " " + saved.model() + "."); refresh();
        });
    }
    private void requestRemoval() {
        Equipment item = table.getSelectionModel().getSelectedItem(); if (item == null) return;
        hide(editor); hide(feedbackBox);
        run(() -> app.inventory().affectedSessions(item.id()), sessions -> {
            Label warning = FeaturePane.label("Remove " + item.brand() + " " + item.model() + "? This removes the inventory entry (all " + item.quantity() + " unit(s)). "
                + (sessions.isEmpty() ? "No saved sessions reference this item." : "Affected saved sessions: " + sessions)
                + " Historical gear snapshots will remain unchanged.", "feedback-text");
            confirmation.getChildren().setAll(warning, new FlowPane(12, 10,
                button("Confirm removal", "gear-confirm-remove", () -> run(() -> app.inventory().remove(item.id(), true, item), removed -> {
                    hide(confirmation); message("Equipment removed. Historical records preserved."); refresh();
                }), true), button("Cancel", "gear-cancel-remove", () -> hide(confirmation), false)));
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
                String error = String.join("; ", result.errors()); message(error);
                if (error.contains("Possible duplicate gear")) show(duplicate);
            }
        });
        task.setOnFailed(event -> {
            if (active && request == generation) { busy = false; controls.setDisable(false); message("Inventory operation failed. Refresh and try again."); }
        });
        operations.execute(task);
    }
    private void message(String text) { feedback.setText(text); show(feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
    // Package-private acceptance-test observations; use real controls for actions.
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    TableView<Equipment> table() { return table; }
}
