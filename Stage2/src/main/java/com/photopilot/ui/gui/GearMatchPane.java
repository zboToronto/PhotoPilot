package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F07 content in the existing feature surface: session -> confirmed F06 capability profile (adjustable here with the
 * shared F06 editor) -> Match My Gear with a chosen ranking -> verified recommendations -> explicit selection. Reuses
 * RequirementPane's controls, feedback cards, confirmation pattern and background Task lifecycle; all rules live in
 * the services.
 */
final class GearMatchPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final ComboBox<String> strategy = new ComboBox<>();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final Label requirementLine = FeaturePane.label("", "secondary-text"), profileLines = FeaturePane.label("", "secondary-text");
    private final Label savedLines = FeaturePane.label("", "secondary-text");
    private final VBox controls = new VBox(16), profileBox = new VBox(10), editor = new VBox(14),
        matchBox = new VBox(12), results = new VBox(18), confirmation = new VBox(12), saved = new VBox(10), feedbackBox = new VBox(8);
    private final Button editProfile;
    private final CapabilityNeedEditor needs;
    private boolean draft;
    private PhotographySession draftSession, matchedSession;
    private GearMatchReport report;
    private GearMatchReport.SelectionStatus savedStatus;
    private boolean busy, disposed;

    GearMatchPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        session.setId("match-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "match.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> { if (!draft) { clearResults(); showSession(); } });
        Label sessionCaption = texts.label("match.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "match-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);

        profileLines.setId("match-profile");
        editProfile = button("needs.edit", "match-profile-edit", this::openEditor, false);
        profileBox.getChildren().addAll(texts.label("needs.title", "section-title"), requirementLine, profileLines,
            texts.label("needs.manualNotice", "secondary-text"), new FlowPane(12, 10, editProfile));

        needs = new CapabilityNeedEditor("match", texts, localization, this::message);
        editor.getChildren().addAll(texts.label("needs.editorTitle", "empty-title"), texts.label("needs.manualNotice", "secondary-text"), needs,
            new FlowPane(12, 10, button("needs.confirm", "match-profile-confirm", this::confirmProfile, true),
                button("button.cancel", "match-profile-cancel", this::closeEditor, false)));

        strategy.setId("match-strategy"); strategy.getItems().addAll(app.planning().rankingStrategies()); strategy.setValue(strategy.getItems().getFirst());
        texts.choices(strategy, s -> localization.text("match.strategy." + s));
        Label strategyCaption = texts.label("match.strategyLabel", "section-title"); strategyCaption.setLabelFor(strategy);
        matchBox.getChildren().addAll(texts.label("match.title", "section-title"), new VBox(7, strategyCaption, strategy),
            texts.label("match.aiNotice", "secondary-text"), new FlowPane(12, 10, button("match.run", "match-run", this::match, true)));
        saved.getChildren().addAll(texts.label("match.saved.title", "section-title"), savedLines); savedLines.setId("match-saved");
        for (var box : List.of(profileBox, editor, matchBox, confirmation, saved, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("match.feedback", "section-title"), feedback);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint), profileBox, editor, saved, matchBox);
        hide(editor); hide(feedbackBox); hide(sessionHint); hide(confirmation); hide(saved); hide(results);
        getChildren().addAll(controls, results, confirmation, feedbackBox);
        texts.onRefresh(this::render);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    boolean dirty() { return draft || !confirmation.getChildren().isEmpty() && confirmation.isVisible(); }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    GearMatchReport report() { return report; }

    /** Editable sessions only: completed/reviewed sessions keep their historical selection. */
    void refresh() {
        UUID selected = draftSession != null ? draftSession.id() : session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            if (draftSession != null) editable.stream().filter(s -> s.id().equals(draftSession.id())).findFirst().ifPresent(fresh -> draftSession = fresh);
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!editable.isEmpty()) session.setValue(editable.getFirst()); });
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession();
        }, null);
    }

    private void showSession() {
        var selected = session.getValue();
        if (selected == null) { hide(profileBox); hide(matchBox); hide(saved); return; }
        show(profileBox); show(matchBox);
        var requirement = selected.requirements().filter(ShootRequirement::confirmed);
        texts.bind(requirementLine.textProperty(), () -> requirement.map(r -> localization.text("match.requirement", r.subject()))
            .orElse(localization.text("match.noRequirement")));
        var profile = selected.profile().filter(EquipmentRequirementProfile::reviewed);
        var usable = com.photopilot.service.PlanningService.usableProfile(selected);
        texts.bind(profileLines.textProperty(), () -> profile.map(p -> localization.text("needs.savedTitle") + "\n" + String.join("\n", CapabilityNeedForm.profile(p, localization))
                + (usable.isSuccess() ? "" : "\n" + localization.text("needs.inconsistent", String.join(" ", usable.errors(localization)))))
            .orElse(localization.text("needs.noneSaved")));
        texts.text(editProfile.textProperty(), profile.isPresent() ? "needs.edit" : "needs.enter");
        editProfile.setDisable(requirement.isEmpty());
        savedStatus = null; hide(saved);
        if (selected.selectedLoadout().isPresent() && !busy) {
            UUID id = selected.id();
            run(() -> app.planning().selection(id), status -> {
                if (session.getValue() == null || !session.getValue().id().equals(id)) return;
                savedStatus = status; render(); show(saved);
            }, failure -> message(() -> String.join("; ", failure.errors(localization))));
        }
    }

    private void openEditor() {
        var selected = session.getValue();
        if (selected == null || draft) return;
        var requirement = selected.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) { message(() -> localization.text("needs.error.requirement")); return; }
        draftSession = selected; draft = true;
        needs.open(selected.profile().filter(EquipmentRequirementProfile::reviewed).orElse(new EquipmentRequirementProfile(List.of(), false)), requirement);
        session.setDisable(true); hide(matchBox); clearResults(); show(editor);
        message(() -> localization.text("needs.editing")); SessionPane.reveal(this, editor);
    }
    private void closeEditor() {
        draft = false; draftSession = null; needs.close(); session.setDisable(false); hide(editor); show(matchBox);
        message(() -> localization.text("needs.cancelled"));
    }
    /** Editing the saved profile here is the explicit replacement; the revision check still rejects concurrent changes. */
    private void confirmProfile() {
        if (!draft) return;
        PhotographySession expected = draftSession; var edited = needs.draft();
        boolean replace = expected.profile().filter(EquipmentRequirementProfile::reviewed).isPresent();
        message(() -> localization.text("needs.saving"));
        run(() -> app.planning().confirmProfile(expected, edited, replace), stored -> {
            draft = false; draftSession = null; needs.close(); session.setDisable(false); hide(editor); show(matchBox);
            message(() -> localization.text("needs.saved", expected.title())); refresh();
        }, failure -> { needs.render(); message(() -> String.join(" ", failure.errors(localization))); });
    }

    private void match() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        String chosen = strategy.getValue();
        clearResults(); message(() -> localization.text("match.running", LoadoutPresentation.strategyName(chosen, localization)));
        run(() -> app.planning().matchGear(selected.id(), chosen), found -> {
            report = found; matchedSession = selected; render(); show(results);
            message(() -> localization.text(found.recommendations().isEmpty() ? "match.summary.noneUsable" : "match.done", found.recommendations().size()));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }
    private void clearResults() { report = null; matchedSession = null; results.getChildren().clear(); hide(results); confirmation.getChildren().clear(); hide(confirmation); }

    /** Rebuilds the localized cards from the last service results (also on language change). */
    private void render() {
        if (savedStatus != null) savedLines.setText(String.join("\n", LoadoutPresentation.selection(savedStatus, localization)));
        if (draft) needs.render();
        results.getChildren().clear();
        if (report == null) return;
        results.getChildren().add(card("match.summary.title", "match-summary", LoadoutPresentation.summary(report, localization), null));
        var shown = report.recommendations();
        for (int i = 0; i < Math.min(LoadoutPresentation.SHOWN, shown.size()); i++) {
            var candidate = shown.get(i);
            Button select = button("match.select", "match-select-" + (i + 1), () -> prepare(candidate), i == 0);
            var box = card(null, "match-candidate-" + (i + 1), LoadoutPresentation.candidate(candidate, localization), select);
            box.getChildren().addFirst(FeaturePane.label(LoadoutPresentation.heading(candidate, i + 1, localization), "section-title"));
            results.getChildren().add(box);
        }
        var rejectedTitle = localization.text("match.rejected.title", report.rejectedCombinations());
        var rejected = card(null, "match-rejected", LoadoutPresentation.rejections(report, localization), null);
        rejected.getChildren().addFirst(FeaturePane.label(rejectedTitle, "section-title"));
        results.getChildren().add(rejected);
    }
    private VBox card(String titleKey, String id, List<String> lines, Button action) {
        var body = FeaturePane.label(String.join("\n", lines), "secondary-text"); body.setId(id);
        var box = new VBox(10); box.getStyleClass().add("feedback-box");
        if (titleKey != null) box.getChildren().add(FeaturePane.label(localization.text(titleKey), "section-title"));
        box.getChildren().add(body);
        if (action != null) box.getChildren().add(new FlowPane(12, 10, action));
        return box;
    }

    private void prepare(Candidate candidate) {
        if (matchedSession == null) return;
        UUID id = matchedSession.id();
        run(() -> app.sessions().open(id), fresh -> {
            matchedSession = fresh;
            var lines = FeaturePane.label(String.join("\n", LoadoutPresentation.confirmation(candidate, fresh, localization)), "feedback-text");
            lines.setId("match-confirmation");
            boolean replace = fresh.selectedLoadout().isPresent();
            confirmation.getChildren().setAll(texts.label("match.confirm.title", "section-title"), lines, new FlowPane(12, 10,
                button(replace ? "match.confirm.replaceAction" : "match.confirm.action", "match-confirm", () -> save(candidate, replace), true),
                button("button.cancel", "match-cancel", () -> { confirmation.getChildren().clear(); hide(confirmation); message(() -> localization.text("match.cancelled")); }, false)));
            show(confirmation); SessionPane.reveal(this, confirmation);
        }, null);
    }
    private void save(Candidate candidate, boolean replace) {
        PhotographySession expected = matchedSession;
        message(() -> localization.text("match.saving"));
        run(() -> app.planning().selectLoadout(expected, candidate.loadout(), replace), stored -> {
            confirmation.getChildren().clear(); hide(confirmation); clearResults();
            message(() -> localization.text("match.saved", expected.title())); refresh();
        }, failure -> message(() -> localization.text("match.saveFailed", String.join(" ", failure.errors(localization)))));
    }

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true); results.setDisable(true); confirmation.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else if (failure != null) failure.accept(result);
            else message(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("match.failed")); });
        operations.execute(task);
    }
    private void done() { busy = false; controls.setDisable(false); results.setDisable(false); confirmation.setDisable(false); }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
