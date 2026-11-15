package com.photopilot.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.EquipmentType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Home Mini AI: short, conversational photography inspiration, separate from the F02 Inspiration workflow.
 * One plain-text request per question goes through the shared model port (the same LLMClient the agent uses, so the
 * configured Gemini key, model, timeout and response-language policy apply), with a dedicated compact instruction, an
 * output-token cap, the last {@link #MAX_EXCHANGES} exchanges of the conversation and the local date and time. The
 * reply is untrusted: Markdown is removed and an over-long reply keeps its leading complete sentences.
 * Stateless: the caller holds the in-memory {@link Conversation} and adds only exchanges that succeeded.
 */
public final class MiniAiService {
    /** Longest question accepted, in characters (code points). */
    public static final int MAX_QUESTION = 300;
    /** Exchanges kept as context; older ones drop out. */
    public static final int MAX_EXCHANGES = 6;
    /**
     * Output-token caps. They bound runaway replies and cost; the instruction and {@link #tidy} set the actual length.
     * They leave headroom because models that reason before answering count that reasoning against the same cap.
     */
    public static final int MAX_TOKENS = 1024, MAX_TOKENS_DETAILED = 2048;
    /** Reply limits after which complete trailing sentences are dropped: Chinese characters (a Latin word counts as one) or words. */
    public static final int MAX_CHINESE = 150, MAX_CHINESE_DETAILED = 300, MAX_WORDS = 80, MAX_WORDS_DETAILED = 160;
    private static final int MAX_GEAR = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH);
    private static final List<String> DETAIL = List.of("more detail", "in detail", "detailed", "elaborate", "explain", "tell me more",
        "step by step", "step-by-step", "in depth", "in-depth", "expand on", "详细", "具体", "展开", "多讲", "多说", "解释", "深入", "一步一步");
    private static final String TERMINAL = ".!?。！？…", CLOSING = "\"'”’)）]」』", FULL_WIDTH_END = "。！？", CLAUSE = "，,；;：:、";

    static final String INSTRUCTION = """
        You are PhotoPilot Mini AI, a concise photography inspiration assistant on the PhotoPilot home page.
        Help users quickly discover photography ideas, creative subjects, compositions and simple shooting suggestions.
        Answer directly and naturally, in plain text without Markdown.
        For Chinese responses, usually write 50-100 Chinese characters and normally no more than 150 characters.
        For English responses, usually write 25-55 words and normally no more than 80 words.
        Prefer one to three concise sentences.
        Avoid greetings, introductions, repeating the question, long lists, tables, headings and excessive explanations.
        Respond in the language of the user's latest message.
        Use the conversation in the application evidence when relevant: it lists the earlier exchanges, oldest first.
        localTime in the evidence is the user's current local date and time.
        Prioritize practical photography advice over generic statements. You may suggest subjects, themes, compositions,
        basic camera techniques, lighting conditions and creative challenges.
        Do not invent real-time weather, precise locations, nearby places, events, equipment specifications or anything
        else not provided by the user or the evidence. You cannot see the user's location, weather or gear unless they
        state it. If a suggestion depends on a place the user has not named, give general ideas and invite them to name
        a city or neighbourhood. Only treat equipment the user lists as theirs; never assume other cameras or lenses.
        When the user wants detailed, configurable inspiration (a specific place and time, gear selection, structured
        idea cards), recommend PhotoPilot's Inspiration page in the sidebar rather than attempting to replace it.
        When explicitly asked for more detail, give a moderately expanded but focused answer.
        Never pretend that unavailable tools or data sources have been accessed.
        """;
    static final String DETAILED = "The user asked for more detail: you may write one focused paragraph of up to about 160 words "
        + "(300 Chinese characters), still without headings, tables or long lists.\n";

    /** One successful question and its shown answer. */
    public record Exchange(String question, String answer) {
        public Exchange { Objects.requireNonNull(question, "question"); Objects.requireNonNull(answer, "answer"); }
    }

    /** In-memory, bounded and ordered (oldest first); nothing is stored. */
    public record Conversation(List<Exchange> exchanges) {
        public static final Conversation EMPTY = new Conversation(List.of());
        public Conversation {
            exchanges = List.copyOf(exchanges);
            if (exchanges.size() > MAX_EXCHANGES) exchanges = List.copyOf(exchanges.subList(exchanges.size() - MAX_EXCHANGES, exchanges.size()));
        }
        /** This conversation plus one more exchange; beyond {@link #MAX_EXCHANGES} the oldest drop out. */
        public Conversation with(Exchange next) {
            var all = new ArrayList<>(exchanges); all.add(Objects.requireNonNull(next)); return new Conversation(all);
        }
        public boolean isEmpty() { return exchanges.isEmpty(); }
    }

    private final LLMClient model;
    private final InventoryService inventory;
    private final Clock clock;

    public MiniAiService(LLMClient model, InventoryService inventory, Clock clock) {
        this.model = Objects.requireNonNull(model, "model");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The question to send (stripped), or why it cannot be sent. Nothing is sent for an empty or over-long question. */
    public static Result<String> validate(String question) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) return Result.failureKey("home.ai.error.empty");
        int length = text.codePointCount(0, text.length());
        if (length > MAX_QUESTION) return Result.failureKey("home.ai.error.tooLong", MAX_QUESTION, length);
        return Result.success(text);
    }

    /**
     * One real model request for {@code question} with the recent {@code conversation} as context. Success is a
     * non-empty, tidied answer; every provider failure is an explicit error and never a stand-in answer.
     */
    public Result<String> ask(Conversation conversation, String question) {
        var valid = validate(question);
        if (!valid.isSuccess()) return valid;
        String text = valid.value().orElseThrow();
        try {
            var reply = model.complete(request(conversation, text));
            if (!reply.isSuccess()) return Result.failure(explain(reply));
            return tidy(reply.value().orElseThrow().structuredOutput().orElse(""), wantsDetail(text))
                .map(Result::success).orElseGet(() -> Result.failureKey("home.ai.error.emptyAnswer"));
        } catch (RuntimeException unexpected) {
            // Never shown as an answer; no request or reply detail is exposed.
            return Result.failureKey("home.ai.error.failed", "");
        }
    }

    /** The exact request {@link #ask} sends: the question as the user message, labelled evidence and the Mini AI instruction. */
    public AgentContext request(Conversation conversation, String question) {
        boolean detailed = wantsDetail(question);
        var evidence = new JsonObject();
        var earlier = new JsonArray();
        for (var exchange : (conversation == null ? Conversation.EMPTY : conversation).exchanges()) {
            var item = new JsonObject(); item.addProperty("user", exchange.question()); item.addProperty("assistant", exchange.answer());
            earlier.add(item);
        }
        if (!earlier.isEmpty()) evidence.add("conversation", earlier);
        evidence.addProperty("localTime", LocalDateTime.now(clock).format(TIME));
        String language = chinese(question)
            ? "The user's latest message is in Chinese: reply in Chinese, in the same script, even if the default response language differs.\n"
            : "Reply in the same language as the user's latest message, even if the default response language differs.\n";
        return new AgentContext(UUID.randomUUID(), List.of(question.strip()), evidence.toString(), List.of(), Optional.empty())
            .withTaskInstruction(INSTRUCTION + language + (detailed ? DETAILED : ""))
            .withMaxOutputTokens(detailed ? MAX_TOKENS_DETAILED : MAX_TOKENS);
    }

    /**
     * Names of the user's cameras, lenses and filters from the F05 inventory (read only), at most eight, so the
     * "Based on my gear" prompt lists real equipment only. Batteries, storage and specifications are not included.
     */
    public Result<List<String>> gear() {
        var listed = inventory.list("");
        if (!listed.isSuccess()) return Result.failure(listed);
        var names = new LinkedHashSet<String>();
        for (var type : List.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.FILTER))
            for (var item : listed.value().orElseThrow().items())
                if (item.type() == type && names.size() < MAX_GEAR) names.add((item.brand().strip() + " " + item.model().strip()).strip());
        return Result.success(List.copyOf(names));
    }

    /** Whether the user explicitly asked for a longer explanation, which allows a moderately longer answer. */
    public static boolean wantsDetail(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return DETAIL.stream().anyMatch(text::contains);
    }

    /** Short Home messages for the shared provider failures; anything else keeps its own explanation. */
    static LocalizedMessage explain(Result<?> failure) {
        for (String key : List.of("ai.error.keyMissing", "ai.error.keyPlaceholder", "ai.error.keyInvalid", "ai.error.keyUnreadable"))
            if (failure.hasError(key)) return LocalizedMessage.of("home.ai.error.notConfigured");
        if (failure.hasError("ai.error.authentication")) return LocalizedMessage.of("home.ai.error.authentication");
        if (failure.hasError("ai.error.network")) return LocalizedMessage.of("home.ai.error.network");
        if (failure.hasError("ai.error.timeout")) return LocalizedMessage.of("home.ai.error.timeout");
        if (failure.hasError("ai.error.rateLimited")) return LocalizedMessage.of("home.ai.error.rateLimited");
        if (failure.hasError("ai.error.serviceUnavailable")) return LocalizedMessage.of("home.ai.error.unavailable");
        if (failure.hasError("ai.error.emptyResponse") || failure.hasError("ai.error.invalidResponse")) return LocalizedMessage.of("home.ai.error.emptyAnswer");
        if (failure.hasError("ai.error.blocked")) return LocalizedMessage.of("home.ai.error.blocked");
        return failure.messages().isEmpty() ? LocalizedMessage.of("home.ai.error.failed", "")
            : LocalizedMessage.of("home.ai.error.failed", failure.messages().getFirst());
    }

    /**
     * Post-generation check of untrusted reply text. Code fences, table rows, headings, list markers and emphasis are
     * removed. Single line breaks flow together, so the answer wraps naturally at the display width; only a blank line
     * (an intentional paragraph break) is kept, as one blank line. A heading or list item becomes a sentence of its
     * paragraph. A trailing unfinished sentence (a reply cut off by the token cap) is dropped when complete sentences
     * precede it. Over the limit, the leading complete sentences that fit are kept; only a single sentence that is
     * longer than the limit on its own is cut, at a clause boundary where possible, with an ellipsis. Sentences are
     * found only to count and shorten; no line break is ever added between them. Empty when no readable text remains.
     */
    public static Optional<String> tidy(String raw, boolean detailed) {
        if (raw == null) return Optional.empty();
        record Line(String text, boolean item, boolean heading, boolean paragraph) { }
        var lines = new ArrayList<Line>();
        boolean blank = false;
        for (String line : raw.strip().split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) { blank = !lines.isEmpty(); continue; }
            if (text.startsWith("```") || text.startsWith("|") || text.matches("[-=*_]{3,}")) continue;
            String unmarked = text.replaceFirst("^(?:[-*+•·]\\s+|\\d{1,2}[.)]\\s+|\\d{1,2}[、．]\\s*)", "");
            String unheaded = unmarked.replaceFirst("^#{1,6}\\s*", "");
            boolean item = !unmarked.equals(text), heading = !unheaded.equals(unmarked);
            text = unheaded.replaceFirst("^>\\s*", "").replace("**", "").replace("__", "").replace("`", "")
                .replaceAll("\\*(\\S[^*]*?)\\*", "$1").strip();
            if (text.isEmpty()) continue;
            // A blank line starts a new paragraph, except after a heading or between items of one list.
            var previous = lines.isEmpty() ? null : lines.getLast();
            boolean paragraph = blank && previous != null && !previous.heading() && !(previous.item() && item);
            lines.add(new Line(text, item, heading, paragraph)); blank = false;
        }
        var paragraphs = new ArrayList<StringBuilder>();
        for (var line : lines) {
            if (paragraphs.isEmpty() || line.paragraph()) paragraphs.add(new StringBuilder());
            String text = line.text();
            int last = text.codePointBefore(text.length());
            if ((line.item() || line.heading()) && !ends(text) && last != ':' && last != '：') text += han(last) ? "。" : ".";
            append(paragraphs.getLast(), text);
        }
        var parts = new ArrayList<List<String>>();
        for (var paragraph : paragraphs) parts.add(sentences(paragraph.toString().replaceAll("[ \\t\\u00A0]+", " ").strip()));
        parts.removeIf(List::isEmpty);
        if (parts.stream().flatMap(List::stream).flatMapToInt(String::codePoints).noneMatch(Character::isLetterOrDigit)) return Optional.empty();
        boolean zh = chinese(String.join(" ", parts.stream().flatMap(List::stream).toList()));
        int limit = zh ? (detailed ? MAX_CHINESE_DETAILED : MAX_CHINESE) : (detailed ? MAX_WORDS_DETAILED : MAX_WORDS);
        var tail = parts.getLast();
        if ((parts.size() > 1 || tail.size() > 1) && !ends(tail.getLast())) { tail.removeLast(); if (tail.isEmpty()) parts.removeLast(); }
        var kept = new ArrayList<String>();
        int used = 0;
        boolean full = false;
        for (var paragraph : parts) {
            var text = new StringBuilder();
            for (String sentence : paragraph) {
                int size = units(sentence, zh);
                if (used > 0 && used + size > limit) { full = true; break; }
                append(text, sentence); used += size;
            }
            if (!text.isEmpty()) kept.add(text.toString());
            if (full) break;
        }
        String result = String.join("\n\n", kept);
        return Optional.of(units(result, zh) > limit ? cut(result, zh, limit) : result);
    }

    /** Last resort for one over-long sentence: the longest prefix within the limit, ending at a clause boundary if any. */
    private static String cut(String sentence, boolean zh, int limit) {
        int best = -1, fallback = -1;
        for (int i = 1; i < sentence.length(); i++) {
            if (units(sentence.substring(0, i), zh) > limit) break;
            char c = sentence.charAt(i - 1);
            if (CLAUSE.indexOf(c) >= 0) best = i - 1;
            if (zh ? !Character.isWhitespace(c) : Character.isWhitespace(sentence.charAt(i))) fallback = i;
        }
        int end = best > 0 ? best : fallback > 0 ? fallback : sentence.length();
        return sentence.substring(0, end).strip() + (zh ? "……" : "…");
    }

    private static void append(StringBuilder text, String part) {
        if (!text.isEmpty() && !han(text.codePointBefore(text.length())) && FULL_WIDTH_END.indexOf(text.charAt(text.length() - 1)) < 0) text.append(' ');
        text.append(part);
    }

    /** Sentences, each with its terminal punctuation and closing quotes; the remainder (if any) is the last element. */
    static List<String> sentences(String text) {
        var out = new ArrayList<String>();
        int start = 0, n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (TERMINAL.indexOf(c) < 0) continue;
            int end = i + 1;
            while (end < n && (TERMINAL.indexOf(text.charAt(end)) >= 0 || CLOSING.indexOf(text.charAt(end)) >= 0)) end++;
            if (end == n || FULL_WIDTH_END.indexOf(c) >= 0 || Character.isWhitespace(text.charAt(end))) {
                String sentence = text.substring(start, end).strip();
                if (!sentence.isEmpty()) out.add(sentence);
                start = end; i = end - 1;
            }
        }
        if (start < n && !text.substring(start).isBlank()) out.add(text.substring(start).strip());
        return out;
    }

    private static boolean ends(String sentence) {
        String text = sentence.strip();
        int end = text.length();
        while (end > 0 && CLOSING.indexOf(text.charAt(end - 1)) >= 0) end--;
        return end > 0 && TERMINAL.indexOf(text.charAt(end - 1)) >= 0;
    }

    private static boolean han(int codePoint) { return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN || "，。！？；：、（）《》".indexOf(codePoint) >= 0; }

    private static long hanCount(String text) { return text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count(); }

    /** Space-separated words that contain a non-Han letter or digit. */
    private static long words(String text) {
        return Arrays.stream(text.strip().split("\\s+"))
            .filter(word -> word.codePoints().anyMatch(cp -> Character.isLetterOrDigit(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN)).count();
    }

    /** Chinese when Han characters outnumber the other words (so a Chinese reply that names "50mm" is still Chinese). */
    static boolean chinese(String text) {
        long han = hanCount(text);
        return han > 0 && han >= words(text);
    }

    /** Length in the unit of the reply's language: Han characters plus Latin runs (e.g. "f/2.8"), or words. */
    static int units(String text, boolean zh) {
        if (!zh) return (int) words(text) + (int) hanCount(text);
        int latin = 0;
        var matcher = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9./'-]*").matcher(text);
        while (matcher.find()) latin++;
        return (int) hanCount(text) + latin;
    }
}
