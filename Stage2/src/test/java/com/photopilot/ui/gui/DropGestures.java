package com.photopilot.ui.gui;

import com.sun.javafx.scene.input.DragboardHelper;
import com.sun.javafx.tk.TKClipboard;
import com.sun.javafx.tk.Toolkit;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

/**
 * Drag events for tests, on the JavaFX thread. The events are the ones the toolkit delivers for a native drop, with a
 * real Dragboard that holds a file list offered for copy, move and link as File Explorer offers it (and move as the
 * gesture's default, as for a drag on the same drive). They are fired directly at the target node: the operating
 * system's part of a drag (the mouse and Explorer itself) cannot be automated here.
 */
final class DropGestures {
    private DropGestures() { }

    static Dragboard files(List<File> files) { ClipboardContent content = new ClipboardContent(); content.putFiles(files); return board(content); }
    static Dragboard text(String text) { ClipboardContent content = new ClipboardContent(); content.putString(text); return board(content); }

    private static Dragboard board(ClipboardContent content) {
        TKClipboard local = Toolkit.getToolkit().createLocalClipboard();
        TKClipboard peer = (TKClipboard) Proxy.newProxyInstance(TKClipboard.class.getClassLoader(), new Class<?>[] {TKClipboard.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getTransferModes")) return EnumSet.allOf(TransferMode.class);
                try { return method.invoke(local, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        Dragboard board = DragboardHelper.createDragboard(peer);
        board.setContent(content);
        return board;
    }

    /** Fires one drag event at the node, as if the pointer were at its centre, and returns it (its state is shared with the delivered copies). */
    static DragEvent fire(Node target, EventType<DragEvent> type, Dragboard board) {
        var bounds = target.getLayoutBounds();
        var screen = target.localToScreen(bounds.getCenterX(), bounds.getCenterY());
        DragEvent event = new DragEvent(target, target, type, board, bounds.getCenterX(), bounds.getCenterY(),
            screen == null ? 0 : screen.getX(), screen == null ? 0 : screen.getY(), TransferMode.MOVE, null, null, null);
        Event.fireEvent(target, event);
        return event;
    }

    /** Enter and move over the node; returns the last DRAG_OVER (accepted or not). */
    static DragEvent hover(Node target, Dragboard board) {
        fire(target, DragEvent.DRAG_ENTERED, board);
        fire(target, DragEvent.DRAG_OVER, board);
        return fire(target, DragEvent.DRAG_OVER, board);
    }

    /** A complete gesture: enter, move, release (only when the drop was accepted, as the toolkit does) and exit. Returns whether the drop completed. */
    static boolean drop(Node target, Dragboard board) {
        boolean accepted = hover(target, board).isAccepted();
        boolean completed = accepted && fire(target, DragEvent.DRAG_DROPPED, board).isDropCompleted();
        fire(target, DragEvent.DRAG_EXITED, board);
        return completed;
    }
}
