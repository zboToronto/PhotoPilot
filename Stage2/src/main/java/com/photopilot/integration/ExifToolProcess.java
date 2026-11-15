package com.photopilot.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One long-running ExifTool, driven with the official {@code -stay_open True -@ -} protocol: each request writes its
 * arguments one per line to stdin and ends with {@code -echo4 {readyN}} and {@code -executeN}. ExifTool prints
 * {@code {readyN}} on stdout when it is done, and the {@code -echo4} text marks the end of that request's stderr.
 *
 * <ul>
 *   <li>Lazy: nothing starts until the first request; later requests reuse the process.</li>
 *   <li>One request at a time (requests from several threads queue on a lock), so answers never mix.</li>
 *   <li>stdout and stderr are drained by their own threads, so a full pipe cannot deadlock either side.</li>
 *   <li>A request that exceeds the timeout, or a process that dies, is reported and the process is stopped
 *       (including any child process); the next request starts a fresh one. A pipe that is already broken when a
 *       request is sent is restarted and the request retried once.</li>
 *   <li>{@link #close()} asks ExifTool to exit ({@code -stay_open False}), then stops it if it does not.</li>
 * </ul>
 * Arguments are passed to ExifTool, never to a shell, and may not contain line breaks.
 */
public final class ExifToolProcess implements AutoCloseable {
    /** Supplies the program to run; resolved on the first request. */
    @FunctionalInterface public interface CommandSource { ExifToolRuntime.Command resolve() throws ExifToolException; }
    /** One request's output: stdout (without the ready marker) and stderr lines. */
    public record Response(int id, String stdout, List<String> stderr) {
        public Response { Objects.requireNonNull(stdout); stderr = List.copyOf(stderr); }
    }

    private static final Duration CLOSE_GRACE = Duration.ofSeconds(3);
    private final CommandSource source;
    private final Duration timeout;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;
    private volatile Running running;
    private int nextId;
    private int starts;

    public ExifToolProcess(CommandSource source, Duration timeout) {
        this.source = Objects.requireNonNull(source);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
    }

    public Response execute(List<String> arguments) throws ExifToolException {
        for (String argument : arguments) {
            if (argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) throw new IllegalArgumentException("ExifTool arguments cannot contain line breaks");
            String option = argument.strip().toLowerCase(java.util.Locale.ROOT);
            if (option.equals("-stay_open") || option.startsWith("-execute") || option.equals("-@") || option.equals("-common_args"))
                throw new IllegalArgumentException("Reserved ExifTool option: " + argument);
        }
        if (closed) throw new ExifToolException(ExifToolException.Kind.CLOSED, "ExifTool has been closed");
        lock.lock();
        try {
            for (int attempt = 0; ; attempt++) {
                if (closed) throw new ExifToolException(ExifToolException.Kind.CLOSED, "ExifTool has been closed");
                Running process = ensureRunning();
                int id = ++nextId;
                try {
                    process.send(arguments, id);
                } catch (IOException broken) {
                    discard(process);
                    if (attempt == 0) continue; // the process had died since the last request: start a new one once
                    throw new ExifToolException(ExifToolException.Kind.TERMINATED, "ExifTool stopped accepting requests", broken);
                }
                try {
                    return process.await(id, System.nanoTime() + timeout.toNanos());
                } catch (ExifToolException failure) {
                    discard(process);
                    if (closed) throw new ExifToolException(ExifToolException.Kind.CLOSED, "ExifTool has been closed", failure);
                    throw failure;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Whether a process is currently running (it is started by the first request, not before). */
    public boolean running() { Running process = running; return process != null && process.process.isAlive(); }
    /** How many processes this instance has started; package-private test observation. */
    int starts() { return starts; }
    /** The current process handle; package-private test observation. */
    ProcessHandle handle() { Running process = running; return process == null ? null : process.process.toHandle(); }

    @Override public void close() {
        closed = true;
        boolean locked = false;
        try { locked = lock.tryLock(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try {
            Running process = running;
            if (process == null) return;
            if (locked) process.stop(); else process.kill(); // a request is still running: stop it rather than wait
            running = null;
        } finally {
            if (locked) lock.unlock();
        }
    }

    private Running ensureRunning() throws ExifToolException {
        Running current = running;
        if (current != null && current.process.isAlive()) return current;
        if (current != null) discard(current);
        ExifToolRuntime.Command command = source.resolve();
        List<String> line = new ArrayList<>(command.command());
        line.addAll(List.of("-stay_open", "True", "-@", "-"));
        ProcessBuilder builder = new ProcessBuilder(line).directory(command.workingDirectory().toFile());
        // A Perl installed elsewhere must not change how the bundled runtime loads modules or decodes its input.
        builder.environment().keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("PERL"));
        try {
            Running started = new Running(builder.start());
            starts++;
            running = started;
            return started;
        } catch (IOException | SecurityException | UnsupportedOperationException error) {
            throw new ExifToolException(ExifToolException.Kind.STARTUP, "ExifTool could not be started", error);
        }
    }

    private void discard(Running process) {
        process.kill();
        if (running == process) running = null;
    }

    private record Line(String text, boolean end) { }

    private static final class Running {
        final Process process;
        final Writer stdin;
        final BlockingQueue<Line> stdout = new LinkedBlockingQueue<>(), stderr = new LinkedBlockingQueue<>();
        final Thread outReader, errReader;

        Running(Process process) {
            this.process = process;
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            outReader = drain(process.getInputStream(), stdout, "exiftool-stdout");
            errReader = drain(process.getErrorStream(), stderr, "exiftool-stderr");
        }
        private static Thread drain(InputStream stream, BlockingQueue<Line> queue, String name) {
            Thread thread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    for (String text; (text = reader.readLine()) != null; ) queue.add(new Line(text, false));
                } catch (IOException ignored) {
                    // The stream closes when the process ends or is stopped.
                } finally {
                    queue.add(new Line("", true));
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        void send(List<String> arguments, int id) throws IOException {
            StringBuilder request = new StringBuilder();
            for (String argument : arguments) request.append(argument).append('\n');
            request.append("-echo4\n{ready").append(id).append("}\n-execute").append(id).append('\n');
            stdin.write(request.toString());
            stdin.flush();
        }

        Response await(int id, long deadline) throws ExifToolException {
            String marker = "{ready" + id + "}";
            List<String> out = collect(stdout, marker, deadline, "stdout");
            List<String> err = collect(stderr, marker, deadline, "stderr");
            return new Response(id, String.join("\n", out), err);
        }
        private List<String> collect(BlockingQueue<Line> queue, String marker, long deadline, String stream) throws ExifToolException {
            List<String> lines = new ArrayList<>();
            while (true) {
                long remaining = deadline - System.nanoTime();
                Line line;
                try {
                    line = remaining <= 0 ? null : queue.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ExifToolException(ExifToolException.Kind.TIMEOUT, "Interrupted while waiting for ExifTool");
                }
                if (line == null) throw new ExifToolException(ExifToolException.Kind.TIMEOUT, "ExifTool did not answer in time");
                if (line.end()) throw new ExifToolException(ExifToolException.Kind.TERMINATED, "ExifTool ended unexpectedly (" + stream + " closed)");
                if (line.text().strip().equals(marker)) return lines;
                lines.add(line.text());
            }
        }

        /** Graceful exit: -stay_open False, then wait briefly, then stop. */
        void stop() {
            try {
                stdin.write("-stay_open\nFalse\n");
                stdin.flush();
                stdin.close();
                if (process.waitFor(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) { join(); return; }
            } catch (IOException ignored) {
                // Already gone or the pipe broke: fall through to stop it.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            kill();
        }
        /** Stops the process and anything it started, and releases its pipes. */
        void kill() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try { process.waitFor(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { stdin.close(); } catch (IOException ignored) { }
            join();
        }
        private void join() {
            try { outReader.join(1000); errReader.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
