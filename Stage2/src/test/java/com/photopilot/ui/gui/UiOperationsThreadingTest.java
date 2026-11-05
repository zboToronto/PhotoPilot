package com.photopilot.ui.gui;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AUD-008 evidence: a slow operation (such as a Gemini request) never blocks the JavaFX thread, while later work of the
 * same window waits in order behind it and is counted as busy, which profile switching relies on.
 */
class UiOperationsThreadingTest {
    @Test void slowWorkKeepsTheFxThreadResponsiveAndLaterWorkRunsInOrderAfterIt() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        var operations = new UiOperations();
        try {
            var release = new CountDownLatch(1); var order = Collections.synchronizedList(new ArrayList<String>());
            var started = new CountDownLatch(1); var secondDone = new CountDownLatch(1);
            operations.execute(() -> { order.add("slow:start"); started.countDown(); awaitQuietly(release); order.add("slow:end"); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            operations.execute(() -> { order.add("quick"); secondDone.countDown(); });

            var fxRan = new CountDownLatch(1); var fxThread = new AtomicBoolean();
            Platform.runLater(() -> { fxThread.set(Platform.isFxApplicationThread()); fxRan.countDown(); });
            assertTrue(fxRan.await(2, TimeUnit.SECONDS), "the FX thread stays free while the worker is occupied");
            assertTrue(fxThread.get());
            assertFalse(secondDone.await(300, TimeUnit.MILLISECONDS), "same-window work queues behind the slow operation");
            assertTrue(operations.busy(), "queued work counts as busy for profile switching");

            release.countDown();
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("slow:start", "slow:end", "quick"), order, "operations of one window are applied in submission order");
            for (int i = 0; i < 50 && operations.busy(); i++) Thread.sleep(20);
            assertFalse(operations.busy());
        } finally { operations.shutdownNow(); }
    }
    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
