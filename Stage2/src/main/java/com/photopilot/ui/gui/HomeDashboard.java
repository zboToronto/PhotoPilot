package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.service.MiniAiService;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;

/**
 * Home: the welcome card, the Mini AI inspiration card and up to two cards built from the active user's real sessions.
 * Mini AI sends the question (with the short in-memory conversation) to the configured model through the shared
 * MiniAiService on the window's worker queue, and shows the answer, a loading state or a short error in the card; the
 * chips only fill the input. Sessions come from the shared SessionService through the same queue; every card action
 * reuses an existing workflow. No demo records, no storage access of its own.
 */
final class HomeDashboard extends VBox {
    private static final double GAP = 18;

    /** The active user's services and the existing workflows that Home opens. */
    record Links(PhotoPilotFacade app, UiOperations operations, Consumer<UUID> continueSession, Consumer<UUID> reviewSession, Runnable openInspiration) { }

    /**
     * The sessions behind the two optional cards, chosen from the existing lifecycle states only: the most recently
     * updated Draft or Planned session (unfinished), and the most recently updated Completed session (shot, waiting for
     * its review), else the most recently updated Reviewed one. A planning session is never treated as a completed shoot.
     */
    record Highlights(Optional<PhotographySession> unfinished, Optional<PhotographySession> afterShoot) {
        static final Highlights NONE = new Highlights(Optional.empty(), Optional.empty());
        static Highlights of(List<PhotographySession> sessions) {
            return new Highlights(latest(sessions, "DRAFT", "PLANNED"), latest(sessions, "COMPLETED").or(() -> latest(sessions, "REVIEWED")));
        }
        private static Optional<PhotographySession> latest(List<PhotographySession> sessions, String... states) {
            var wanted = Set.of(states);
            return sessions.stream().filter(session -> wanted.contains(session.status())).max(Comparator.comparing(PhotographySession::updatedAt));
        }
    }

    /** The next unfinished preparation step, in the order the existing workflows require it (F03, F06, then F10). */
    enum Step {
        REQUIREMENT, PROFILE, PLAN, SHOOT;
        static Step of(PhotographySession session) {
            if (session.status().equals("PLANNED")) return SHOOT;
            if (session.requirements().filter(ShootRequirement::confirmed).isEmpty()) return REQUIREMENT;
            if (session.profile().filter(EquipmentRequirementProfile::reviewed).isEmpty()) return PROFILE;
            return PLAN;
        }
    }

    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Label greeting = FeaturePane.label("", "page-title");
    private final StackPane avatar = new StackPane();
    private final TextArea prompt = new TextArea();
    /** Off-screen text used only to measure the input's wrapped text height. */
    private final javafx.scene.text.Text measure = new javafx.scene.text.Text();
    /** Created with the card, once the localizer exists. */
    private Button generate, clear, historyToggle;
    /** Loading state, validation messages and errors, below the chips; hidden until needed. */
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusText = FeaturePane.label("", "home-ai-status");
    private final HBox status = new HBox(8, progress, statusText);
    /** The latest answer with its question, and the earlier exchanges behind a toggle; hidden until the first answer. */
    private final VBox response = new VBox();
    private final Label asked = single(FeaturePane.label("", "home-ai-asked")), answer = FeaturePane.label("", "home-ai-answer");
    private final VBox history = new VBox(10);
    private final Random random = new Random();
    /** Zero, one or two session cards; collapsed when there are none, one card spans the row, two share it equally. */
    private final HBox sessions = new HBox(GAP);
    private Links links;
    private long generation, asks;
    private boolean disposed, asking, composing, historyOpen;
    private MiniAiService.Conversation conversation = MiniAiService.Conversation.EMPTY;
    private String lastTheme = "";

    HomeDashboard(LocalizationService localization) {
        super(GAP); this.localization = localization;
        setId("home-dashboard"); getStyleClass().add("page"); setMaxWidth(1180); setMinWidth(0);
        texts = new FxLocalizer(this, localization);
        sessions.setId("home-sessions"); sessions.setMinWidth(0);
        sessions.setVisible(false); sessions.setManaged(false);
        getChildren().addAll(welcome(), inspiration(), sessions);
    }

