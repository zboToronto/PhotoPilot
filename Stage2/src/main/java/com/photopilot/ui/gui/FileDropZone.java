package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizedMessage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Reusable import area for files dragged in from the operating system (Windows File Explorer or another file manager),
 * through JavaFX's native drag and drop. It wraps a module's own import content (title, formats, its picker button),
 * keeps that content's look and size, and adds the drag states: the accent colour and a release hint while acceptable
 * files are over it, the danger colour and the reason while they are not, and the reason below the content after a
 * refused drop. It only decides whether files may be imported: how many, file or folder, extension, size and an
 * optional module check. What the files are for is the module's accept callback.
 *
 * <p>A module passes the result of its own picker to {@link #submit}, so picked and dropped files share one validation
 * and one handler. While the module is {@linkplain #setBusy busy} nothing is accepted. Only drags that carry files are
 * handled; other drag and drop in the window is left alone. Validation reads file attributes only, never contents.
 */
final class FileDropZone extends StackPane {
    /** What the zone shows: nothing is being dragged over it, acceptable files are, or files it will refuse are. */
    enum State { IDLE, ACCEPTING, REFUSING }
    /** Why files were refused. CHECK is the module's own check. */
    enum Problem { NO_FILES, MULTIPLE, DIRECTORY, EXTENSION, TOO_LARGE, INVALID_PATH, CHECK }
    /** A refused drop or choice; the file name is the number of files for NO_FILES and MULTIPLE. */
    record Rejection(Problem problem, String fileName, LocalizedMessage message) { }

    /** What a module accepts and what the zone says. The zone keeps a copy, so later changes do not affect it. */
    static final class Options {
        private final Set<String> extensions = new LinkedHashSet<>();
        private boolean multiple;
        private long maxBytes;
        private Function<Path, Optional<LocalizedMessage>> check = file -> Optional.empty();
        private LocalizedMessage release = LocalizedMessage.of("fileDrop.release"), busy = LocalizedMessage.of("fileDrop.busy");
        private final Map<Problem, Function<String, LocalizedMessage>> messages = new EnumMap<>(Problem.class);

        /** Accepted file name extensions, with or without the dot, in any case. None (the default) accepts any file. */
        Options extensions(Collection<String> values) {
            for (String value : values) {
                String extension = value.strip().toLowerCase(Locale.ROOT);
                if (extension.startsWith(".")) extension = extension.substring(1);
                if (!extension.isEmpty()) extensions.add(extension);
            }
            return this;
        }
        /** Several files in one drop. Off by default: one file at a time. */
        Options multiple(boolean value) { multiple = value; return this; }
        /** Largest accepted file in bytes. 0 (the default) leaves the size to the module. */
        Options maxBytes(long value) {
            if (value < 0) throw new IllegalArgumentException("maxBytes must not be negative");
            maxBytes = value; return this;
        }
        /** A further check per file, returning the reason to refuse it. Keep it cheap: it also runs while hovering. */
        Options check(Function<Path, Optional<LocalizedMessage>> value) { check = Objects.requireNonNull(value); return this; }
        /** Shown while acceptable files are over the zone. */
        Options release(LocalizedMessage value) { release = Objects.requireNonNull(value); return this; }
        /** Shown while files are over the zone and the module is busy. */
        Options busy(LocalizedMessage value) { busy = Objects.requireNonNull(value); return this; }
        /** Replaces the default text for a problem; the argument is the file name (the number of files for NO_FILES and MULTIPLE). */
        Options message(Problem problem, Function<String, LocalizedMessage> text) {
            if (problem == Problem.CHECK) throw new IllegalArgumentException("The module's check supplies its own message");
            messages.put(problem, Objects.requireNonNull(text)); return this;
        }
        private Options copy() {
            Options copy = new Options().extensions(extensions).multiple(multiple).maxBytes(maxBytes).check(check).release(release).busy(busy);
            copy.messages.putAll(messages);
            return copy;
        }
    }

    /** The files a drag or choice would import, or why they are refused. */
    private record Verdict(List<Path> files, Rejection rejection) {
        boolean refused() { return rejection != null; }
    }

    private static final PseudoClass ACCEPT = PseudoClass.getPseudoClass("drag-accept"), REFUSE = PseudoClass.getPseudoClass("drag-refuse");

    private final FxLocalizer texts;
    private final Options options;
    private final Consumer<List<Path>> accepted;
    private Consumer<Rejection> rejected = rejection -> { };
    private final VBox body;
    private final Label hint = FeaturePane.label("", "file-drop-hint"), error = FeaturePane.label("", "file-drop-error");
    private final BooleanProperty busy = new SimpleBooleanProperty(this, "busy");
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.IDLE);
    /** The verdict for the files over the zone, worked out once when they arrive; null while nothing is dragged over it. */
    private Verdict hovering;
    /** The last refused drop or choice, shown below the content until files are accepted. */
    private Rejection rejection;

    /**
     * @param texts    the owning view's localizer, so the zone follows its language without a subscription of its own
     * @param content  the module's import content, shown unchanged while nothing is dragged over the zone
     * @param accepted receives validated files on the JavaFX thread (exactly one unless multiple files are enabled)
     */
    FileDropZone(FxLocalizer texts, Node content, Options options, Consumer<List<Path>> accepted) {
        this.texts = texts; this.options = options.copy(); this.accepted = Objects.requireNonNull(accepted);
        getStyleClass().add("file-drop-zone");
        setMinWidth(0);
        centre(error);
        body = new VBox(8, content, error);
        body.setAlignment(Pos.CENTER); body.setMinWidth(0);
        // The hint fills the zone without adding to its size: while it shows, the content keeps its place, invisibly.
        centre(hint);
        hint.setMinSize(0, 0); hint.setPrefSize(0, 0); hint.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        hint.setMouseTransparent(true);
        getChildren().addAll(body, hint);
        setOnDragEntered(this::entered);
        setOnDragOver(this::over);
        setOnDragExited(this::exited);
        setOnDragDropped(this::dropped);
        busy.addListener((property, was, now) -> show());
        texts.onRefresh(this::show);
    }
    private static void centre(Label label) { label.setAlignment(Pos.CENTER); label.setStyle("-fx-text-alignment: center;"); label.setMinWidth(0); }

    /** Called with each refused drop or choice, after the zone shows the reason. */
    void onRejected(Consumer<Rejection> action) { rejected = Objects.requireNonNull(action); }
    BooleanProperty busyProperty() { return busy; }
    boolean isBusy() { return busy.get(); }
    /** While busy (for example processing the previous file) the zone accepts nothing. */
    void setBusy(boolean value) { busy.set(value); }
    ReadOnlyObjectProperty<State> stateProperty() { return state.getReadOnlyProperty(); }
    State state() { return state.get(); }
    Optional<Rejection> rejection() { return Optional.ofNullable(rejection); }

    /**
     * The entry point for the module's own picker: validates the chosen files like a drop and hands them to the accept
     * callback, or shows why they were refused. Returns whether they were accepted; nothing is accepted while busy.
     */
    boolean submit(List<Path> files) { return apply(inspect(files)); }

    // ---- Native drag and drop ------------------------------------------------------------------------------------

    private void entered(DragEvent event) {
        if (!event.getDragboard().hasFiles()) return;
        hovering = inspectDropped(event.getDragboard().getFiles());
        show(); event.consume();
    }
    private void over(DragEvent event) {
        if (!event.getDragboard().hasFiles()) return;
        if (hovering == null) { hovering = inspectDropped(event.getDragboard().getFiles()); show(); }
        // Copy only: accepting a move would let File Explorer delete the original after the drop. Refused files are
        // still accepted as a gesture so that the drop can explain why; nothing is accepted while busy.
        if (!isBusy()) event.acceptTransferModes(TransferMode.COPY);
        event.consume();
    }
    private void exited(DragEvent event) {
        if (hovering == null) return;
        hovering = null; show(); event.consume();
    }
    private void dropped(DragEvent event) {
        if (!event.getDragboard().hasFiles()) return;
        hovering = null;
        event.setDropCompleted(apply(inspectDropped(event.getDragboard().getFiles())));
        event.consume();
    }

    // ---- Validation ----------------------------------------------------------------------------------------------

    /** Files from the drag board; a name the platform cannot express as a path is refused. */
    private Verdict inspectDropped(List<File> files) {
        if (!options.multiple && files.size() > 1) return refuse(Problem.MULTIPLE, Integer.toString(files.size()));
        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            try { paths.add(file.toPath()); }
            catch (InvalidPathException invalid) { return refuse(Problem.INVALID_PATH, file.getName()); }
        }
        return inspect(paths);
    }

    private Verdict inspect(List<Path> files) {
        if (files.isEmpty()) return refuse(Problem.NO_FILES, "0");
        if (!options.multiple && files.size() > 1) return refuse(Problem.MULTIPLE, Integer.toString(files.size()));
        for (Path file : files) {
            String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
            if (directory(file)) return refuse(Problem.DIRECTORY, name);
            if (!options.extensions.isEmpty() && !options.extensions.contains(extension(name))) return refuse(Problem.EXTENSION, name);
            if (options.maxBytes > 0 && size(file) > options.maxBytes) return refuse(Problem.TOO_LARGE, name);
            Optional<LocalizedMessage> problem = options.check.apply(file);
            if (problem.isPresent()) return new Verdict(List.of(), new Rejection(Problem.CHECK, name, problem.get()));
        }
        return new Verdict(List.copyOf(files), null);
    }
    private static boolean directory(Path file) {
        try { return Files.isDirectory(file); } catch (SecurityException denied) { return false; }
    }
    /** -1 when the size cannot be read; the module's own reading then reports the file. */
    private static long size(Path file) {
        try { return Files.size(file); } catch (IOException | SecurityException unreadable) { return -1; }
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Verdict refuse(Problem problem, String name) {
        var custom = options.messages.get(problem);
        return new Verdict(List.of(), new Rejection(problem, name, custom != null ? custom.apply(name) : defaultMessage(problem, name)));
    }
    private LocalizedMessage defaultMessage(Problem problem, String name) {
        return switch (problem) {
            case NO_FILES -> LocalizedMessage.of("fileDrop.error.noFiles");
            case MULTIPLE -> LocalizedMessage.of("fileDrop.error.multiple", name);
            case DIRECTORY -> LocalizedMessage.of("fileDrop.error.directory", name);
            case EXTENSION -> LocalizedMessage.of("fileDrop.error.extension", name,
                String.join(" / ", options.extensions.stream().map(extension -> extension.toUpperCase(Locale.ROOT)).toList()));
            case TOO_LARGE -> LocalizedMessage.of("fileDrop.error.tooLarge", name, limit(options.maxBytes));
            case INVALID_PATH, CHECK -> LocalizedMessage.of("fileDrop.error.invalidPath", name);
        };
    }
    /** The size limit for a message: bytes, KB or MB with at most one decimal. */
    static String limit(long bytes) {
        if (bytes < 1024) return bytes + " B";
        boolean kilobytes = bytes < 1024 * 1024;
        double value = bytes / (kilobytes ? 1024.0 : 1024.0 * 1024.0);
        String number = value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
        return number + (kilobytes ? " KB" : " MB");
    }

    // ---- State ---------------------------------------------------------------------------------------------------

    private boolean apply(Verdict verdict) {
        if (isBusy()) return false;
        if (verdict.refused()) {
            rejection = verdict.rejection(); show();
            rejected.accept(rejection);
            return false;
        }
        rejection = null; show();
        accepted.accept(verdict.files());
        return true;
    }

    private void show() {
        State now = hovering == null ? State.IDLE : isBusy() || hovering.refused() ? State.REFUSING : State.ACCEPTING;
        state.set(now);
        pseudoClassStateChanged(ACCEPT, now == State.ACCEPTING);
        pseudoClassStateChanged(REFUSE, now == State.REFUSING);
        LocalizedMessage shown = switch (now) {
            case IDLE -> null;
            case ACCEPTING -> options.release;
            case REFUSING -> isBusy() ? options.busy : hovering.rejection().message();
        };
        hint.setText(shown == null ? "" : shown.render(texts.service()));
        hint.setVisible(now != State.IDLE);
        body.setVisible(now == State.IDLE);
        boolean failed = rejection != null;
        error.setText(failed ? rejection.message().render(texts.service()) : "");
        error.setVisible(failed); error.setManaged(failed);
    }
}
