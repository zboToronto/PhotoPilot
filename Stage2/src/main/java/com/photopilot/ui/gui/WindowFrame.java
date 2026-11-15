package com.photopilot.ui.gui;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Optional;
import javafx.stage.Window;

/**
 * The outer window frame that Windows draws. The main window is natively decorated, so its rounded corners, thin border
 * and shadow come from the Desktop Window Manager (DWM); PhotoPilot defines no radius or frame style of its own. The
 * borderless workspace chooser asks DWM for that same corner policy instead of painting an imitation, so it gets the
 * system's radius, anti-aliased clipping and border at every display scale: rounded while restored, square while it
 * fills the screen, as the system treats the main window when it is restored or maximized.
 *
 * <p>DwmSetWindowAttribute is reached through java.lang.foreign, a preview API in Java 21 (final from Java 22), by
 * reflection, so the build needs no preview flags. Where the request cannot apply (macOS, Linux, Windows 10, no window
 * handle) nothing changes and the window keeps its square frame; the outcome is reported, never thrown.
 */
final class WindowFrame {
    /** What became of the latest request: the system applied it, declined it (no rounded windows), or it cannot apply here. */
    enum Result { APPLIED, DECLINED, UNAVAILABLE }

    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33, DWMWCP_DONOTROUND = 1, DWMWCP_ROUND = 2;

    private WindowFrame() { }

    /** Requests the system's rounded corners ({@code round}) or square corners for a showing window. */
    static Result corners(Window window, boolean round) {
        var dwm = Dwm.INSTANCE;
        if (dwm == null || !window.isShowing()) return Result.UNAVAILABLE;
        try {
            long handle = handle(window);
            if (handle == 0) return Result.UNAVAILABLE;
            return dwm.set(handle, DWMWA_WINDOW_CORNER_PREFERENCE, round ? DWMWCP_ROUND : DWMWCP_DONOTROUND) == 0 ? Result.APPLIED : Result.DECLINED;
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable unavailable) {
            return Result.UNAVAILABLE;
        }
    }

    /** The native window handle (HWND on Windows) that JavaFX created for the window. */
    private static long handle(Window window) throws ReflectiveOperationException {
        Object peer = Class.forName("com.sun.javafx.stage.WindowHelper").getMethod("getPeer", Window.class).invoke(null, window);
        return peer == null ? 0 : (long) Class.forName("com.sun.javafx.tk.TKStage").getMethod("getRawHandle").invoke(peer);
    }

    /** dwmapi's DwmSetWindowAttribute, bound once; null where it is not available. */
    private static final class Dwm {
        static final Dwm INSTANCE = load();
        private final MethodHandle setAttribute;
        private final Method ofAddress, ofConfined, allocate, setInt, close;
        private final Object intLayout;

        private static Dwm load() {
            if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
            try { return new Dwm(); } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private Dwm() throws ReflectiveOperationException {
            Class<?> linker = type("Linker"), arena = type("Arena"), lookup = type("SymbolLookup"), layout = type("MemoryLayout");
            Class<?> values = type("ValueLayout"), descriptor = type("FunctionDescriptor"), segment = type("MemorySegment");
            intLayout = values.getField("JAVA_INT").get(null);
            Object address = values.getField("ADDRESS").get(null);
            Object library = lookup.getMethod("libraryLookup", String.class, arena).invoke(null, "dwmapi", arena.getMethod("global").invoke(null));
            Object symbol = ((Optional<?>) lookup.getMethod("find", String.class).invoke(library, "DwmSetWindowAttribute")).orElseThrow();
            // HRESULT DwmSetWindowAttribute(HWND window, DWORD attribute, LPCVOID value, DWORD size)
            Object parameters = Array.newInstance(layout, 4);
            Array.set(parameters, 0, address); Array.set(parameters, 1, intLayout); Array.set(parameters, 2, address); Array.set(parameters, 3, intLayout);
            Object function = descriptor.getMethod("of", layout, parameters.getClass()).invoke(null, intLayout, parameters);
            Object options = Array.newInstance(type("Linker$Option"), 0);
            setAttribute = (MethodHandle) linker.getMethod("downcallHandle", segment, descriptor, options.getClass())
                .invoke(linker.getMethod("nativeLinker").invoke(null), symbol, function, options);
            ofAddress = segment.getMethod("ofAddress", long.class);
            ofConfined = arena.getMethod("ofConfined");
            allocate = type("SegmentAllocator").getMethod("allocate", layout);
            setInt = segment.getMethod("set", type("ValueLayout$OfInt"), long.class, int.class);
            close = arena.getMethod("close");
        }

        private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("java.lang.foreign." + name); }

        /** Sets a DWORD attribute of the window; returns the HRESULT (0 when the system accepted it). */
        int set(long window, int attribute, int value) throws Throwable {
            Object arena = ofConfined.invoke(null);
            try {
                Object cell = allocate.invoke(arena, intLayout);
                setInt.invoke(cell, intLayout, 0L, value);
                return (int) setAttribute.invokeWithArguments(ofAddress.invoke(null, window), attribute, cell, Integer.BYTES);
            } finally {
                close.invoke(arena);
            }
        }
    }
}
