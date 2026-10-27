package com.photopilot.ui.gui;

import com.photopilot.application.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import com.photopilot.model.Equipment;
import com.photopilot.common.Result;
import com.photopilot.model.GearInventory;
import javafx.concurrent.Task;
import javafx.util.StringConverter;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** F08 form in the existing feature flow, using the inventory form styling. */
final class CalculatorPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final ComboBox<Equipment> gear=new ComboBox<>();
    private final Button loadGear=new Button("Load inventory");
    private long generation;
    private boolean active;
    private final ComboBox<CalculationForm.Tool> tool=new ComboBox<>();
    private final ComboBox<String> ndUnit=new ComboBox<>();
    private final FlowPane fields=new FlowPane(16,14);
    private final Map<String,TextField> inputs=new LinkedHashMap<>();
    private final Label feedback=FeaturePane.label("", "feedback-text");
    private final VBox feedbackBox=new VBox(8,FeaturePane.label("Calculation details","section-title"),feedback);
    CalculatorPane(PhotoPilotFacade app, ExecutorService operations) {
        super(20);this.app=app;this.operations=operations;getStyleClass().add("inventory-content");
        tool.getItems().setAll(CalculationForm.Tool.values());tool.setId("calc-tool");tool.setAccessibleText("Calculator");tool.setMaxWidth(Double.MAX_VALUE);
        ndUnit.getItems().setAll("Stops", "ND factor", "Optical density");ndUnit.getSelectionModel().selectFirst();ndUnit.setId("calc-nd-unit");ndUnit.setAccessibleText("ND strength convention");
        tool.valueProperty().addListener((o, old, value) -> buildFields());ndUnit.valueProperty().addListener((o, old, value) -> buildFields());
        Button calculate=new Button("Calculate");calculate.getStyleClass().add("primary-button");calculate.setId("calc-run");calculate.setOnAction(e -> calculate());
        feedbackBox.getStyleClass().add("feedback-box");
        gear.setId("calc-gear");gear.setAccessibleText("Optional inventory item");gear.setPromptText("Optional inventory item");gear.setPrefWidth(260);
        gear.setConverter(new StringConverter<>() {
            @Override public String toString(Equipment item) { return item==null ? "" : item.brand()+" "+item.model()+" ("+item.type()+")"; }
            @Override public Equipment fromString(String text) { throw new UnsupportedOperationException(); }
        });
        loadGear.setId("calc-load-gear");loadGear.getStyleClass().add("secondary-button");loadGear.setOnAction(e -> loadInventory());
        Button applyGear=new Button("Use known values");applyGear.setId("calc-use-gear");applyGear.getStyleClass().add("secondary-button");applyGear.disableProperty().bind(gear.valueProperty().isNull());
        applyGear.setOnAction(e -> {
            var values=CalculationForm.fromGear(tool.getValue(),gear.getValue());
            if (values.containsKey("ndStops") && ndUnit.getSelectionModel().getSelectedIndex()!=0) {
                String shutter=inputs.get("shutter").getText();ndUnit.getSelectionModel().selectFirst();inputs.get("shutter").setText(shutter);
            }
            values.forEach((key,value) -> { if (inputs.containsKey(key)) inputs.get(key).setText(value); });
            message(values.isEmpty() ? "No usable values for this calculation. Enter parameters manually." : "Known inventory values copied. Review all fields before Calculate; inventory specifications may be unverified. Taking aperture, zoom focal length and CoC require your choice.");
        });
        getChildren().addAll(new FlowPane(12,10,loadGear,gear,applyGear),new VBox(7,FeaturePane.label("Calculation","section-title"),tool),ndUnit,fields,calculate,feedbackBox);
        tool.getSelectionModel().selectFirst();
    }
    void activate(boolean value) {
        active=value;generation++;loadGear.setDisable(false);
        feedbackBox.setVisible(false);feedbackBox.setManaged(false);
        // Cached inventory is invalidated when reopening; manual form values remain editable.
        if (active) gear.getItems().clear();
    }
    private void loadInventory() {
        long request=++generation;loadGear.setDisable(true);
        Task<Result<GearInventory>> task=new Task<>() { @Override protected Result<GearInventory> call() { return app.inventory().list(""); } };
        task.setOnSucceeded(e -> {
            if (!active || request!=generation) return;
            loadGear.setDisable(false);
            var result=task.getValue();
            if (!result.isSuccess()) message(String.join("; ",result.errors()));
            else { gear.getItems().setAll(result.value().orElseThrow().items());message(gear.getItems().isEmpty() ? "Inventory is empty. Enter parameters manually." : "Select an item, use its known values, then review the parameters."); }
        });
        task.setOnFailed(e -> { if (active && request==generation) {loadGear.setDisable(false);message("Could not load inventory. Manual input remains available.");} });
        operations.execute(task);
    }
    private void buildFields() {
        inputs.clear();fields.getChildren().clear();feedbackBox.setVisible(false);feedbackBox.setManaged(false);
        boolean nd=tool.getValue()==CalculationForm.Tool.ND;ndUnit.setVisible(nd);ndUnit.setManaged(nd);
        if (tool.getValue()==null) return;
        for (var original:tool.getValue().fields) {
            var f=original;
            if (f.key().equals("ndStops")) f=switch(ndUnit.getSelectionModel().getSelectedIndex()) {
                case 1 -> new CalculationForm.Field("ndFactor","ND factor","factor","e.g. 8 for ND8");
                case 2 -> new CalculationForm.Field("opticalDensity","Optical density","OD","e.g. 3.0");
                default -> f;
            };
            TextField input=new TextField();input.setId("calc-field-"+f.key());input.setPromptText(f.hint());input.setAccessibleText(f.label()+" ("+f.unit()+")");
            input.textProperty().addListener((o,old,value) -> { feedbackBox.setVisible(false);feedbackBox.setManaged(false); });
            Label caption=FeaturePane.label(f.label()+" ("+f.unit()+") *","section-title");caption.setLabelFor(input);
            VBox field=new VBox(7,caption,input);field.setMinWidth(0);
            field.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth()<560 ? Math.max(160,fields.getWidth()) : (fields.getWidth()-16)/2,fields.widthProperty()));
            fields.getChildren().add(field);inputs.put(f.key(),input);
        }
    }
    private void calculate() {
        Map<String,String> text=new LinkedHashMap<>();inputs.forEach((key,input) -> text.put(key,input.getText()));
        var parsed=CalculationForm.parse(tool.getValue(),text);
        if (!parsed.isSuccess()) message(String.join("; ",parsed.errors()));
        else {
            var result=app.calculate(parsed.value().orElseThrow());
            message(result.isSuccess() ? CalculationForm.format(result.value().orElseThrow()) : String.join("; ",result.errors()));
        }
    }
    private void message(String value) { feedback.setText(value);feedbackBox.setVisible(true);feedbackBox.setManaged(true); }
    String feedbackText() { return feedback.getText(); }
}
