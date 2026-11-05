package com.photopilot.ui.gui;

import com.photopilot.application.CapabilityNeedForm;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import java.util.*;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Shared F06 capability-need editor (fields, draft list with edit/remove, live deterministic validation), used by the
 * F06 Capability Analysis page and F07's profile adjustment. Parsing and rules come from CapabilityNeedForm and the
 * domain; the editor never saves. Control ids are {@code <prefix>-need-*} so each page keeps its own ids.
 */
final class CapabilityNeedEditor extends VBox {
    private final String prefix;
    private final FxLocalizer texts;
    private final LocalizationService localization;
    private final Consumer<Supplier<String>> message;
    private final ComboBox<String> capability = new ComboBox<>();
    private final TextField minimum = new TextField(), maximum = new TextField(), priority = new TextField(), note = new TextField();
    private final CheckBox mandatory = new CheckBox();
    private final Label accepted = FeaturePane.label("", "secondary-text"), validation = FeaturePane.label("", "feedback-text");
    private final VBox draftList = new VBox(8);
    private EquipmentRequirementProfile draft;
    private Optional<ShootRequirement> requirement = Optional.empty();

    CapabilityNeedEditor(String prefix, FxLocalizer texts, LocalizationService localization, Consumer<Supplier<String>> message) {
        super(14); this.prefix = prefix; this.texts = texts; this.localization = localization; this.message = message;
        capability.setId(prefix + "-need-capability"); capability.getItems().addAll(CapabilityNeed.SUPPORTED.keySet()); capability.setMaxWidth(Double.MAX_VALUE);
        texts.choices(capability, c -> localization.text("needs.capability." + c), () -> localization.text("needs.choose"));
        capability.valueProperty().addListener((o, old, value) -> texts.bind(accepted.textProperty(), () -> value == null ? "" : CapabilityNeedForm.capabilityHelp(value, localization)));
        mandatory.setId(prefix + "-need-mandatory"); mandatory.setSelected(true); texts.text(mandatory.textProperty(), "needs.field.mandatory");
        priority.setText(CapabilityNeedForm.DEFAULT_PRIORITY);
        var fields = new FlowPane(16, 14);
        field(fields, "capability", capability); field(fields, "minimum", minimum); field(fields, "maximum", maximum);
        field(fields, "priority", priority); field(fields, "note", note);
        Button add = texts.button("needs.addNeed"); add.setId(prefix + "-need-add"); add.getStyleClass().add("secondary-button"); add.setOnAction(e -> addNeed());
        validation.setId(prefix + "-need-validation");
        getChildren().addAll(fields, accepted, mandatory, new FlowPane(12, 10, add), texts.label("needs.draftNeeds", "section-title"), draftList, validation);
    }
    private void field(FlowPane fields, String key, Control input) {
        input.setId(prefix + "-need-" + key); texts.bind(input.accessibleTextProperty(), () -> localization.text("needs.field." + key));
        if (input instanceof TextField text) { texts.inputMenu(text); texts.text(text.promptTextProperty(), "needs.prompt." + key); }
        Label caption = FeaturePane.label("", "section-title"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> localization.text("needs.field." + key));
        VBox box = new VBox(7, caption, input); box.setMinWidth(0);
        box.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(box);
    }

    /** Starts editing {@code profile} (always handled as an unreviewed draft) against the session's confirmed requirement. */
    void open(EquipmentRequirementProfile profile, Optional<ShootRequirement> confirmed) {
        draft = profile.asDraft(); requirement = confirmed; clearFields(); render();
    }
    void close() { draft = null; requirement = Optional.empty(); clearFields(); draftList.getChildren().clear(); validation.setText(""); }
    EquipmentRequirementProfile draft() { return draft; }
    /** Deterministic findings of the current draft against the confirmed requirement; the model is never consulted. */
    Result<EquipmentRequirementProfile> check() {
        if (draft == null) return Result.failureKey("needs.error.noDraft");
        return requirement.map(r -> draft.validate(r)).orElse(Result.failureKey("needs.error.requirement"));
    }

    private void clearFields() {
        capability.setValue(null); minimum.clear(); maximum.clear(); note.clear(); priority.setText(CapabilityNeedForm.DEFAULT_PRIORITY); mandatory.setSelected(true);
    }
    private void addNeed() {
        if (draft == null) return;
        var values = new LinkedHashMap<String, String>();
        values.put("capability", Objects.toString(capability.getValue(), "")); values.put("minimum", minimum.getText());
        values.put("maximum", maximum.getText()); values.put("priority", priority.getText());
        values.put("mandatory", mandatory.isSelected() ? "yes" : "no"); values.put("note", note.getText());
        var parsed = CapabilityNeedForm.parse(values);
        if (!parsed.isSuccess()) { message.accept(() -> String.join(" ", parsed.errors(localization))); return; }
        draft = CapabilityNeedForm.put(draft, parsed.value().orElseThrow());
        clearFields(); render(); message.accept(() -> localization.text("needs.needAdded"));
    }

    /** Rebuilds the localized draft list and validation (also on language change). */
    void render() {
        draftList.getChildren().clear();
        if (draft == null) return;
        if (draft.needs().isEmpty()) draftList.getChildren().add(texts.label("needs.noNeeds", "secondary-text"));
        for (CapabilityNeed need : draft.needs()) {
            Label text = FeaturePane.label(CapabilityNeedForm.describe(need, localization), "secondary-text");
            text.setId(prefix + "-need-line-" + need.capability());
            Button edit = texts.button("needs.editNeed"); edit.setId(prefix + "-need-edit-" + need.capability()); edit.getStyleClass().add("secondary-button");
            edit.setOnAction(e -> {
                var values = CapabilityNeedForm.format(need);
                capability.setValue(need.capability()); minimum.setText(values.get("minimum")); maximum.setText(values.get("maximum"));
                priority.setText(values.get("priority")); mandatory.setSelected(need.hardConstraint()); note.setText(values.get("note"));
            });
            Button remove = texts.button("needs.removeNeed"); remove.setId(prefix + "-need-remove-" + need.capability()); remove.getStyleClass().add("secondary-button");
            remove.setOnAction(e -> { draft = CapabilityNeedForm.remove(draft, need.capability()); render(); });
            draftList.getChildren().add(new VBox(6, text, new FlowPane(10, 8, edit, remove)));
        }
        var checked = check();
        texts.bind(validation.textProperty(), () -> checked.isSuccess() ? localization.text("needs.valid")
            : localization.text("needs.problems", String.join(" ", checked.errors(localization))));
    }
}