    private HBox welcome() {
        var identity = new VBox(0, greeting, texts.label("home.identity", "page-subtitle")); identity.setMinWidth(0);
        identity.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(identity, Priority.ALWAYS);
        var card = new HBox(14, avatar, identity); card.setAlignment(Pos.CENTER_LEFT);
        card.setId("home-welcome"); card.getStyleClass().addAll("surface-panel", "home-card", "home-welcome");
        return card;
    }

    private VBox inspiration() {
        // The question is the card's first and most prominent element.
        var question = texts.label("home.ai.question", "home-ai-question"); question.setId("home-ai-question"); question.setMinWidth(0);
        var description = texts.label("home.ai.description", "home-ai-description");
        // Multiline input that follows its wrapped text: the Home styles keep it between 64 px (about two lines) and
        // 120 px; beyond that it scrolls. Its height never depends on the answer.
        prompt.setId("home-ai-prompt"); prompt.getStyleClass().add("home-prompt"); prompt.setWrapText(true); prompt.setPrefRowCount(2);
        prompt.textProperty().addListener((property, was, now) -> fitPrompt());
        prompt.skinProperty().addListener((property, was, now) -> {
            if (prompt.lookup(".scroll-pane") instanceof ScrollPane scroll && scroll.getContent() instanceof Region content)
                content.widthProperty().addListener((width, before, after) -> fitPrompt());
        });
        texts.text(prompt.promptTextProperty(), "home.ai.placeholder"); texts.text(prompt.accessibleTextProperty(), "home.ai.question"); texts.inputMenu(prompt);
        // Enter sends and Shift+Enter starts a new line. While an input method is composing (e.g. Chinese pinyin), Enter
        // belongs to the input method and never sends.
        prompt.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, event -> composing = !event.getComposed().isEmpty());
        prompt.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ENTER || composing) return;
            event.consume();
            if (event.isShiftDown()) prompt.replaceSelection("\n");
            else if (!event.isAltDown()) ask();
        });
        // Suggestion chips only fill the input for the user to edit; they never start a request.
        var chips = new FlowPane(12, 10,
            chip("home-chip-tonight", AppIcons.MOON, "home.ai.chip.tonight", () -> fill(localization.text("home.ai.chip.tonight.prompt"))),
            chip("home-chip-nearby", AppIcons.PIN, "home.ai.chip.nearby", () -> fill(localization.text("home.ai.chip.nearby.prompt"))),
            chip("home-chip-gear", AppIcons.CAMERA_OUTLINE, "home.ai.chip.gear", this::fillGear),
            chip("home-chip-random", AppIcons.SHUFFLE, "home.ai.chip.random", this::fillRandom));
        chips.setId("home-ai-chips"); chips.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(chips, Priority.ALWAYS);
        generate = action("home.ai.generate", "home-ai-generate", AppIcons.SPARKLES, true, false);
        generate.getStyleClass().add("home-generate"); generate.setOnAction(event -> ask());
        // Says what Generate sends and where, in the place the "not available yet" note used to be.
        texts.text(generate.accessibleHelpProperty(), "home.ai.privacy");
        var privacy = new Tooltip(); privacy.setWrapText(true); privacy.setMaxWidth(320); texts.text(privacy.textProperty(), "home.ai.privacy");
        var generateSlot = new StackPane(generate); generateSlot.setMinWidth(Region.USE_PREF_SIZE); Tooltip.install(generateSlot, privacy);
        var actions = new HBox(12, chips, generateSlot); actions.setAlignment(Pos.CENTER_LEFT);
        // Spins only while a request runs.
        progress.setId("home-ai-progress"); progress.getStyleClass().add("home-ai-progress"); progress.setProgress(0);
        progress.setMinSize(16, 16); progress.setPrefSize(16, 16); progress.setMaxSize(16, 16);
        statusText.setId("home-ai-status-text"); statusText.setMinWidth(0); HBox.setHgrow(statusText, Priority.ALWAYS);
        status.setId("home-ai-status"); status.setAlignment(Pos.CENTER_LEFT); hide(status);
        VBox.setMargin(description, new Insets(6, 0, 0, 0));
        VBox.setMargin(prompt, new Insets(18, 0, 0, 0)); VBox.setMargin(actions, new Insets(16, 0, 0, 0));
        VBox.setMargin(status, new Insets(14, 0, 0, 0));
        var card = new VBox(0, question, description, prompt, actions, status, responseArea());
        card.setId("home-inspiration"); card.getStyleClass().addAll("surface-panel", "home-card", "home-inspiration");
        return card;
    }

    /**
     * Divider; a header row with "AI Inspiration" on the left and the small Clear conversation / Advanced inspiration
     * actions on the right; the latest question (small, secondary); its answer on a subtle inner surface; and the
     * earlier exchanges behind a toggle, collapsed by default. No bubbles or nested cards.
     */
    private VBox responseArea() {
        var divider = new Separator(); divider.getStyleClass().add("home-ai-divider");
        var title = texts.label("home.ai.response.title", "home-ai-response-title"); title.setId("home-ai-response-title"); title.setMinWidth(Region.USE_PREF_SIZE);
        var advanced = link("home.ai.advanced", "home-ai-advanced");
        advanced.setOnAction(event -> { if (links != null) links.openInspiration().run(); });
        clear = link("home.ai.clear", "home-ai-clear"); clear.setOnAction(event -> clearConversation());
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(4, AppIcons.slot(AppIcons.SPARKLES, "home-ai-response-icon", 18), title, spacer, clear, advanced);
        header.setAlignment(Pos.CENTER_LEFT); HBox.setMargin(title, new Insets(0, 0, 0, 4));
        // The last action's text lines up with the card's right content edge; its padding stays clickable.
        HBox.setMargin(advanced, new Insets(0, -6, 0, 0));
        asked.setId("home-ai-asked"); asked.setMaxWidth(Double.MAX_VALUE);
        asked.setGraphic(AppIcons.slot(AppIcons.MESSAGE, "home-ai-asked-icon", 14)); asked.setGraphicTextGap(6);
        // One label: the text wraps at the surface's width and reflows when the window is resized.
        answer.setId("home-ai-answer"); answer.setMaxWidth(Double.MAX_VALUE);
        var answerBox = new VBox(answer); answerBox.setId("home-ai-answer-box"); answerBox.getStyleClass().add("home-ai-answer-box");
        historyToggle = link("home.ai.history.show", "home-ai-history-toggle");
        historyToggle.setOnAction(event -> { historyOpen = !historyOpen; renderConversation(); });
        history.setId("home-ai-history"); hide(history);
        VBox.setMargin(divider, new Insets(14, 0, 0, 0)); VBox.setMargin(header, new Insets(10, 0, 0, 0));
        VBox.setMargin(asked, new Insets(8, 0, 0, 0)); VBox.setMargin(answerBox, new Insets(6, 0, 0, 0));
        VBox.setMargin(historyToggle, new Insets(6, 0, 0, -6)); VBox.setMargin(history, new Insets(4, 0, 0, 0));
        response.getChildren().setAll(divider, header, asked, answerBox, historyToggle, history);
        response.setId("home-ai-response"); hide(response);
        return response;
    }

    private Button chip(String id, String icon, String key, Runnable action) {
        var chip = new Button(); chip.setId(id); chip.getStyleClass().add("home-chip"); chip.setMinWidth(Region.USE_PREF_SIZE);
        chip.setGraphic(AppIcons.slot(icon, "home-chip-icon", 18));
        texts.text(chip.textProperty(), key);
        chip.setOnAction(event -> action.run());
        return chip;
    }

    /** A small text action inside the card. */
    private Button link(String key, String id) {
        var button = texts.button(key); button.setId(id); button.getStyleClass().add("home-link"); button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** Preferred height = the wrapped text plus the input's own padding; the Home styles bound it, so short text keeps the default. */
    private void fitPrompt() {
        if (!(prompt.lookup(".scroll-pane") instanceof ScrollPane scroll) || !(scroll.getContent() instanceof Region content) || content.getWidth() <= 0) return;
        measure.setFont(prompt.getFont());
        measure.setWrappingWidth(content.getWidth() - content.snappedLeftInset() - content.snappedRightInset());
        measure.setText(prompt.getText().isEmpty() ? " " : prompt.getText());
        double height = Math.ceil(measure.getLayoutBounds().getHeight() + content.snappedTopInset() + content.snappedBottomInset()
            + scroll.snappedTopInset() + scroll.snappedBottomInset() + prompt.snappedTopInset() + prompt.snappedBottomInset());
        if (Math.abs(prompt.getPrefHeight() - height) >= 1) prompt.setPrefHeight(height);
    }

    /** Puts a suggestion in the input for the user to edit and send; nothing is sent. */
    private void fill(String suggestion) {
        prompt.setText(suggestion); prompt.requestFocus(); prompt.end();
        if (!asking) hide(status);
    }

    /** "Based on my gear": the user's own cameras, lenses and filters (F05, read only), never invented ones. */
    private void fillGear() {
        if (links == null) { fill(localization.text("home.ai.chip.gear.unknown")); return; }
        var app = links.app();
        links.operations().run(() -> app.miniAi().gear(), result -> {
            if (disposed) return;
            if (!result.isSuccess()) fill(localization.text("home.ai.chip.gear.unknown"));
            else if (result.value().orElseThrow().isEmpty()) fill(localization.text("home.ai.chip.gear.none"));
            else fill(localization.text("home.ai.chip.gear.prompt", String.join(", ", result.value().orElseThrow())));
        });
    }

    /** "Random idea": a creative challenge around a theme picked at random, never the same theme twice in a row. */
    private void fillRandom() {
        var themes = Arrays.stream(localization.text("home.ai.chip.random.themes").split("\\|")).map(String::strip).filter(theme -> !theme.isEmpty()).toList();
        var others = themes.stream().filter(theme -> !theme.equals(lastTheme)).toList();
        lastTheme = others.isEmpty() ? themes.getFirst() : others.get(random.nextInt(others.size()));
        fill(localization.text("home.ai.chip.random.prompt", lastTheme));
    }

    /**
     * Validates the input, then sends it with the conversation so far. One request at a time: Generate and Enter do
     * nothing while it runs. A failure keeps the input and the conversation unchanged; a success adds the exchange and
     * clears the input (unless the user has typed something else meanwhile).
     */
    private void ask() {
        if (asking || links == null || disposed) return;
        var valid = MiniAiService.validate(prompt.getText());
        if (!valid.isSuccess()) { failure(valid); return; }
        String text = valid.value().orElseThrow();
        asking = true; generate.setDisable(true); clear.setDisable(true);
        statusText.getStyleClass().remove("home-ai-error"); progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS); show(progress);
        texts.text(statusText.textProperty(), "home.ai.generating"); show(status);
        long ticket = ++asks; var app = links.app(); var context = conversation;
        links.operations().run(() -> app.miniAi().ask(context, text), result -> {
            if (disposed || ticket != asks) return;
            asking = false; generate.setDisable(false); clear.setDisable(false); progress.setProgress(0);
            if (!result.isSuccess()) { failure(result); return; }
            conversation = conversation.with(new MiniAiService.Exchange(text, result.value().orElseThrow()));
            // The new answer is what the user reads next; earlier exchanges fold away again (they stay in the context).
            historyOpen = false; hide(status);
            if (prompt.getText().strip().equals(text)) prompt.clear();
            renderConversation();
            bringIntoView(response);
        });
    }

    private void failure(com.photopilot.common.Result<?> result) {
        hide(progress);
        if (!statusText.getStyleClass().contains("home-ai-error")) statusText.getStyleClass().add("home-ai-error");
        texts.bind(statusText.textProperty(), () -> String.join(" ", result.errors(localization)));
        show(status);
    }

    /** Forgets this conversation (nothing else); the next question starts without context. */
    private void clearConversation() {
        if (asking) return;
        conversation = MiniAiService.Conversation.EMPTY; historyOpen = false;
        hide(status); renderConversation();
    }

    private void renderConversation() {
        var exchanges = conversation.exchanges();
        if (exchanges.isEmpty()) { hide(response); history.getChildren().clear(); return; }
        var latest = exchanges.getLast();
        // The question as a one-line context subtitle (line breaks folded); its full text stays in the tooltip when shortened.
        asked.setText(latest.question().replaceAll("\\s*\\R\\s*", " "));
        answer.setText(latest.answer());
        var earlier = exchanges.subList(0, exchanges.size() - 1);
        history.getChildren().clear();
        for (var exchange : earlier) {
            var said = FeaturePane.label(exchange.question(), "home-ai-history-question");
            var replied = FeaturePane.label(exchange.answer(), "home-ai-history-answer");
            // Muted text with a hairline between exchanges; no card per message.
            var item = new VBox(2, said, replied);
            if (!history.getChildren().isEmpty()) item.getStyleClass().add("home-ai-history-later");
            history.getChildren().add(item);
        }
        int count = earlier.size();
        texts.bind(historyToggle.textProperty(), () -> historyOpen ? localization.text("home.ai.history.hide") : localization.text("home.ai.history.show", count));
        if (count == 0) hide(historyToggle); else show(historyToggle);
        if (historyOpen && count > 0) show(history); else hide(history);
        show(response);
    }

    /** Scrolls the page only as far as needed to show the new answer, and not at all when it is already in view. */
    private void bringIntoView(Region target) {
        javafx.application.Platform.runLater(() -> {
            if (getScene() == null || !isVisible() || !(getScene().lookup(".content-scroll") instanceof ScrollPane scroll)) return;
            scroll.applyCss(); scroll.layout();
            var content = scroll.getContent();
            double viewport = scroll.getViewportBounds().getHeight(), overflow = content.getBoundsInLocal().getHeight() - viewport;
            if (overflow <= 0) return;
            var bounds = content.sceneToLocal(target.localToScene(target.getBoundsInLocal()));
            double top = scroll.getVvalue() * overflow;
            if (bounds.getMinY() >= top && bounds.getMaxY() <= top + viewport) return;
            double wanted = Math.min(bounds.getMinY() - 12, Math.max(top, bounds.getMaxY() + 12 - viewport));
            scroll.setVvalue(Math.max(0, Math.min(1, wanted / overflow)));
        });
    }

    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }

    /** A rounded action in the theme colour (primary) or outlined in it (secondary), with its icon before or after the text. */
    private Button action(String key, String id, String icon, boolean primary, boolean iconAfter) {
        var button = texts.button(key); button.setId(id);
        button.getStyleClass().addAll(primary ? "primary-button" : "secondary-button", "home-action");
        if (!primary) button.getStyleClass().add("home-outline");
        button.setGraphic(AppIcons.slot(icon, "home-button-icon", 16));
        if (iconAfter) button.setContentDisplay(ContentDisplay.RIGHT);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static StackPane iconBox(String icon, double size) {
        var box = new StackPane(AppIcons.outline(icon, "home-icon")); box.getStyleClass().add("home-icon-box");
        box.setMinSize(size, size); box.setPrefSize(size, size); box.setMaxSize(size, size);
        return box;
    }

    /** Called with the active user's workspace; until then (or without a user) Home shows no session cards. */
    void connect(Links links) { this.links = links; }

    /** Re-reads the active user's sessions whenever Home is shown, so the cards follow changes made on other pages. */
    void refresh() {
        if (links == null || disposed) return;
        long ticket = ++generation;
        var app = links.app();
        links.operations().run(() -> app.sessions().search(""), result -> {
            if (disposed || ticket != generation) return;
            // A storage failure shows no session card rather than an invented one; the pages themselves report errors.
            show(result.isSuccess() ? Highlights.of(result.value().orElseThrow()) : Highlights.NONE);
        });
    }

    /** Builds only the cards that have a session; each card grows to share the row equally. */
    void show(Highlights highlights) {
        var cards = new ArrayList<Region>();
        highlights.unfinished().ifPresent(session -> cards.add(continueCard(session)));
        highlights.afterShoot().ifPresent(session -> cards.add(afterCard(session)));
        sessions.getChildren().setAll(cards);
        boolean any = !cards.isEmpty();
        sessions.setVisible(any); sessions.setManaged(any);
    }

    private VBox continueCard(PhotographySession session) {
        var step = Step.of(session); UUID id = session.id();
        var open = action("home.continue.action", "home-continue-action", AppIcons.ARROW_RIGHT, true, true);
        open.setOnAction(event -> { if (links != null) links.continueSession().accept(id); });
        return sessionCard("home-continue", "home.continue.heading", AppIcons.HISTORY, true,
            () -> session.status().equals("PLANNED") ? localization.text("session.state.PLANNED") : localization.text("home.status.preparing"),
            session.title(), () -> next(step), () -> metadata(subject(session).orElse(session.date().toString()), stage(step)), open);
    }

    private VBox afterCard(PhotographySession session) {
        boolean reviewed = session.status().equals("REVIEWED"); UUID id = session.id();
        var open = action(reviewed ? "home.after.view" : "home.after.start", "home-review-action", AppIcons.ARROW_RIGHT, false, true);
        open.setOnAction(event -> { if (links != null) links.reviewSession().accept(id); });
        return sessionCard("home-after", "home.after.heading", AppIcons.TREND, false,
            () -> reviewed ? localization.text("session.state.REVIEWED") : localization.text("home.status.toReview"),
            session.title(),
            () -> reviewed ? localization.text("home.after.saved")
                : session.statistics().isPresent() ? localization.text("home.after.review") : localization.text("home.after.import"),
            () -> metadata(subject(session).orElse(""), localization.text("home.after.date", session.date().toString())), open);
    }

    private VBox sessionCard(String id, String heading, String icon, boolean accentBadge, Supplier<String> status, String title,
            Supplier<String> description, Supplier<String> metadata, Button action) {
        var name = single(texts.label(heading, "home-card-heading")); name.setId(id + "-heading");
        name.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(name, Priority.ALWAYS);
        var badge = FeaturePane.label("", "home-pill"); badge.setId(id + "-status"); badge.setWrapText(false); badge.setMinWidth(Region.USE_PREF_SIZE);
        if (!accentBadge) badge.getStyleClass().add("home-pill-neutral");
        texts.bind(badge.textProperty(), status);
        var header = new HBox(12, iconBox(icon, 34), name, badge); header.setAlignment(Pos.CENTER_LEFT);
        var shoot = single(FeaturePane.label(title, "home-session-title")); shoot.setId(id + "-title");
        var next = FeaturePane.label("", "home-session-description"); next.setId(id + "-description"); texts.bind(next.textProperty(), description);
        var details = single(FeaturePane.label("", "home-session-meta")); details.setId(id + "-meta"); texts.bind(details.textProperty(), metadata);
        details.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(details, Priority.ALWAYS);
        var footer = new HBox(12, details, action); footer.setAlignment(Pos.CENTER_LEFT);
        // Keeps the footer on the bottom edge when the neighbouring card is taller.
        var fill = new Region(); fill.setMinHeight(14); VBox.setVgrow(fill, Priority.ALWAYS);
        VBox.setMargin(shoot, new Insets(14, 0, 0, 0)); VBox.setMargin(next, new Insets(6, 0, 0, 0));
        var card = new VBox(0, header, shoot, next, fill, footer);
        card.setId(id); card.getStyleClass().addAll("surface-panel", "home-card", "home-session");
        card.setPrefWidth(0); card.setMinWidth(0); card.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    /** One line that shortens with an ellipsis; the full text stays readable as a tooltip and as the accessible name. */
    private static Label single(Label label) {
        label.setWrapText(false); label.setMinWidth(0); label.setTextOverrun(OverrunStyle.ELLIPSIS); FxLocalizer.tooltipWhenClipped(label);
        label.accessibleTextProperty().bind(label.textProperty());
        return label;
    }

    private String next(Step step) {
        return switch (step) {
            case REQUIREMENT -> localization.text("home.next.requirement");
            case PROFILE -> localization.text("home.next.profile");
            case PLAN -> localization.text("home.next.plan");
            case SHOOT -> localization.text("home.next.shoot");
        };
    }
    /** The page that holds the next step, by its navigation name. */
    private String stage(Step step) {
        return switch (step) {
            case REQUIREMENT -> localization.text("feature.F03.title");
            case PROFILE -> localization.text("feature.F06.title");
            case PLAN -> localization.text("feature.F10.title");
            case SHOOT -> localization.text("home.stage.shoot");
        };
    }
    /** The confirmed requirement's subject, when there is one; nothing is inferred from the title or notes. */
    private static Optional<String> subject(PhotographySession session) {
        return session.requirements().filter(ShootRequirement::confirmed).map(ShootRequirement::subject).map(String::strip).filter(text -> !text.isEmpty());
    }
    private static String metadata(String... parts) {
        return String.join(" · ", Arrays.stream(parts).filter(part -> part != null && !part.isBlank()).toList());
    }

    void user(Optional<com.photopilot.model.LocalProfile> user) {
        texts.bind(greeting.textProperty(), () -> user.map(p -> texts.service().text("home.welcome", p.displayName())).orElseGet(() -> texts.service().text("profile.unavailable")));
    }
    void avatar(String name, Optional<byte[]> bytes) { avatar.getChildren().setAll(UserAvatar.portrait(name, bytes, 52)); }
    void dispose() { disposed = true; texts.close(); }
}
