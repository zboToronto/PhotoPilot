package com.photopilot.service;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.service.MiniAiService.Conversation;
import com.photopilot.service.MiniAiService.Exchange;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional live smoke test (never part of routine runs): set PHOTOPILOT_LIVE_GEMINI=1 to ask Home Mini AI three real
 * questions with the locally configured key (an English question, a follow-up that needs the first answer, and a Chinese
 * question) on an empty temporary profile. Answers must be non-empty, within the Mini AI limits and in the question's
 * language. The transcript (questions and answers only; no key) is written to target/mini-ai-live-smoke.txt.
 */
@EnabledIfEnvironmentVariable(named = "PHOTOPILOT_LIVE_GEMINI", matches = "1")
class LiveGeminiMiniAiSmokeTest {
    @TempDir Path root;
    final StringBuilder log = new StringBuilder();
    void print(String line) { log.append(line).append(System.lineSeparator()); System.out.println(line); }

    @Test void liveAnswersAreShortFollowUpAwareAndInTheUsersLanguage() throws Exception {
        var environment = Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
        try (var app = new ApplicationBootstrap(environment)) {
            var miniAi = app.facade().miniAi();
            var conversation = Conversation.EMPTY;
            for (String question : new String[] { "What should I shoot tonight?", "What if I only have a 50mm lens?", "我今晚想拍多伦多夜景，有什么建议？" }) {
                var answer = miniAi.ask(conversation, question);
                assertTrue(answer.isSuccess(), () -> String.join("; ", answer.errors()));
                String text = answer.value().orElseThrow();
                boolean chinese = MiniAiService.chinese(question);
                int units = MiniAiService.units(text, chinese);
                print("Q: " + question); print("A (" + units + (chinese ? " characters" : " words") + "): " + text);
                assertEquals(chinese, MiniAiService.chinese(text), "answered in the question's language");
                assertTrue(units <= (chinese ? MiniAiService.MAX_CHINESE : MiniAiService.MAX_WORDS), text);
                conversation = conversation.with(new Exchange(question, text));
            }
        } finally {
            Files.writeString(Path.of("target", "mini-ai-live-smoke.txt"), log, StandardCharsets.UTF_8);
        }
    }
}
