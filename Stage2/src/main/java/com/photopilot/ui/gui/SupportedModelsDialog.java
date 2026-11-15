package com.photopilot.ui.gui;

import com.photopilot.application.ShutterCountPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CameraModel.Brand;
import com.photopilot.model.CameraModel.Level;
import com.photopilot.model.CameraModel.Series;
import com.photopilot.service.CameraModelCatalog;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Lookup-only list of the cameras in the shutter count checker's catalog (ExifTool's tested-camera list, through the
 * existing {@link CameraModelCatalog}): one brand at a time, searchable by product name, model ID or naming variant
 * like the catalog's own search, grouped by series, each model with its support level (the full evidence as a
 * tooltip). It selects nothing: the checker always identifies the camera from the photo. Styled as the other
 * application dialogs ({@link AppDialogs}); closes with Close or Escape.
 */
final class SupportedModelsDialog {
    private final CameraModelCatalog catalog;
    private final LocalizationService localization;
    private final ToggleGroup brands = new ToggleGroup();
    private final TextField search = new TextField();
    private final VBox list = new VBox();
    private final ScrollPane scroll = new ScrollPane(list);
    private final Label count = FeaturePane.label("", "shutter-models-count"), source = FeaturePane.label("", "shutter-models-source");
    /** Rows are built once per brand and reused while filtering. */
    private final Map<CameraModel, Node> rows = new HashMap<>();

    private SupportedModelsDialog(CameraModelCatalog catalog, LocalizationService localization) { this.catalog = catalog; this.localization = localization; }

    /** Opens the dialog without blocking and returns it. */
    static Dialog<Void> show(Window owner, CameraModelCatalog catalog, LocalizationService localization) {
        var dialog = new Dialog<Void>(); AppDialogs.style(dialog, owner);
        var pane = dialog.getDialogPane(); pane.setId("shutter-models-dialog"); pane.getStyleClass().add("shutter-models");
        pane.setPrefWidth(640);
        var texts = new FxLocalizer(pane, localization); texts.useReadableFont(pane);
        texts.text(dialog.titleProperty(), "shutter.models.title"); texts.text(dialog.headerTextProperty(), "shutter.models.title");
        var close = new ButtonType(localization.text("window.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(close);
        ((Button) pane.lookupButton(close)).setDefaultButton(true);
        pane.setContent(new SupportedModelsDialog(catalog, localization).content(texts));
        dialog.setOnHidden(event -> texts.close());
        dialog.show();
        return dialog;
    }

    private Node content(FxLocalizer texts) {
        Label intro = texts.label("shutter.models.intro", "shutter-models-intro");
        Label levels = texts.label("shutter.models.levels", "shutter-models-intro");

        HBox brandChoice = new HBox(); brandChoice.getStyleClass().add("segmented"); brandChoice.setAlignment(Pos.CENTER_LEFT);
        brandChoice.setMinWidth(Region.USE_PREF_SIZE); brandChoice.setMaxWidth(Region.USE_PREF_SIZE);
        for (Brand brand : Brand.values()) {
            ToggleButton segment = new ToggleButton(); segment.setText(brand.label()); segment.setUserData(brand);
            segment.setId("shutter-models-" + brand.name().toLowerCase(Locale.ROOT)); segment.getStyleClass().setAll("segment");
            segment.setMinWidth(Region.USE_PREF_SIZE); segment.setToggleGroup(brands);
            brandChoice.getChildren().add(segment);
        }
        // A segmented control always has one brand: clicking the selected one keeps it.
        brands.selectedToggleProperty().addListener((property, was, now) -> { if (now == null) was.setSelected(true); else refresh(); });

        search.setId("shutter-models-search"); HBox.setHgrow(search, Priority.ALWAYS); search.setMaxWidth(Double.MAX_VALUE);
        texts.text(search.promptTextProperty(), "shutter.searchPrompt");
        texts.text(search.accessibleTextProperty(), "shutter.searchAccessible");
        texts.inputMenu(search);
        search.textProperty().addListener((property, was, now) -> refresh());
        HBox controls = new HBox(12, brandChoice, search); controls.setAlignment(Pos.CENTER_LEFT);

        count.setId("shutter-models-count");
        list.setId("shutter-models-list"); list.getStyleClass().add("shutter-models-list");
        scroll.setId("shutter-models-scroll"); scroll.getStyleClass().add("shutter-models-scroll");
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setPrefViewportHeight(340);
        source.setId("shutter-models-source");

        brands.getToggles().getFirst().setSelected(true);
        texts.onRefresh(this::refresh);
        return new VBox(12, intro, levels, controls, count, scroll, source);
    }

    private Brand brand() { return (Brand) brands.getSelectedToggle().getUserData(); }

    /** Shows the brand's models that match the search, grouped by series in catalog order. */
    private void refresh() {
        if (brands.getSelectedToggle() == null) return;
        Brand brand = brand();
        List<CameraModel> all = catalog.models(brand);
        String query = search.getText() == null ? "" : search.getText().strip();
        Set<CameraModel> found = query.isEmpty() ? null : new HashSet<>(catalog.search(brand, Optional.empty(), query));
        List<Node> shown = new ArrayList<>();
        int matches = 0;
        for (Series series : catalog.series(brand)) {
            List<CameraModel> members = all.stream().filter(model -> model.series() == series && (found == null || found.contains(model))).toList();
            if (members.isEmpty()) continue;
            Label group = FeaturePane.label(localization.text("shutter.models.group", ShutterCountPresentation.series(series, localization), members.size()), "shutter-models-group");
            shown.add(group);
            for (CameraModel model : members) shown.add(rows.computeIfAbsent(model, this::row));
            matches += members.size();
        }
        if (matches == 0) {
            Label empty = FeaturePane.label(localization.text("shutter.models.empty", brand.label(), query), "shutter-models-empty");
            empty.setId("shutter-models-empty");
            shown.add(empty);
        }
        list.getChildren().setAll(shown);
        count.setText(localization.text("shutter.models.count", matches, all.size(), brand.label()));
        source.setText(ShutterCountPresentation.source(catalog, brand, localization));
        scroll.setVvalue(0);
    }

    private Node row(CameraModel model) {
        Label name = FeaturePane.label(model.label(), "shutter-models-name"); name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS); name.setMaxWidth(Double.MAX_VALUE);
        Level level = model.support().level();
        Label status = FeaturePane.label(ShutterCountPresentation.level(level, localization), "shutter-models-level");
        status.setMinWidth(Region.USE_PREF_SIZE); status.setWrapText(false);
        if (level == Level.VERIFIED || level == Level.DOCUMENTED) status.getStyleClass().add("shutter-models-level-known");
        HBox row = new HBox(12, name, status); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("shutter-models-row");
        row.setUserData(model);
        Tooltip evidence = new Tooltip(ShutterCountPresentation.support(model, localization));
        evidence.setWrapText(true); evidence.setMaxWidth(420); Tooltip.install(row, evidence);
        return row;
    }
}
