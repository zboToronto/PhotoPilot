package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.i18n.*;
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
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Label detailsTitle = FeaturePane.label("", "section-title");
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final ComboBox<Equipment> gear=new ComboBox<>();
    private final Button loadGear=new Button();
    private long generation;
    private boolean active;
    private final ComboBox<CalculationForm.Tool> tool=new ComboBox<>();
    private final ComboBox<String> ndUnit=new ComboBox<>();
    private final FlowPane fields=new FlowPane(16,14);
    private final Map<String,TextField> inputs=new LinkedHashMap<>();
    private final Label feedback=FeaturePane.label("", "feedback-text");
    private final VBox feedbackBox=new VBox(8,detailsTitle,feedback);
    CalculatorPane(PhotoPilotFacade app, ExecutorService operations) { this(app, operations, LocalizationService.english()); }
    CalculatorPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20);
        this.localization=localization; texts=new FxLocalizer(this, localization);
        texts.text(loadGear.textProperty(), "calc.loadInventory");
        texts.text(detailsTitle.textProperty(), "calc.details");
        texts.choices(tool, value -> localization.text("calc.tool." + value.name()));
        texts.choices(ndUnit, value -> localization.text("calc.nd." + value));
        this.app=app;this.operations=operations;getStyleClass().add("inventory-content");
        tool.getItems().setAll(CalculationForm.Tool.values());tool.setId("calc-tool");texts.text(tool.accessibleTextProperty(), "calc.calculator");tool.setMaxWidth(Double.MAX_VALUE);
        ndUnit.getItems().setAll("stops", "factor", "density");ndUnit.getSelectionModel().selectFirst();ndUnit.setId("calc-nd-unit");texts.text(ndUnit.accessibleTextProperty(), "calc.ndUnit");
        tool.valueProperty().addListener((o, old, value) -> buildFields());ndUnit.valueProperty().addListener((o, old, value) -> buildFields());
        Button calculate=texts.button("calc.calculate");calculate.getStyleClass().add("primary-button");calculate.setId("calc-run");calculate.setOnAction(e -> calculate());
        feedbackBox.getStyleClass().add("feedback-box");
        gear.setId("calc-gear");texts.text(gear.accessibleTextProperty(), "calc.optionalGear");texts.text(gear.promptTextProperty(), "calc.optionalGear");gear.setPrefWidth(260);
        texts.choices(gear, item -> localization.text("calc.gearChoice", item.brand(), item.model(), localization.text("gear.type." + item.type().name())), () -> localization.text("calc.optionalGear"));
        loadGear.setId("calc-load-gear");loadGear.getStyleClass().add("secondary-button");loadGear.setOnAction(e -> loadInventory());
        Button applyGear=texts.button("calc.applyGear");applyGear.setId("calc-use-gear");applyGear.getStyleClass().add("secondary-button");applyGear.disableProperty().bind(gear.valueProperty().isNull());
        applyGear.setOnAction(e -> {
            var values=CalculationForm.fromGear(tool.getValue(),gear.getValue());
            if (values.containsKey("ndStops") && ndUnit.getSelectionModel().getSelectedIndex()!=0) {
                String shutter=inputs.get("shutter").getText();ndUnit.getSelectionModel().selectFirst();inputs.get("shutter").setText(shutter);
            }
            values.forEach((key,value) -> { if (inputs.containsKey(key)) inputs.get(key).setText(value); });
            message(() -> localization.text(values.isEmpty() ? "calc.noValues" : "calc.copied"));
        });
        getChildren().addAll(new FlowPane(12,10,loadGear,gear,applyGear),new VBox(7,texts.label("calc.calculation","section-title"),tool),ndUnit,fields,calculate,feedbackBox);
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
            if (!result.isSuccess()) message(() -> String.join("; ",result.errors(localization)));
            else { gear.getItems().setAll(result.value().orElseThrow().items());message(() -> localization.text(gear.getItems().isEmpty() ? "calc.emptyInventory" : "calc.selectGear")); }
        });
        task.setOnFailed(e -> { if (active && request==generation) {loadGear.setDisable(false);message(() -> localization.text("calc.loadFailed"));} });
        operations.execute(task);
    }
    private void buildFields() {
        inputs.clear();fields.getChildren().clear();feedbackBox.setVisible(false);feedbackBox.setManaged(false);
        boolean nd=tool.getValue()==CalculationForm.Tool.ND;ndUnit.setVisible(nd);ndUnit.setManaged(nd);
        if (tool.getValue()==null) return;
        for (var original:tool.getValue().fields) {
            var f=original;
            if (f.key().equals("ndStops")) f=switch(ndUnit.getSelectionModel().getSelectedIndex()) {
                case 1 -> new CalculationForm.Field("ndFactor","","factor","");
                case 2 -> new CalculationForm.Field("opticalDensity","","OD","");
                default -> f;
            };
            final var fieldDefinition=f;
            TextField input=new TextField();input.setId("calc-field-"+f.key());
            texts.inputMenu(input);
            texts.text(input.promptTextProperty(), "calc.hint."+f.key());
            texts.bind(input.accessibleTextProperty(), () -> localization.text("calc.fieldAccessible", localization.text("calc.field."+fieldDefinition.key()), CalculationForm.unit(fieldDefinition.unit(), localization)));
            input.textProperty().addListener((o,old,value) -> { feedbackBox.setVisible(false);feedbackBox.setManaged(false); });
            Label caption=FeaturePane.label("","section-title");
            texts.bind(caption.textProperty(), () -> localization.text("calc.fieldCaption", localization.text("calc.field."+fieldDefinition.key()), CalculationForm.unit(fieldDefinition.unit(), localization)));caption.setLabelFor(input);
            VBox field=new VBox(7,caption,input);field.setMinWidth(0);
            field.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth()<560 ? Math.max(160,fields.getWidth()) : (fields.getWidth()-16)/2,fields.widthProperty()));
            fields.getChildren().add(field);inputs.put(f.key(),input);
        }
    }
    private void calculate() {
        Map<String,String> text=new LinkedHashMap<>();inputs.forEach((key,input) -> text.put(key,input.getText()));
        var parsed=CalculationForm.parse(tool.getValue(),text);
        if (!parsed.isSuccess()) message(() -> String.join("; ",parsed.errors(localization)));
        else {
            var result=app.calculate(parsed.value().orElseThrow());
            message(() -> result.isSuccess() ? CalculationForm.format(result.value().orElseThrow(), localization) : String.join("; ",result.errors(localization)));
        }
    }
    private void message(java.util.function.Supplier<String> value) { texts.bind(feedback.textProperty(), value);feedbackBox.setVisible(true);feedbackBox.setManaged(true); }
    String feedbackText() { return feedback.getText(); }
}
