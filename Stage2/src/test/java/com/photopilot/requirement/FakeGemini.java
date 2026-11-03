package com.photopilot.requirement;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Offline loopback stand-in for the Gemini REST endpoint; queued replies, recorded request bodies. No live calls. */
public final class FakeGemini implements AutoCloseable {
    public static final String KEY = "offline-gemini-test-key";
    private final HttpServer server;
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();
    public final List<String> requests = new CopyOnWriteArrayList<>();

    public FakeGemini() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status; String body;
            synchronized (this) { status = statuses.isEmpty() ? 503 : statuses.poll()[0]; body = bodies.isEmpty() ? "" : bodies.poll(); }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
    public String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"; }
    /** Environment for a bootstrap that talks only to this server with a fake key. */
    public Map<String, String> environment(java.nio.file.Path root) {
        return Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(),
            "LLM_API_KEY", KEY, "GEMINI_ENDPOINT", endpoint(), "GEMINI_MODEL", "test-model");
    }
    /** Queues a successful generateContent reply whose generated text is {@code text}. */
    public synchronized FakeGemini reply(String text) {
        JsonObject part = new JsonObject(); part.addProperty("text", text);
        JsonArray parts = new JsonArray(); parts.add(part);
        JsonObject content = new JsonObject(); content.add("parts", parts);
        JsonObject candidate = new JsonObject(); candidate.add("content", content);
        JsonArray candidates = new JsonArray(); candidates.add(candidate);
        JsonObject root = new JsonObject(); root.add("candidates", candidates);
        statuses.add(new int[]{200}); bodies.add(root.toString()); return this;
    }
    public synchronized FakeGemini status(int code, String body) { statuses.add(new int[]{code}); bodies.add(body); return this; }

    /** Representative structured output for the Stage 2 airshow example; date and location are deliberately absent. */
    public static final String AIRSHOW = """
        {"subject":"Aircraft at an airshow","goals":["Aircraft in flight at sunset"],"date":null,"startTime":null,
         "timeWindow":"around sunset","location":null,"durationMinutes":180,"cameraCount":2,"lensChangesAllowed":false,
         "maxWeightGrams":null,"portability":"minimize weight","gearConstraints":["avoid lens changes"],
         "priorities":[{"name":"low weight","rank":1}],"fixedConstraints":[],
         "clarificationQuestions":["Which date is the airshow?","Where is the airshow taking place?"],"flaggedFields":[]}
        """;
    public static final String AIRSHOW_REQUEST = "I want to photograph aircraft at an airshow around sunset for three hours. "
        + "I prefer carrying two camera bodies, minimizing weight, and avoiding lens changes.";

    @Override public void close() { server.stop(0); }
}
