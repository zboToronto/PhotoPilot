package com.photopilot.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * One HTTP exchange with a hard deadline and a bounded in-memory body, shared by the HTTP adapters of this package.
 * The JDK 21 client stops its request timeout once the response headers arrive, so with {@code HttpClient.send} a server
 * that sends headers and then stalls the body blocks the caller (the GUI's single worker) forever, and the in-memory body
 * handlers keep whatever a server sends. Here the whole exchange must end within the request timeout plus {@link #GRACE}
 * (then {@link HttpTimeoutException}, as for a late response); on that deadline or an interrupt (whose flag stays set) the
 * exchange is cancelled, which closes its connection. A status 200 body above the caller's limit fails with
 * {@link BodyTooLargeException}: at once when its Content-Length says so, otherwise as soon as the received bytes exceed
 * it. Other statuses keep their own error, so their body (only read for a short reason) is cut at the limit instead.
 */
final class BoundedHttp {
    /** Time for the body after the request timeout, which only covers the response headers. */
    static final Duration GRACE = Duration.ofSeconds(15);

    /** A status 200 body above the caller's limit; each adapter reports it as its existing unreadable-response error. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() { super("The response body exceeds its size limit"); }
    }

    private BoundedHttp() { }

    /**
     * Like {@code http.send(request, ofByteArray())}, with the same exceptions, plus the deadline and limit above.
     * The request must have a timeout.
     */
    static HttpResponse<byte[]> send(HttpClient http, HttpRequest request, long maxBytes) throws IOException, InterruptedException {
        Duration deadline = request.timeout().orElseThrow(() -> new IllegalArgumentException("The request has no timeout")).plus(GRACE);
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request, info -> new Limited(maxBytes, info.statusCode() == 200, announced(info)));
        try {
            return exchange.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            exchange.cancel(true);
            throw new HttpTimeoutException("No complete response within " + deadline.toSeconds() + " seconds");
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            // As HttpClient.send reports failures: I/O and argument/security problems as such, anything else as I/O.
            Throwable cause = failed.getCause();
            for (Throwable link = cause; link != null; link = link.getCause()) if (link instanceof BodyTooLargeException tooLarge) throw tooLarge;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof IllegalArgumentException || cause instanceof SecurityException) throw (RuntimeException) cause;
            throw new IOException(cause);
        }
    }

    /** The announced Content-Length, or -1 when absent or unreadable (the received bytes are still counted). */
    private static long announced(HttpResponse.ResponseInfo info) {
        try { return info.headers().firstValueAsLong("Content-Length").orElse(-1); } catch (NumberFormatException unreadable) { return -1; }
    }

    /** Collects at most {@code limit} bytes; beyond it the body is cancelled and fails (status 200) or ends there (others). */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit, announced;
        private final boolean strict;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Limited(long limit, boolean strict, long announced) { this.limit = limit; this.strict = strict; this.announced = announced; }

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (announced > limit) exceeded(); else subscription.request(Long.MAX_VALUE);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + (long) buffer.remaining() > limit) { exceeded(); return; }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }

        /** The outcome is fixed before cancelling, so an error the cancellation may cause cannot replace it. */
        private void exceeded() {
            if (strict) body.completeExceptionally(new BodyTooLargeException()); else body.complete(bytes.toByteArray());
            subscription.cancel();
        }
    }
}
