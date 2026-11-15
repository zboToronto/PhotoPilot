package com.photopilot.service;

import com.google.gson.JsonParser;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;
import com.photopilot.config.LLMConfiguration;
import com.photopilot.integration.GeminiAdapter;
import com.photopilot.integration.LLMClient;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.Equipment;
import com.photopilot.model.GearInventory;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.MiniAiService.Conversation;
import com.photopilot.service.MiniAiService.Exchange;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Home Mini AI service with scripted model replies and loopback fakes; no live provider request is made. */
class MiniAiServiceTest {
    /** Monday 2026-11-09 18:30 local time. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T23:30:00Z"), ZoneOffset.ofHours(-5));
    @TempDir Path root;
    private final List<AgentContext> sent = new ArrayList<>();
    private final Deque<Result<ModelReply>> replies = new ArrayDeque<>();
    private final LLMClient scripted = context -> { sent.add(context); return replies.isEmpty() ? Result.failureKey("ai.error.serviceUnavailable", "503") : replies.poll(); };

    private MiniAiService service(LLMClient model, InventoryRepository inventory) {
        var sessions = new JsonSessionRepository(root.resolve("sessions"));
        var recognition = new GearRecognitionService(GearFixtures.agent(root, new ArrayList<>(), context -> Result.failureKey("ai.error.network")),
            name -> Result.failureKey("ai.error.network"));
        return new MiniAiService(model, new InventoryService(inventory, sessions, recognition), CLOCK);
    }
    private MiniAiService service(List<Equipment> gear) { return service(scripted, inventory(gear)); }
    private MiniAiService service() { return service(List.of()); }
    private static InventoryRepository inventory(List<Equipment> gear) {
        return new InventoryRepository() {
            public Result<GearInventory> load() { return Result.success(new GearInventory(gear)); }
            public Result<GearInventory> save(GearInventory inventory) { return Result.failureKey("error.detail", "read only"); }
        };
    }
    private void reply(String text) { replies.add(Result.success(new ModelReply(Optional.of(text), List.of(), List.of()))); }
    private static String evidence(AgentContext context, String field) {
        var json = JsonParser.parseString(context.evidenceJson()).getAsJsonObject();
        return json.has(field) ? json.get(field).toString() : null;
    }

    @Test void emptyAndOverLongQuestionsAreRejectedBeforeAnyRequest() {
        for (String blank : Arrays.asList(null, "", "   \n\t  ")) {
            assertTrue(MiniAiService.validate(blank).hasError("home.ai.error.empty"));
            assertTrue(service().ask(Conversation.EMPTY, blank).hasError("home.ai.error.empty"));
        }
        assertEquals("拍".repeat(300), MiniAiService.validate("  " + "拍".repeat(300) + "\n").value().orElseThrow(), "300 characters are accepted, stripped");
        assertEquals("📷".repeat(300), MiniAiService.validate("📷".repeat(300)).value().orElseThrow(), "characters are counted, not UTF-16 units");
        var tooLong = service().ask(Conversation.EMPTY, "a".repeat(301));
        assertTrue(tooLong.hasError("home.ai.error.tooLong"));
        assertTrue(tooLong.errors().getFirst().contains("300") && tooLong.errors().getFirst().contains("301"), tooLong.errors().toString());
        assertEquals("first line\nsecond line", MiniAiService.validate("first line\nsecond line").value().orElseThrow(), "multiline text is kept");
        assertTrue(sent.isEmpty(), "nothing was sent");
    }

    @Test void requestCarriesTheMiniAiInstructionCapConversationAndQuestionOnly() {
        reply("A 50mm lens is great for isolating lit storefronts and reflections. Focus on strong compositions rather than the whole skyline.");
        var conversation = Conversation.EMPTY.with(new Exchange("What should I shoot tonight?", "Try photographing city lights and reflections."));
        var answer = service().ask(conversation, "  What if I only have a 50mm lens?  ");
        assertEquals("A 50mm lens is great for isolating lit storefronts and reflections. Focus on strong compositions rather than the whole skyline.",
            answer.value().orElseThrow());
        var request = sent.getFirst();
        assertEquals(List.of("What if I only have a 50mm lens?"), request.messages());
        assertTrue(request.taskInstruction().startsWith("You are PhotoPilot Mini AI"), request.taskInstruction());
        for (String rule : List.of("no more than 150 characters", "no more than 80 words", "one to three concise sentences", "Inspiration page",
                "Never pretend that unavailable tools", "never assume other cameras or lenses", "Reply in the same language"))
            assertTrue(request.taskInstruction().contains(rule), rule);
        assertEquals(MiniAiService.MAX_TOKENS, request.maxOutputTokens());
        assertTrue(request.responseSchema().isBlank(), "plain text, not a structured schema");
        assertTrue(request.approvedImage().isEmpty() && request.history().isEmpty() && request.toolResults().isEmpty());
        assertEquals("[{\"user\":\"What should I shoot tonight?\",\"assistant\":\"Try photographing city lights and reflections.\"}]", evidence(request, "conversation"));
        assertEquals("\"Monday 2026-11-09 18:30\"", evidence(request, "localTime"));
        assertEquals(Set.of("conversation", "localTime"), JsonParser.parseString(request.evidenceJson()).getAsJsonObject().keySet(),
            "no profile, location, inventory or other application data is sent");
    }

    @Test void conversationKeepsOrderStaysBoundedAndResets() {
        var conversation = Conversation.EMPTY;
        for (int i = 1; i <= 8; i++) {
            var before = conversation;
            conversation = conversation.with(new Exchange("Q" + i, "A" + i));
            assertEquals(Math.min(i - 1, MiniAiService.MAX_EXCHANGES), before.exchanges().size(), "earlier values never change");
        }
        assertEquals(List.of("Q3", "Q4", "Q5", "Q6", "Q7", "Q8"), conversation.exchanges().stream().map(Exchange::question).toList());
        assertEquals(6, new Conversation(Collections.nCopies(10, new Exchange("Q", "A"))).exchanges().size());
        var request = service().request(conversation, "Q9");
        var sentQuestions = JsonParser.parseString(request.evidenceJson()).getAsJsonObject().getAsJsonArray("conversation").asList().stream()
            .map(item -> item.getAsJsonObject().get("user").getAsString()).toList();
        assertEquals(List.of("Q3", "Q4", "Q5", "Q6", "Q7", "Q8"), sentQuestions, "at most six exchanges, oldest first");
        assertNull(evidence(service().request(Conversation.EMPTY, "Q1"), "conversation"), "a cleared conversation sends no context");
        assertTrue(Conversation.EMPTY.isEmpty());
    }

    @Test void chineseQuestionsAskForChineseAndDetailRequestsMayBeLonger() {
        var chinese = service().request(Conversation.EMPTY, "我今晚想拍多伦多夜景，有什么建议？");
        assertTrue(chinese.taskInstruction().contains("reply in Chinese"), chinese.taskInstruction());
        assertFalse(chinese.taskInstruction().contains(MiniAiService.DETAILED));
        assertEquals(MiniAiService.MAX_TOKENS, chinese.maxOutputTokens());
        var detailed = service().request(Conversation.EMPTY, "Can you explain long exposure in more detail?");
        assertTrue(detailed.taskInstruction().contains(MiniAiService.DETAILED));
        assertEquals(MiniAiService.MAX_TOKENS_DETAILED, detailed.maxOutputTokens());
        assertTrue(MiniAiService.wantsDetail("能详细说说吗？"));
        assertFalse(MiniAiService.wantsDetail("What should I shoot tonight?"));
        assertFalse(MiniAiService.chinese("Suggest something creative with a 50mm lens."));
        assertTrue(MiniAiService.chinese("用50mm镜头拍街景。"));
        assertEquals(7, MiniAiService.units("用50mm镜头拍街景。", true), "six Han characters and one Latin run");
    }

    @Test void englishRepliesAreShortenedToWholeSentences() {
        String sentence = "Look for reflections in shop windows after the rain and frame them with strong leading lines. ";
        assertEquals(16, MiniAiService.units(sentence, false));
        String reply = sentence.repeat(8);
        String normal = MiniAiService.tidy(reply, false).orElseThrow();
        assertEquals(sentence.repeat(5).strip(), normal, "five whole sentences fit in 80 words; the sixth would not");
        assertEquals(sentence.repeat(8).strip(), MiniAiService.tidy(reply, true).orElseThrow(), "an explicit request for detail allows up to 160 words");
        String good = "Try shooting city lights, storefront reflections, or traffic light trails. Look for contrasting colors, especially during blue hour.";
        assertEquals(good, MiniAiService.tidy(good, false).orElseThrow(), "a reply within the limit is unchanged");
        // One sentence that is longer than the limit on its own is cut at a clause boundary, never mid-word.
        String runOn = "Walk " + "past the old market, ".repeat(30) + "and stop.";
        String cut = MiniAiService.tidy(runOn, false).orElseThrow();
        assertTrue(cut.endsWith("market…"), cut);
        assertTrue(MiniAiService.units(cut, false) <= MiniAiService.MAX_WORDS, cut);
    }

    @Test void chineseRepliesAreShortenedToWholeSentences() {
        String sentence = "雨后可以去街角拍橱窗倒影，用低角度把霓虹和行人一起框进画面。";
        int each = MiniAiService.units(sentence, true);
        String reply = sentence.repeat(10);
        String normal = MiniAiService.tidy(reply, false).orElseThrow();
        int kept = MiniAiService.MAX_CHINESE / each;
        assertEquals(sentence.repeat(kept), normal, "as many whole sentences as fit in 150 characters");
        assertTrue(MiniAiService.units(normal, true) <= MiniAiService.MAX_CHINESE);
        assertEquals(sentence.repeat(Math.min(10, MiniAiService.MAX_CHINESE_DETAILED / each)), MiniAiService.tidy(reply, true).orElseThrow());
        String good = "试试拍城市灯光、橱窗倒影或车流光轨。蓝调时刻的冷暖对比最出片，记得找有前景的构图。";
        assertEquals(good, MiniAiService.tidy(good, false).orElseThrow());
    }

    @Test void markdownIsFlattenedAndAnUnfinishedTailIsDropped() {
        assertEquals("Ideas for tonight. City lights at blue hour. Reflections in puddles. Light trails from a bridge.",
            MiniAiService.tidy("## Ideas for tonight\n\n- **City lights** at blue hour\n- *Reflections* in puddles\n\n| a | b |\n|---|---|\n1. Light trails from a bridge", false).orElseThrow());
        assertEquals("夜景可以拍：灯光倒影。车流光轨。", MiniAiService.tidy("夜景可以拍：\n1、灯光倒影\n2、车流光轨", false).orElseThrow());
        assertEquals("Try the waterfront at dusk.", MiniAiService.tidy("Try the waterfront at dusk. Use a tripod and a slow shutter so the", false).orElseThrow(),
            "a reply cut off by the token cap loses its unfinished sentence");
        assertEquals("Try the waterfront at dusk with a tripod and", MiniAiService.tidy("Try the waterfront at dusk with a tripod and", false).orElseThrow(),
            "a lone sentence is kept");
        for (String unreadable : Arrays.asList(null, "", "   ", "```\n```", "### \n---", "📸 ✨"))
            assertTrue(MiniAiService.tidy(unreadable, false).isEmpty(), String.valueOf(unreadable));
    }

    @Test void lineBreaksFlowTogetherAndOnlyParagraphBreaksStay() {
        assertEquals("Try the river at dusk. Use a tripod for the lights.", MiniAiService.tidy("Try the river at dusk.\nUse a tripod for the lights.", false).orElseThrow(),
            "one sentence per line becomes one naturally wrapping paragraph");
        assertEquals("Try shooting the city lights at dusk.", MiniAiService.tidy("Try shooting the city\nlights at dusk.", false).orElseThrow(),
            "a soft-wrapped sentence gets no invented full stop");
        assertEquals("夜景可以这样拍：用三脚架和慢门。", MiniAiService.tidy("夜景可以这样拍：\n用三脚架和慢门。", false).orElseThrow());
        assertEquals("Look for reflections after rain.\n\nThen try light trails from a bridge.",
            MiniAiService.tidy("Look for reflections after rain.\n\n\nThen try light trails from a bridge.", false).orElseThrow(), "an intentional paragraph break stays");
        assertEquals("雨后找倒影。\n\n再去桥上拍车流光轨。", MiniAiService.tidy("雨后找倒影。\n\n再去桥上拍车流光轨。", false).orElseThrow());
        String sentence = "Look for reflections in shop windows after the rain and frame them with strong leading lines.";
        String reply = String.join(" ", Collections.nCopies(3, sentence)) + "\n\n" + String.join(" ", Collections.nCopies(3, sentence));
        assertEquals(String.join(" ", Collections.nCopies(3, sentence)) + "\n\n" + String.join(" ", Collections.nCopies(2, sentence)),
            MiniAiService.tidy(reply, false).orElseThrow(), "shortening keeps whole sentences and the paragraph break");
        assertFalse(MiniAiService.tidy("Shoot neon. Then reflections. Then trails.", false).orElseThrow().contains("\n"), "no line break is added between sentences");
    }

    @Test void providerFailuresBecomeShortMessagesAndNeverAnswers() {
        var expected = new LinkedHashMap<Result<ModelReply>, String>();
        expected.put(Result.failureKey("ai.error.keyMissing"), "home.ai.error.notConfigured");
        expected.put(Result.failureKey("ai.error.keyPlaceholder"), "home.ai.error.notConfigured");
        expected.put(Result.failureKey("ai.error.keyInvalid"), "home.ai.error.notConfigured");
        expected.put(Result.failureKey("ai.error.authentication", "401"), "home.ai.error.authentication");
        expected.put(Result.failureKey("ai.error.network"), "home.ai.error.network");
        expected.put(Result.failureKey("ai.error.timeout", "30"), "home.ai.error.timeout");
        expected.put(Result.failureKey("ai.error.rateLimited"), "home.ai.error.rateLimited");
        expected.put(Result.failureKey("ai.error.serviceUnavailable", "503"), "home.ai.error.unavailable");
        expected.put(Result.failureKey("ai.error.emptyResponse"), "home.ai.error.emptyAnswer");
        expected.put(Result.failureKey("ai.error.invalidResponse"), "home.ai.error.emptyAnswer");
        expected.put(Result.failureKey("ai.error.blocked", "SAFETY"), "home.ai.error.blocked");
        expected.put(Result.failureKey("ai.error.modelNotFound", "test-model"), "home.ai.error.failed");
        expected.forEach((failure, key) -> {
            replies.add(failure);
            var result = service().ask(Conversation.EMPTY, "Night ideas?");
            assertFalse(result.isSuccess()); assertTrue(result.value().isEmpty());
            assertTrue(result.hasError(key), key + " for " + failure.errors());
        });
        var other = service(context -> Result.failureKey("ai.error.modelNotFound", "test-model"), inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?");
        assertTrue(other.errors().getFirst().contains("test-model"), "unmapped failures keep their own explanation: " + other.errors());
        reply("   "); assertTrue(service().ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.emptyAnswer"));
        replies.add(Result.success(new ModelReply(Optional.empty(), List.of(), List.of())));
        assertTrue(service().ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.emptyAnswer"));
        var thrown = service(context -> { throw new IllegalStateException("boom"); }, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?");
        assertTrue(thrown.hasError("home.ai.error.failed"));
        assertFalse(thrown.errors().getFirst().contains("boom"), "no internal detail is shown");
    }

    @Test void realAdapterReportsMissingConfigurationNetworkFailureAndMalformedReplies() throws Exception {
        var http = HttpClient.newHttpClient();
        try (var gemini = new FakeGemini()) {
            var unconfigured = new GeminiAdapter(URI.create(gemini.endpoint()), "test-model", http, Duration.ofSeconds(5), LLMConfiguration.unconfigured());
            assertTrue(service(unconfigured, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.notConfigured"));
            assertTrue(gemini.requests.isEmpty(), "an unconfigured key never reaches the network");
            var configuration = LLMConfiguration.load(Map.of("LLM_API_KEY", FakeGemini.KEY), Path.of("unused"));
            var adapter = new GeminiAdapter(URI.create(gemini.endpoint()), "test-model", http, Duration.ofSeconds(5), configuration);
            gemini.status(200, "not json");
            assertTrue(service(adapter, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.emptyAnswer"));
            gemini.reply("```\n```");
            assertTrue(service(adapter, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.emptyAnswer"));
            gemini.status(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
            assertTrue(service(adapter, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.rateLimited"));
            gemini.reply("Shoot rain reflections under street lamps.");
            assertEquals("Shoot rain reflections under street lamps.", service(adapter, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").value().orElseThrow());
            var body = JsonParser.parseString(gemini.requests.getLast()).getAsJsonObject();
            assertEquals(MiniAiService.MAX_TOKENS, body.getAsJsonObject("generationConfig").get("maxOutputTokens").getAsInt());
            assertTrue(body.getAsJsonObject("systemInstruction").toString().contains("PhotoPilot Mini AI"));
            assertFalse(gemini.requests.getLast().contains(FakeGemini.KEY), "the key is never in the body");
        }
        int closed;
        try (var socket = new ServerSocket(0)) { closed = socket.getLocalPort(); }
        var unreachable = new GeminiAdapter(URI.create("http://127.0.0.1:" + closed), "test-model", http, Duration.ofSeconds(5),
            LLMConfiguration.load(Map.of("LLM_API_KEY", FakeGemini.KEY), Path.of("unused")));
        assertTrue(service(unreachable, inventory(List.of())).ask(Conversation.EMPTY, "Night ideas?").hasError("home.ai.error.network"));
        http.close();
    }

    @Test void gearPromptUsesOnlyOwnedCamerasLensesAndFilters() {
        var airshow = new GearFixtures.Airshow();
        assertEquals(List.of("Sony A1", "Sony A7 IV", "Sony FE 200-600mm G", "Sony FE 100-400mm GM", "Sony FE 24-105mm G", "Canon EF 100-400mm L II",
            "Vintage 300mm f/4", "B+W 77mm CPL"), service(airshow.items()).gear().value().orElseThrow(), "cameras, lenses, filters; no batteries or cards; at most eight");
        assertEquals(List.of(), service(List.of(airshow.fz, airshow.sd)).gear().value().orElseThrow(), "no camera, lens or filter: nothing to list");
        var unreadable = service(scripted, new InventoryRepository() {
            public Result<GearInventory> load() { return Result.failureKey("error.detail", "unreadable"); }
            public Result<GearInventory> save(GearInventory inventory) { return Result.failureKey("error.detail", "unreadable"); }
        });
        assertFalse(unreadable.gear().isSuccess());
        assertTrue(sent.isEmpty(), "reading gear never calls the model");
    }
}
