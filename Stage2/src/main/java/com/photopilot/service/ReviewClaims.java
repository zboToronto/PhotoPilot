package com.photopilot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.i18n.LocalizedMessage;

/**
 * F11 semantic evidence binding (Stage2Review1). A figure in an AI review is supported only by an application fact with
 * the same metric and unit, the same statistic (minimum, maximum, median, mean, a recorded value or the most frequent
 * one), the same source (this session's EXIF statistics, the approved plan, or an earlier session's saved EXIF facts) and,
 * for counts and shares, the same subject. A number that merely occurs somewhere in the evidence proves nothing.
 * Evidence records carry application ids (S = this session, P = approved plan); a cited id must exist and carry a figure it
 * is cited for. Earlier sessions are never citable: their figures must be introduced as earlier and are matched against
 * their saved facts only. Every source a sentence attributes a figure to must support it ("used ISO 800 as planned" needs
 * ISO 800 both recorded and planned); without wording, a figure is this session's recorded data. A figure claimed under
 * several statistics ("the mean and median ISO were both 800") needs each; one claimed for every photo ("all at 50 mm")
 * must be the only recorded value; counts of two settings at once, of "the rest", or of a place relative to the plan that
 * the sentence does not name are unsupported. Free text is read conservatively: a digit that belongs to no figure the
 * checker reads ("10fps", "2nd", a bare "S17"), or a figure whose meaning cannot be established, makes the statement
 * unsupported. Digits inside equipment names and verbatim deterministic comparison or limit lines are application text,
 * not claims, unless earlier-session or "as with the lens" wording in their sentence claims more than they say.
 */
final class ReviewClaims {
    enum Source { SESSION, PLAN, HISTORY }
    static final String FOCAL = "focalLength", SHUTTER = "shutter", APERTURE = "aperture", ISO = "iso", EV = "exposureCompensation",
        COUNT = "count", PERCENT = "percent", REVISION = "revision", TIME = "time", DATE = "date", OFFSET = "offset";
    private static final String UNTYPED = "";
    private static final Set<String> MEASURES = Set.of(FOCAL, SHUTTER, APERTURE, ISO, EV);

    /** One typed component of an evidence record (components of one record share its id); the value is exact, never rounded. */
    record Fact(String id, Source source, String metric, String aggregation, double value, String subject) { }

    /** The facts of one review request, the verbatim deterministic lines and the equipment names. */
    record Evidence(List<Fact> facts, List<String> verbatim, List<String> names) {
        Evidence { facts = List.copyOf(facts); verbatim = List.copyOf(verbatim); names = List.copyOf(names); }
        /** The same evidence plus the saved EXIF facts of earlier sessions (HistoryRetriever JSON); unreadable JSON adds nothing. */
        Evidence withHistory(String historyJson) {
            var history = history(historyJson);
            var all = new ArrayList<>(facts); all.addAll(history.facts());
            var allNames = new ArrayList<>(names); allNames.addAll(history.names());
            return new Evidence(all, verbatim, allNames);
        }
        /** An id the application gave to a record of this request (earlier sessions have none the model can cite). */
        boolean citable(String id) { return !id.isEmpty() && facts.stream().anyMatch(f -> f.id().equalsIgnoreCase(id) && f.source() != Source.HISTORY); }
    }

    /** Collects facts while ReviewEvidence writes the evidence JSON, so every id in the JSON names exactly these facts. */
    static final class Builder {
        private final List<Fact> facts = new ArrayList<>();
        private final List<String> verbatim = new ArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();
        private int session, plan;

        String record(Source source) { return source == Source.PLAN ? "P" + (++plan) : "S" + (++session); }
        void add(String id, Source source, String metric, String aggregation, double value, String subject) {
            if (Double.isFinite(value)) facts.add(new Fact(id, source, metric, aggregation, value, subject));
        }
        void name(String name) { if (name != null && !name.isBlank()) names.add(name.strip()); }
        /**
         * A deterministic comparison or limit line: quoting it verbatim is application text; its counts and shares are facts of
         * this session, each typed by its place in the line (photos within, below or above a planned range, outside the planned
         * lenses, missing, failed, invalid, or a total).
         */
        void line(LocalizedMessage message) {
            verbatim.add(StatisticsCalculator.english(message));
            String key = message.key();
            String field = message.arguments().stream().filter(a -> a instanceof LocalizedMessage m && m.key().startsWith("review.field."))
                .map(a -> "field:" + ((LocalizedMessage) a).key().substring("review.field.".length())).findFirst().orElse("");
            if (key.equals("review.compare.coverage")) field = "field:" + FOCAL;
            else if (key.startsWith("review.compare.range.")) field = "field:" + switch (key.substring("review.compare.range.".length())) {
                case "compensation" -> EV; default -> key.substring("review.compare.range.".length()); };
            else if (key.startsWith("review.compare.camera")) field = "field:camera";
            else if (key.startsWith("review.compare.lens")) field = "field:lens";
            else if (key.equals("review.limit.unzoned") || key.startsWith("review.compare.time")) field = "field:captureTime";
            String item = key.endsWith("Used") || key.endsWith("Extra") ? item(String.valueOf(message.arguments().getFirst())) : field;
            var arguments = message.arguments();
            for (int i = 0; i < arguments.size(); i++) {
                Object argument = arguments.get(i);
                String role = role(key, i), subject = role.equals("photos") || role.equals("share") ? item : field;
                if (argument instanceof Integer || argument instanceof Long) add("", Source.SESSION, COUNT, role, ((Number) argument).doubleValue(), subject);
                else if (argument instanceof String text && text.matches("\\d+(?:\\.\\d+)?%")) add("", Source.SESSION, PERCENT, role, Double.parseDouble(text.substring(0, text.length() - 1)), subject);
            }
        }
        /** What the index-th argument of a deterministic line counts (the message patterns of StatisticsCalculator). */
        private static String role(String key, int index) {
            if (key.startsWith("review.compare.range.")) return switch (index) { case 1, 3 -> "within"; case 2 -> "withData"; case 4 -> "below"; case 5 -> "above"; default -> "comparison"; };
            return switch (key) {
                case "review.compare.coverage" -> index == 1 ? "withData" : "outside";
                case "review.compare.cameraUsed", "review.compare.lensUsed", "review.compare.cameraExtra", "review.compare.lensExtra" -> index == 2 ? "withData" : index == 3 ? "share" : "photos";
                case "review.compare.cameraMissing", "review.compare.lensMissing", "review.limit.unusable" -> index == 0 ? "missing" : "imported";
                case "review.limit.missing" -> index == 1 ? "missing" : "imported";
                case "review.limit.unzoned" -> "missing";
                case "review.limit.failedFiles" -> "failed";
                case "review.limit.invalid" -> "invalid";
                case "review.limit.smallSample" -> "usable";
                case "review.compare.cameraNoData", "review.compare.lensNoData", "review.compare.noData" -> "imported";
                default -> "comparison";
            };
        }
        Evidence build() { return new Evidence(facts, verbatim, List.copyOf(names)); }
    }
    static String item(String name) { return "item:" + name.strip().toLowerCase(Locale.ROOT); }
    static String value(String metric, double value) { return "value:" + metric + ":" + value; }

    private record History(List<Fact> facts, List<String> names) { }

    /** Saved EXIF facts of the related history (relatedHistory[].savedReviews[].facts, dates, revisions); earlier advice is never a fact. */
    private static History history(String json) {
        var facts = new ArrayList<Fact>(); var names = new ArrayList<String>();
        if (json == null || json.isBlank()) return new History(facts, names);
        try {
            var related = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("relatedHistory");
            if (related == null) return new History(facts, names);
            int k = 0;
            for (JsonElement element : related) {
                var session = element.getAsJsonObject(); String id = "H" + (++k);
                date(session, "date").ifPresent(d -> facts.add(new Fact(id, Source.HISTORY, DATE, "sessionDate", d, "")));
                number(session, "approvedRevision").ifPresent(r -> facts.add(new Fact(id, Source.HISTORY, REVISION, "revision", r, "")));
                var equipment = session.getAsJsonArray("plannedEquipment");
                if (equipment != null) for (var gear : equipment) {
                    var m = Pattern.compile("^(\\d+) x (.+)$").matcher(gear.getAsString().strip());
                    if (m.matches()) { names.add(m.group(2)); facts.add(new Fact(id, Source.HISTORY, COUNT, "quantity", Double.parseDouble(m.group(1)), item(m.group(2)))); }
                }
                var reviews = session.getAsJsonArray("savedReviews");
                if (reviews == null) continue;
                for (var r : reviews) {
                    var review = r.getAsJsonObject();
                    date(review, "savedOn").ifPresent(d -> facts.add(new Fact(id, Source.HISTORY, DATE, "savedOn", d, "")));
                    number(review, "reviewedPlanRevision").ifPresent(v -> facts.add(new Fact(id, Source.HISTORY, REVISION, "revision", v, "")));
                    var saved = review.getAsJsonObject("facts");
                    if (saved == null) continue;
                    number(saved, "photosImported").ifPresent(v -> facts.add(new Fact(id, Source.HISTORY, COUNT, "imported", v, "")));
                    number(saved, "photosWithUsableExif").ifPresent(v -> facts.add(new Fact(id, Source.HISTORY, COUNT, "usable", v, "")));
                    top(saved.getAsJsonArray("mostUsedFocalLengthsMm"), id, FOCAL, facts);
                    top(saved.getAsJsonArray("mostUsedApertures"), id, APERTURE, facts);
                    top(saved.getAsJsonArray("mostUsedIso"), id, ISO, facts);
                }
            }
            return new History(facts, names);
        } catch (RuntimeException unreadable) { return new History(List.of(), List.of()); }
    }
    private static void top(JsonArray values, String id, String metric, List<Fact> facts) {
        if (values == null) return;
        int index = 0;
        for (var element : values) {
            var item = element.getAsJsonObject();
            var v = number(item, "value"); var photos = number(item, "photos");
            if (v.isEmpty()) continue;
            facts.add(new Fact(id, Source.HISTORY, metric, index++ == 0 ? "mode" : "top", v.get(), ""));
            photos.ifPresent(p -> facts.add(new Fact(id, Source.HISTORY, COUNT, "photos", p, value(metric, v.get()))));
        }
    }
    private static Optional<Double> number(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? Optional.of(Double.parseDouble(o.get(key).getAsString())) : Optional.empty(); }
        catch (RuntimeException unreadable) { return Optional.empty(); }
    }
    private static Optional<Double> date(JsonObject o, String key) {
        try { return o.has(key) ? Optional.of((double) LocalDate.parse(o.get(key).getAsString()).toEpochDay()) : Optional.empty(); }
        catch (RuntimeException unreadable) { return Optional.empty(); }
    }
    /** Seconds of the day and epoch day of an ISO local date-time (capture times). */
    static double secondOfDay(String localDateTime) { return LocalDateTime.parse(localDateTime).toLocalTime().toSecondOfDay(); }
    static double epochDay(String localDateTime) { return LocalDateTime.parse(localDateTime).toLocalDate().toEpochDay(); }
    static double offsetMinutes(String offset) { return ZoneOffset.of(offset).getTotalSeconds() / 60.0; }


    // ---------------------------------------------------------------- statements

    private static final Pattern CITATION = Pattern.compile("\\s*\\[\\s*[A-Za-z]{1,2}\\d{1,4}(?:\\s*,\\s*[A-Za-z]{1,2}\\d{1,4})*\\s*]");

    /** Text as shown and saved: citation markers removed, nothing else changed. */
    static String display(String text) {
        return CITATION.matcher(text).replaceAll("").replaceAll("\\s+([.,;:!?。，；：！？)])", "$1").replaceAll(" {2,}", " ").strip();
    }

    /** One stated quantity. {@code numerator}/{@code denominator} are set for a shutter written as a fraction. */
    private static final class Figure {
        final int start, end; String metric; final double value; final int decimals; final long numerator, denominator;
        boolean afterOf, photos; String countSubject = ""; Figure partner;
        final List<String> cited = new ArrayList<>(); int group = -1;
        Set<Source> sources = Set.of(); boolean all = true;
        Figure(int start, int end, String metric, double value, int decimals, long numerator, long denominator, boolean afterOf) {
            this.start = start; this.end = end; this.metric = metric; this.value = value; this.decimals = decimals;
            this.numerator = numerator; this.denominator = denominator; this.afterOf = afterOf;
        }
        boolean count() { return metric.equals(COUNT) || metric.equals(PERCENT) || metric.equals(UNTYPED); }
        boolean within(int[] segment) { return start >= segment[0] && start < segment[1]; }
    }
    private enum Kind { HISTORY, CURRENT, PLAN, ACTUAL }
    /** Source wording; {@code contrast} ("unlike the earlier session") and {@code echo} ("as in the earlier session", "as planned") qualify it. */
    private record Marker(int start, Kind kind, boolean contrast, boolean echo) { }

    /**
     * Whether every figure of one statement is supported. Free text is read conservatively: a figure whose metric,
     * statistic, source or subject cannot be established is unsupported. In advice (lessons, improvements, limitations) a
     * figure is exempt only when the wording plainly proposes it ("try 1/4000 s next time"); counts, shares, statistics and
     * anything said about recorded, planned or earlier data are checked there too.
     */
    static boolean supported(String statement, Evidence evidence, boolean advice) {
        String text = Normalizer.normalize(statement, Normalizer.Form.NFKC);
        String shown = display(text);
        for (String line : evidence.verbatim()) if (same(shown, line)) return true;
        var masked = new StringBuilder(text);
        // Citations are checked, remembered by position and blanked, so their commas never split a clause.
        var citations = new ArrayList<int[]>(); var citedIds = new ArrayList<List<String>>();
        Matcher c = CITE.matcher(text);
        while (c.find()) {
            var ids = Arrays.stream(c.group().replaceAll("[\\[\\]\\s]", "").split(",")).toList();
            if (ids.stream().anyMatch(id -> !evidence.citable(id))) return false; // an id the application never issued
            citations.add(new int[]{c.start(), c.end()}); citedIds.add(ids);
            for (int i = c.start(); i < c.end(); i++) masked.setCharAt(i, ' ');
        }
        // A deterministic line inside a longer statement is application text, unless earlier-session wording in its sentence re-scopes it.
        for (String line : evidence.verbatim()) mask(text, masked, line, null, null, null, true);
        var items = new ArrayList<int[]>(); var itemNames = new ArrayList<String>();
        for (var name : expand(evidence.names())) mask(text, masked, name.getKey(), items, itemNames, name.getValue(), false);
        String scan = joinRanges(masked.toString());

        // Every digit must belong to a figure that is read: digits glued to unknown words ("10fps", "2nd", "S17") make the statement unsupported.
        var figures = new ArrayList<Figure>();
        var read = new boolean[scan.length()];
        Matcher m = TOKEN.matcher(scan);
        while (m.find()) {
            var found = figures(m, scan);
            if (found == null) return false;
            figures.addAll(found);
            for (int i = m.start(); i < m.end(); i++) read[i] = true;
        }
        for (int i = 0; i < scan.length(); i++) if (Character.isDigit(scan.charAt(i)) && !read[i]) return false;
        for (int i = 0; i + 1 < figures.size(); i++) { // "50 to 85 mm", "from f/2.8 to 4"
            var a = figures.get(i); var b = figures.get(i + 1);
            boolean comparable = a.metric.equals(b.metric) || a.metric.equals(UNTYPED) && MEASURES.contains(b.metric)
                || MEASURES.contains(a.metric) && b.metric.equals(UNTYPED) && !b.photos;
            if (a.partner == null && b.partner == null && comparable && RANGE_GAP.matcher(scan.substring(a.end, Math.max(a.end, b.start))).matches()) {
                String metric = a.metric.equals(UNTYPED) ? b.metric : a.metric;
                a.partner = b; b.partner = a; a.metric = metric; b.metric = metric;
            }
        }
        // Each citation names the provenance of the figures of its own clause, otherwise of those since the previous citation in its sentence.
        for (int k = 0; k < citations.size(); k++) {
            int at = citations.get(k)[0]; int[] sentence = sentence(scan, at), clause = clause(scan, at);
            int since = Math.max(sentence[0], k == 0 ? 0 : citations.get(k - 1)[1]), inClause = Math.max(since, clause[0]);
            int from = figures.stream().anyMatch(f -> f.group < 0 && f.start >= inClause && f.start < at) ? inClause : since;
            for (var figure : figures) if (figure.group < 0 && figure.start >= from && figure.start < at) { figure.cited.addAll(citedIds.get(k)); figure.group = k; }
        }
        var markers = markers(scan);
        for (var figure : figures) type(figure, scan, figures);
        for (var figure : figures) if (figure.partner != null && figure.metric.equals(UNTYPED) && MEASURES.contains(figure.partner.metric)) figure.metric = figure.partner.metric;
        // "Of the 7 photos shot at 85 mm": with no count before it and a value in its clause, the 7 counts photos of that value, not a total.
        for (var figure : figures) {
            if (!figure.afterOf || !OF_WORD.matcher(scan.substring(Math.max(0, figure.start - 16), figure.start)).find()) continue;
            int[] clause = clause(scan, figure.start);
            if (OF_OPENING.matcher(scan).region(clause[0], figure.start).matches() // the clause opens with it ("None of the 7 photos ..." keeps its total)
                && figures.stream().noneMatch(f -> f != figure && f.count() && f.start < figure.start && f.within(clause))
                && figures.stream().anyMatch(f -> MEASURES.contains(f.metric) && f.within(clause))) figure.afterOf = false;
        }
        for (var figure : figures) scope(figure, scan, markers, figures);
        for (var figure : figures) if (!supported(figure, scan, evidence, advice, figures, items, itemNames, markers)) return false;
        for (int k = 0; k < citations.size(); k++) {
            final int group = k;
            var cited = evidence.facts().stream().filter(f -> citedIds.get(group).stream().anyMatch(id -> id.equalsIgnoreCase(f.id()))).toList();
            var bound = figures.stream().filter(f -> f.group == group).toList();
            if (!bound.isEmpty() && bound.stream().noneMatch(f -> cited.stream().anyMatch(fact -> f.sources.contains(fact.source())
                && matches(f, fact, scan, figures, items, itemNames, evidence.facts())))) return false;
        }
        return respectively(scan, figures, evidence);
    }

    private static final Pattern CITE = Pattern.compile("\\[\\s*[A-Za-z]{1,2}\\d{1,4}(?:\\s*,\\s*[A-Za-z]{1,2}\\d{1,4})*\\s*]");
    private static final String N = "(?:\\d{1,3}(?:,\\d{3})+(?![\\d,])(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)";
    private static final String DASH = "[-–—~‐‑‒―〜～]";
    /** Digits may follow Chinese text directly ("焦距是85mm"); digits glued to Latin letters belong to names ("A1", "7M4"). */
    private static final String NOT_AFTER = "(?<![\\p{IsLatin}\\p{N}])";
    /** A number starts after a non-digit; a comma ends a word before it ("，85 毫米" after NFKC) but not inside "1,000". */
    private static final String NUMBER_START = "(?<![\\p{IsLatin}\\p{N}./])(?<!\\d,)";
    /** The second number of an aperture or ISO range is whole and is no count ("f/2.8 – 4 of 7 photos", "ISO 800 – 5 photos", "f/2.8 – 1/250 s"). */
    private static final String WHOLE = "(?!\\d|[.,]\\d)(?!\\s*(?:/|(?:out\\s+)?of\\b|photos?\\b|photographs?\\b|images?\\b|frames?\\b|files?\\b|shots?\\b|pictures?\\b|exposures?\\b|times\\b|张|幅|帧|次|%))";
    private static final Pattern TOKEN = Pattern.compile(
        "(?<date>" + NOT_AFTER + "\\d{4}-\\d{2}-\\d{2}(?!\\p{N}))"
        + "|(?<offset>(?:UTC|GMT)\\s*(?<os>[+\\-−])\\s*(?<oh>\\d{1,2})(?::(?<om>\\d{2}))?|(?<![\\p{N}:])(?<os2>[+\\-−])(?<oh2>\\d{2}):(?<om2>\\d{2})(?!\\p{N}))"
        + "|(?<time>(?<![\\p{N}:.])(?<th>\\d{1,2}):(?<tm>\\d{2})(?::(?<ts>\\d{2}))?(?![\\p{N}:]))"
        + "|(?<ap>" + NOT_AFTER + "(?:[fƒ]\\s*/\\s*|[fƒ](?=\\d))(?<ap1>" + N + ")(?:\\s*" + DASH + "\\s*(?:[fƒ]\\s*/\\s*|[fƒ](?=\\d))?(?<ap2>" + N + ")" + WHOLE + ")?)"
        + "|(?<iso>" + NOT_AFTER + "ISO\\s*(?<iso1>" + N + ")(?:\\s*" + DASH + "\\s*(?<iso2>" + N + ")" + WHOLE + ")?)"
        + "|(?<ev>" + NOT_AFTER + "EV\\s*(?<evs>[+\\-−])?\\s*(?<ev1>" + N + ")(?![\\p{N}]))"
        + "|(?<sl>(?<![\\p{IsLatin}\\p{N}./])(?<!\\d,)" + N + "(?:\\s*/\\s*" + N + ")+\\s*(?:millimet(?:er|re)s?|mm|毫米)(?!\\p{IsLatin}))"
        + "|(?<cl>" + NUMBER_START + N + "(?:(?:\\s*,\\s+|\\s*,?\\s+and\\s+|、|和)" + N + ")+\\s*(?:millimet(?:er|re)s?|mm|毫米)(?!\\p{IsLatin}))"
        + "|(?<fr>(?<![\\p{IsLatin}\\p{N}./])(?<!\\d,)(?<fn1>\\d+)\\s*/\\s*(?<fd1>" + N + ")(?:\\s*" + DASH + "\\s*(?<fn2>\\d+)\\s*/\\s*(?<fd2>" + N + "))?(?:\\s*(?<fu>secs?|seconds?|s|秒)(?!\\p{IsLatin}))?)"
        + "|(?<mult>" + NOT_AFTER + "[x×]\\s?(?<m1>\\d+)(?![\\p{L}\\p{N}]|[.,]\\d)|" + NUMBER_START + "(?<m2>\\d+)\\s?[x×](?![\\p{L}\\p{N}]))"
        + "|(?<num>" + NUMBER_START + "(?<sign>[+\\-−])?(?<n1>" + N + ")(?:\\s*" + DASH + "\\s*(?<n2>" + N + "))?"
        + "(?:\\s*(?<unit>millimet(?:er|re)s?|mm|毫米|secs?|seconds?|s|秒|EV|stops?|档|%|per ?cent|photos?|photographs?|images?|frames?|files?|shots?|pictures?|exposures?|pics?|times|张|幅|帧|次"
        + "|(?:(?:different|distinct|separate|unique)\\s+)?(?:lens(?:es)?|cameras?|bod(?:y|ies)|apertures?|f-numbers?|focal lengths?|shutter speeds?|ISO (?:values|settings|speeds)|ISOs)"
        + "|ISO)(?!\\p{IsLatin}))?)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RANGE_GAP = Pattern.compile("(?iu)\\s*(?:[\\p{L}%°]{1,12}\\.?\\s*)?(?:" + DASH + "|(?:up )?to|through|until|至|到)\\s*");
    private static final Pattern RANGE_WORDS = Pattern.compile("(?iu)(\\b(?:between)\\s+(?:f/|ISO\\s*)?[+\\-−]?\\d[\\d.,/]*\\s*(?:[\\p{L}%°]{1,12}\\.?\\s*)?)(and)(?=\\s+(?:f/|ISO\\s*)?[+\\-−]?\\d)");

    /** "between 50 and 85 mm" becomes a dash range of the same length (positions unchanged); "to"/"through" ranges are read as they are. */
    private static String joinRanges(String text) {
        var m = RANGE_WORDS.matcher(text); var out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + "~" + " ".repeat(m.group(2).length() - 1)));
        return m.appendTail(out).toString();
    }

    /** Figures of one token; null when the digits belong to a word ("10fps") or cannot be a value (an impossible date, an endless number). */
    private static List<Figure> figures(Matcher m, String scan) {
        try { return read(m, scan); }
        catch (java.time.DateTimeException | NumberFormatException | ArithmeticException unreadable) { return null; }
    }
    private static List<Figure> read(Matcher m, String scan) {
        int start = m.start(), end = m.end();
        boolean afterOf = OF_BEFORE.matcher(scan.substring(Math.max(0, start - 16), start)).find();
        if (m.group("date") != null) return List.of(new Figure(start, end, DATE, LocalDate.parse(m.group("date")).toEpochDay(), 0, 0, 0, false));
        if (m.group("offset") != null) {
            String sign = m.group("os") != null ? m.group("os") : m.group("os2");
            String hours = m.group("oh") != null ? m.group("oh") : m.group("oh2"), minutes = m.group("om") != null ? m.group("om") : m.group("om2");
            double total = Integer.parseInt(hours) * 60 + (minutes == null ? 0 : Integer.parseInt(minutes));
            return List.of(new Figure(start, end, OFFSET, sign.equals("+") ? total : -total, 0, 0, 0, false));
        }
        if (m.group("time") != null) {
            int seconds = Integer.parseInt(m.group("th")) * 3600 + Integer.parseInt(m.group("tm")) * 60 + (m.group("ts") == null ? 0 : Integer.parseInt(m.group("ts")));
            return List.of(new Figure(start, end, TIME, seconds, m.group("ts") == null ? 0 : 1, 0, 0, false));
        }
        if (m.group("ap") != null) return pair(start, end, APERTURE, m.group("ap1"), m.group("ap2"), false, false);
        if (m.group("iso") != null) return pair(start, end, ISO, m.group("iso1"), m.group("iso2"), false, false);
        if (m.group("ev") != null) {
            double v = parse(m.group("ev1")); boolean negative = m.group("evs") != null && !m.group("evs").equals("+");
            return List.of(new Figure(start, end, EV, negative ? -v : v, decimals(m.group("ev1")), 0, 0, false));
        }
        if (m.group("sl") != null || m.group("cl") != null) { // "15/50/85 mm", "15, 50 and 85 mm": a list of recorded values, not a fraction
            var found = new ArrayList<Figure>(); var value = NUMBER.matcher(m.group("sl") != null ? m.group("sl") : m.group("cl"));
            while (value.find()) found.add(new Figure(start, end, FOCAL, parse(value.group()), decimals(value.group()), 0, 0, false));
            return found;
        }
        if (m.group("fr") != null) {
            long n1 = Long.parseLong(m.group("fn1")); double d1 = parse(m.group("fd1"));
            boolean shutter = m.group("fu") != null || n1 == 1;
            if (!shutter) return List.of(new Figure(start, end, UNTYPED, n1, 0, 0, 0, afterOf), new Figure(start, end, UNTYPED, d1, decimals(m.group("fd1")), 0, 0, true));
            var found = new ArrayList<Figure>();
            if (d1 > 0) found.add(new Figure(start, end, SHUTTER, n1 / d1, -1, n1, (long) d1, false));
            if (m.group("fn2") != null) { long n2 = Long.parseLong(m.group("fn2")); double d2 = parse(m.group("fd2")); if (d2 > 0) found.add(new Figure(start, end, SHUTTER, n2 / d2, -1, n2, (long) d2, false)); }
            if (found.size() == 2) { found.get(0).partner = found.get(1); found.get(1).partner = found.get(0); }
            return found;
        }
        if (m.group("mult") != null) {
            var count = new Figure(start, end, COUNT, Double.parseDouble(m.group("m1") != null ? m.group("m1") : m.group("m2")), 0, 0, 0, false);
            count.photos = true; return List.of(count);
        }
        String unit = Objects.toString(m.group("unit"), "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (unit.isEmpty() && end < scan.length() && Character.isLetter(scan.charAt(end)) && Character.UnicodeScript.of(scan.charAt(end)) == Character.UnicodeScript.LATIN) return null;
        String metric = unit.isEmpty() ? UNTYPED : unit.startsWith("millimet") || unit.equals("mm") || unit.equals("毫米") ? FOCAL
            : unit.matches("secs?|seconds?|s|秒") ? SHUTTER : unit.equals("ev") || unit.startsWith("stop") || unit.equals("档") ? EV
            : unit.equals("%") || unit.startsWith("per") ? PERCENT : unit.equals("iso") ? ISO : COUNT;
        // A negative value is kept as stated: only exposure compensation has negative facts, so nothing else can match it.
        boolean negative = m.group("sign") != null && !m.group("sign").equals("+");
        var found = pair(start, end, metric, m.group("n1"), m.group("n2"), negative, afterOf);
        String subject = unit.contains("lens") ? "field:lens" : unit.contains("camera") || unit.contains("bod") ? "field:camera"
            : unit.contains("aperture") || unit.contains("f-number") ? "field:" + APERTURE : unit.contains("focal") ? "field:" + FOCAL
            : unit.contains("shutter") ? "field:" + SHUTTER : unit.startsWith("iso ") || unit.equals("isos") ? "field:" + ISO : "";
        boolean photos = unit.matches("photos?|photographs?|images?|frames?|files?|shots?|pictures?|exposures?|pics?|times|张|幅|帧|次");
        found.forEach(f -> { f.countSubject = subject; f.photos = photos; });
        if (metric.equals(UNTYPED) && OF_AFTER.matcher(scan.substring(end, Math.min(scan.length(), end + 24))).lookingAt()) found.forEach(f -> { f.metric = COUNT; f.photos = true; }); // "1 of the photos"
        if ((metric.equals(UNTYPED) || metric.equals(COUNT)) && AMONG_AFTER.matcher(scan.substring(end)).lookingAt()) // "7 张照片中有 2 张": the 7 is the total
            found.forEach(f -> { f.metric = COUNT; f.afterOf = true; });
        return found;
    }
    private static List<Figure> pair(int start, int end, String metric, String first, String second, boolean negative, boolean afterOf) {
        var found = new ArrayList<Figure>();
        found.add(new Figure(start, end, metric, (negative ? -1 : 1) * parse(first), decimals(first), 0, 0, afterOf));
        if (second != null) {
            var other = new Figure(start, end, metric, parse(second), decimals(second), 0, 0, false);
            found.getFirst().partner = other; other.partner = found.getFirst(); found.add(other);
        }
        return found;
    }
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");
    private static double parse(String token) { return Double.parseDouble(THOUSANDS.matcher(token).matches() ? token.replace(",", "") : token.replace(',', '.')); }
    private static int decimals(String token) {
        String normal = THOUSANDS.matcher(token).matches() ? token.replace(",", "") : token.replace(',', '.');
        int dot = normal.indexOf('.'); return dot < 0 ? 0 : normal.length() - dot - 1;
    }

    // ---------------------------------------------------------------- what a figure is about

    private static final Pattern NUMBER = Pattern.compile(N);
    private static final Pattern OF_BEFORE = Pattern.compile("(?iu)(?<!total )\\b(?:out of|of)\\s+(?:the\\s+|all\\s+)?$|\\bn\\s*=\\s*$");
    private static final Pattern AMONG_AFTER = Pattern.compile("\\s*(?:照片|图片|相片)?\\s*(?:当中|之中|中|里)");
    private static final Pattern OF_AFTER = Pattern.compile("(?iu)(?:\\s+(?:out of|of)\\s+(?:the\\s+|all\\s+)?(?:\\d|photos?|frames?|images?|shots?|pictures?)|\\s*/\\s*\\d)");
    /** "an ISO of 800", "exposure compensation of 0": the metric word names the number. */
    private static final Pattern OF_METRIC = Pattern.compile("(?iu)\\b(focal lengths?|aperture(?: value| setting)?|f-number|f-stop|f stop|ISO(?: value| setting| speed| sensitivity)?|shutter speed|exposure time|exposure compensation|exposure value|EV)\\s+(?:of|at|was|is|=)\\s*$|(焦距|光圈|快门|感光度|曝光补偿)(?:为|是)?\\s*$");
    private static final Pattern OF_WORD = Pattern.compile("(?iu)\\b(?:out of|of)\\s+(?:the\\s+|all\\s+)?$");
    private static final Pattern BOUND_BEFORE = Pattern.compile("(?iu)\\b(?:over|above|under|below)\\s+$");
    /** A listed value on its own ("The 50 mm"): nothing but an article around the figure. */
    private static final Pattern BARE = Pattern.compile("(?iu)\\s*(?:the|both|either|a|an|和|及)?\\s*");
    /** "Unlike the capture time, ...": the named field must not have the same count. */
    private static final Pattern CONTRAST_FIELD = Pattern.compile("(?iu)\\b(?:unlike|in contrast to|compared (?:with|to)|whereas|except for)\\s+(?:the\\s+)?(lens(?:es)?(?: models?)?|cameras?(?: models?)?|bod(?:y|ies)|focal lengths?|shutter(?: speeds?)?|apertures?|ISO|exposure compensation|capture times?|timestamps?)\\b");
    /** "more than 5", "about 70%", "约 5 张": a bound or an approximation, not the recorded figure. */
    private static final Pattern QUALIFIER_BEFORE = Pattern.compile("(?iu)\\b(?:more than|over|above|at least|at most|fewer than|less than|under|below|nearly|almost|about|around|roughly|approximately|approx\\.?|up to|close to|just over|just under|some)\\s+$|(?:超过|不到|不足|约|大约|近|将近|至少|最多|多于|少于|不止|不少于|不多于)\\s*$");
    /** A clause that follows a figure and describes it ("f/4, the aperture you used most, ..."; "ISO 1600, the planned setting, ..."). */
    private static final Pattern APPOSITIVE = Pattern.compile("(?iu)\\s*(?:(?:the|a|an|its|your|their|this|that|which|where|who|when)\\b|这是|这|是|即|也就是)");
    /** A following clause that refers back to the figure ("本次大部分照片都用了这个光圈", "most photos used this setting"). */
    private static final Pattern BACK_REFERENCE = Pattern.compile("(?iu)这个|这一|该(?:光圈|焦距|快门|设置|设定|值)|那个|\\b(?:this|that) (?:setting|value|aperture|focal length|shutter speed|ISO)\\b");
    /** A source named right after a figure ("85 mm in the earlier session", "50 mm last time"). */
    private static final Pattern POST_LINK = Pattern.compile("(?iu)\\s*(?:(?:in|during|for|from|at|on|of)\\s+)?(?:the\\s+|this\\s+|that\\s+|your\\s+|its\\s+)?");
    /** The clause opens with the setting that governs its count ("At 50 mm, the lens recorded 7 photos"; "Of the photos taken at f/2.8, ..."; "在 f/4 光圈下"). */
    private static final Pattern CONTEXT = Pattern.compile("(?iu)\\s*(?:(?:at|with|using|for|in|of|among|across)\\b|在|于|以|用)");
    /** "complete EXIF metadata": every field recorded on every counted photo. */
    private static final Pattern COMPLETE = Pattern.compile("(?iu)\\b(?:complete|full|all(?: the)?|every)\\s+(?:exif\\s+)?(?:metadata|data|settings|fields)\\b|完整|齐全");
    /** "Of the 7 photos with recorded capture times": the total is of that field. */
    private static final Pattern WITH_FIELD = Pattern.compile("(?iu)\\b(?:with|that (?:had|recorded)|having)\\s+(?:a\\s+)?(?:recorded\\s+|usable\\s+|valid\\s+)?(?:\\w+\\s+)?(?:data|metadata|values?|models?|times?|timestamps?|settings?)\\b|有.{0,6}(?:数据|记录)");
    private static final Pattern FREQ_HIGH = Pattern.compile("(?iu)频率最高|次数最多|使用最多|用得最多|出现最多|最频繁");
    private static final Pattern PLAN_CHANGE = Pattern.compile("(?iu)\\b(?:update|updating|change|changing|revise|revising|adjust|adjusting|amend|amending|set|setting|plan for)\\s+(?:the|your)?\\s*$|调整|修改|更新");
    private static final Pattern AFTER_QUALIFIER = Pattern.compile("\\s*(?:张|幅|帧|次)?\\s*(?:以上|以下|左右|多|余)");
    private static final Pattern EITHER = Pattern.compile("(?iu)\\b(?:either|or)\\b|或|或者");
    private static final Pattern NO_DATA = Pattern.compile("(?iu)\\bno\\s+(?:\\w+\\s+){0,3}(?:data|metadata|values?)\\b|没有.{0,8}(?:数据|记录)");
    private static final Pattern RELATIVE_TAIL = Pattern.compile("(?iu),\\s*(?:which|who|whose|where|that)\\b");
    /** "Of the 7 photos ..." opening the clause. */
    private static final Pattern OF_OPENING = Pattern.compile("(?iu)\\s*(?:of|among|out of)\\s+(?:the\\s+|all\\s+)?");
    private static final Pattern INSTEAD_BEFORE = Pattern.compile("(?iu)\\b(?:instead of|rather than)\\s*$");
    private static final Pattern CONTRAST_OPENING = Pattern.compile("(?iu)\\b(?:compared (?:with|to)|unlike|than|instead of|rather than|versus|against|whereas|while)\\s*$|(?:相比|对比|不同于|而|但)\\s*$");
    private static final Pattern CONTRAST_OPENING_START = Pattern.compile("(?iu)\\s*(?:compared (?:with|to)|unlike|in contrast to|whereas|while|versus)\\b");
    /** Sentence ends; abbreviations ("avg.", "sec.") and decimal points are not. */
    private static final Pattern SENTENCE = Pattern.compile("(?iu)(?<!\\b(?:avg|max|min|sec|secs|approx|ca|vs|e\\.g|i\\.e))\\.(?=\\s|$)|[!?。！？\\n]");
    /** Clause ends. Parentheses are handled by {@link #clause}; a relative clause (", which you used most") stays with its figure. */
    private static final Pattern CLAUSE = Pattern.compile("(?iu)(?:(?<!\\d),|,(?!\\d))(?!\\s*(?:which|who|whose)\\b)|[;；，：]|:(?!\\d)|\\s(?:and|but|while|whereas|versus|compared (?:with|to)|against|than|instead of|rather than|unlike)\\s|而|但|相比|对比|不同于");
    private static final Pattern PLAN = Pattern.compile("(?iu)\\b(?:planned|plan's|(?:\\p{L}+'s|the|your|this|that|its|their|our|a|shoot|shooting|session|approved|original)\\s+plans?|approved|intended|targeted|scheduled|schedule|windows?|specified|called for|prescribed)\\b|计划|预定|方案|已批准|批准的");
    private static final Pattern HISTORY = Pattern.compile("(?iu)\\b(?:earlier|previous|previously|prior|formerly|in the past|past sessions?|last (?:session|shoot|time|outing)|that (?:session|shoot))\\b|之前|上一次|上一场|上一轮|上一组|上场|上次|上回|前一次|前一场|以往|以前|此前|先前|过去");
    private static final Pattern CURRENT = Pattern.compile("(?iu)\\b(?:this (?:session|shoot|time|set|batch|round|outing)|current (?:session|shoot)|(?:latest|most recent|newest) (?:session|shoot)|these (?:photos|frames|images|shots)|today)\\b|本次|这次|这组|这批|本组|当前|今天");
    /** Recorded data. Chinese verbs carry no tense, so only completed forms ("用了", "拍摄了") state what was recorded. */
    private static final Pattern ACTUAL = Pattern.compile("(?iu)(?<!most[ -])\\b(?:used|recorded|actual|actually|shot|captured|took|taken|exif|relied|leaned|stuck|worked|stayed|remained|kept|throughout|ended up|in practice|did the same|likewise)\\b|实际|记录|拍摄了|拍了|用了|用的是|使用了|使用的是|采用了|全程|(?:均|都|全部)(?:采用|使用|用|设为|拍摄)");
    private static final Pattern PAST = Pattern.compile("(?iu)\\b(?:was|were|had|did|made|stayed|remained|recommended|suggested|advised|proposed|specified|required)\\b");
    private static final Pattern SUGGEST = Pattern.compile("(?iu)\\b(?:try|trying|consider|could|should|aim|next time|in future|future|suggest\\w*|recommend\\w*)\\b|建议|下次|尝试|今后|未来");
    private static final Pattern CAUSAL = Pattern.compile("(?iu)\\b(?:since|because|(?<!such )as|given that|as a result)\\b|因为|由于");
    private static final Pattern CONTRAST_BEFORE = Pattern.compile("(?iu)(?:\\bunlike|\\bin contrast to|\\bcompared (?:with|to)|\\bversus|\\bvs\\.?|\\binstead of|\\brather than|\\bdifferent from|\\bthan|\\bsince|\\bcontrary to|\\bdespite|\\bagainst|\\bdeviating from|\\bdeparting from|\\bnot|\\babove|\\bbelow|\\bexceeding|\\bbeyond|不同于|不像|相比|比|高于|低于)(?:\\s+(?:the|that|this|its|an?|in the|in))?\\s*$");
    /** A comparison word between a figure and a later source: that source is about what follows, not the figure. */
    private static final Pattern CONTRAST_WORD = Pattern.compile("(?iu)\\b(?:unlike|compared|than|instead|rather|versus|vs|different|since|contrary|despite|against|deviating|departing|not|above|below|exceeding|beyond)\\b|不同于|不像|相比|比|高于|低于");
    /** "与上次相比", "和计划不同": the comparison word follows the source in Chinese ("上次拍摄比较依赖": 比较 there means "rather"). */
    private static final Pattern CONTRAST_AFTER = Pattern.compile("[^。！？.!?,;:]{0,16}?(?:相比|相较|对比|比起|不同)");
    private static final Pattern ECHO_BEFORE = Pattern.compile("(?iu)(?:\\b(?:as in|as with|same as|similar to|just as|like|matched|matches|matching|mirrored|mirroring|repeated|repeating)(?:\\s+(?:it|they|was|were|did|had|in|the|that|this|its|an?|for)){0,4}"
        + "|\\b(?:identical to|unchanged from|consistent with|in line with|in keeping with|following|as|per|according to)(?:\\s+(?:the|that|this|its|an?|in the))?|与|和|同|按照|依照|遵照|按|如|照)\\s*$");
    private static final Pattern NEGATION = Pattern.compile("(?iu)\\b(?:none|never|no (?:photos?|frames?|images?|shots?|pictures?)|not|neither|nor)\\b|没有|未|从不");
    private static final Pattern RANK = Pattern.compile("(?iu)\\b(?:second|third|fourth)(?:-|\\s+)(?:most|highest|lowest|largest|smallest|longest|shortest|widest|fastest|slowest)\\b"
        + "|\\b(?:least|fewest|less (?:common|frequent|often|used)|rarely|seldom|occasionally)\\b|第二|第三|最少");
    private static final Pattern RESPECTIVELY = Pattern.compile("(?iu)\\brespectively\\b|分别|各自");
    private static final Pattern SAVED = Pattern.compile("(?iu)\\b(?:saved|reviewed|review)\\b|保存|复盘");
    private static final Pattern SPEED = Pattern.compile("(?iu)\\bspeeds?\\b|速度");
    private static final Map<String, Pattern> AGGREGATIONS = Map.of(
        "mean", Pattern.compile("(?iu)\\b(?:average|mean|avg|on average)\\b|平均"),
        "median", Pattern.compile("(?iu)\\bmedian\\b|中位"),
        "min", Pattern.compile("(?iu)\\b(?:minimum|min|lowest|shortest|widest|fastest|quickest|smallest|(?:wide|short) end)\\b|最小|最低|最短|最快|广角端"),
        "max", Pattern.compile("(?iu)\\b(?:maximum|max|highest|longest|narrowest|slowest|largest|peak(?:ed|s)?|(?:long|tele(?:photo)?) end)\\b|最大|最高|最长|最慢|长焦端"),
        // the value most photos used: "mostly", "mainly", "以 85mm 为主" claim the mode, not merely a frequent value
        "mode", Pattern.compile("(?iu)\\bmost[-\\s](?:used|frequent(?:ly)?|common(?:ly)?|often|popular)\\b|\\bused (?:\\w+ )?most\\b|\\bmost of (?:the|this|that) (?:photos|frames|images|shots|time|session|shoot)\\b|\\bmost (?:photos|frames|images|shots)\\b"
            + "|\\b(?:majority|commonest|mostly|mainly|predominantly|largely|chiefly|primarily|usually|typically|generally|for the most part)\\b|最常|最多|大多数|多数|大部分|大多|主要|通常|一般|为主|居多|占多数|频率最高|次数最多|使用最多|最频繁"),
        "frequent", Pattern.compile("(?iu)\\b(?:frequently|often|typical|commonly|regularly|prefer\\w*|favou?r\\w*|go-to|dominant|staple|usual|standard|default|heavily)\\b|经常|常用|平时|习惯"));
    /** Count kinds a clause names; a stated count must then be that kind of count. */
    private static final Map<String, Pattern> COUNT_KINDS = Map.of(
        "missing", Pattern.compile("(?iu)\\b(?:missing|without|lack(?:ed|ing|s)?|no (?:\\w+ ){0,2}(?:data|metadata|value|model|record))\\b|缺少|缺失|没有"),
        "imported", Pattern.compile("(?iu)\\bimported\\b|导入"),
        "usable", Pattern.compile("(?iu)\\busable\\b|可用"),
        "failed", Pattern.compile("(?iu)\\b(?:failed|could not be (?:read|analy[sz]ed))\\b|失败"),
        "invalid", Pattern.compile("(?iu)\\b(?:invalid|unreadable|impossible)\\b|无效"),
        "distinct", Pattern.compile("(?iu)\\b(?:different|distinct|unique|separate)\\b|不同"));
    private static final Map<String, Pattern> KEYWORDS = Map.of(
        FOCAL, Pattern.compile("(?iu)\\bfocal(?: lengths?)?\\b|焦距"), SHUTTER, Pattern.compile("(?iu)\\bshutter\\b|\\bexposure times?\\b|快门"),
        APERTURE, Pattern.compile("(?iu)\\bapertures?\\b|\\bf-(?:numbers?|stops?)\\b|\\bf stops?\\b|光圈"), ISO, Pattern.compile("(?iu)\\bISO\\b|感光度"),
        EV, Pattern.compile("(?iu)\\b(?:exposure )?compensation\\b|曝光补偿"));
    /** Fields a count can be of without a stated value ("lens model is missing for 2 photos"). */
    private static final Map<String, Pattern> FIELDS = Map.of(
        "lens", Pattern.compile("(?iu)\\blens(?:es)?\\b|镜头"), "camera", Pattern.compile("(?iu)\\b(?:cameras?|bod(?:y|ies))\\b|机身|相机"),
        FOCAL, KEYWORDS.get(FOCAL), SHUTTER, KEYWORDS.get(SHUTTER), APERTURE, KEYWORDS.get(APERTURE), ISO, KEYWORDS.get(ISO), EV, KEYWORDS.get(EV),
        "captureTime", Pattern.compile("(?iu)\\b(?:timestamps?|capture times?)\\b|时间戳"));
    private static final Pattern REVISION_BEFORE = Pattern.compile("(?iu)\\brevision\\s*$|版本\\s*$");
    /** Places of photos relative to a planned range or the planned lenses; a count in a plan comparison must name its place. */
    private static final Map<String, Pattern> ROLES = Map.of(
        "within", Pattern.compile("(?iu)\\b(?:within|inside|in range|met|meet|meets|matched|match(?:es|ing)?|on (?:plan|target)|as planned|followed|complied|hit)\\b|范围内|计划内|符合|达到|一致"),
        "below", Pattern.compile("(?iu)\\b(?:below|under|lower|shorter|faster|wider|less than|fewer than|beneath)\\b|低于|更低|更短|更快|更大"),
        "above", Pattern.compile("(?iu)\\b(?:above|over|higher|longer|slower|narrower|more than|exceed\\w*|beyond)\\b|高于|更高|更长|更慢|更小|超过"),
        "outside", Pattern.compile("(?iu)\\b(?:outside|beyond|out of range|off plan|deviat\\w*|missed)\\b|范围外|之外|超出"));
    /** Wording that claims one value for every photo ("all photos were at f/2.8", "ISO 800 throughout"). */
    private static final Pattern UNIVERSAL = Pattern.compile("(?iu)(?<!of )\\ball\\b|\\b(?:every(?:thing)?|throughout|exclusively|solely|always|entirely|sole|the only|only one)\\b|\\beach\\s+(?:photo|frame|image|shot|picture|exposure)\\b"
        + "|\\b(?:entire|whole)\\s+(?:session|shoot)\\b|(?<!占)全部|(?<!占)所有|全程|一直|始终|每张|都是|均为|均是|全为|全是|唯一");
    private static final Pattern ONLY_BEFORE = Pattern.compile("(?iu)\\b(?:only|solely|exclusively)\\s+(?:\\p{L}+\\s+){0,2}(?:at|with|on)?\\s*$|只用|仅用|只拍");
    /** "the rest at 85 mm", "the remaining 2 photos": an implied count the evidence does not hold. */
    private static final Pattern REMAINDER = Pattern.compile("(?iu)\\b(?:the rest|remaining|remainder|the others|all others|the other(?:\\s+\\d+)?\\s+(?:photos?|frames?|images?|shots?)|the (?:last|final) (?:photo|frame|shot))\\b|其余|其他|剩下|剩余");
    private static final Pattern EACH = Pattern.compile("(?iu)\\b(?:each|apiece)\\b|各|每个");
    /** Both sessions at once ("in both sessions", "both this time and last time"). */
    private static final Pattern BOTH = Pattern.compile("(?iu)\\bboth (?:sessions|shoots|times|this|last|in)\\b|\\bin both\\b|\\b(?:each|every|either) (?:session|shoot)\\b|\\b(?:each|all|every one) of (?:your|the|both|these) (?:sessions|shoots)\\b"
        + "|\\ball (?:your|of your|the|these) (?:sessions|shoots)\\b|\\bacross (?:both|sessions|your sessions|all sessions|the sessions)\\b|两次|两场|每次拍摄|每场");
    /** "As with the lens model, ...": the same count is claimed for the named field too. */
    private static final Pattern ECHO_FIELD = Pattern.compile("(?iu)\\b(?:as with|like|same as|just as|as for|similar to|as did|as was)\\s+(?:the\\s+)?(lens(?:es)?(?: models?)?|cameras?(?: models?)?|bod(?:y|ies)|focal lengths?|shutter(?: speeds?)?|apertures?|ISO|exposure compensation|capture times?|timestamps?)\\b");
    /** In advice, a figure the sentence refers back to ("the 1/60 s you relied on", "the 1/60 s frames") is a stated fact, not a proposal. */
    private static final Pattern DEFINITE_BEFORE = Pattern.compile("(?iu)\\b(?:the|your|those|these|its)\\s+(?:(?!(?:to|at|of|for|with|from|on|in|by)\\b)\\p{L}+\\s+){0,2}$");
    private static final Pattern INDEFINITE_BEFORE = Pattern.compile("(?iu)\\b(?:a few|a couple of|some|several|more|extra|additional|a|an|any)\\s+$");
    private static final Pattern PHOTO_AFTER = Pattern.compile("(?iu)\\s*(?:photos?|frames?|images?|shots?|pictures?|exposures?)\\b|\\s*的?照片");
    /** A text before a deterministic line that claims more than the line: an earlier session, both sessions, a field it is likened to or contrasted with. */
    private static final Pattern LEAD_CLAIM = Pattern.compile("(?iu)\\b(?:unlike|compared|than|instead|rather|both|as with|like|same as|just as|similar|contrary|despite|also|too|likewise)\\b|同样|也|与|和|不同");
    private static final Pattern RELATIVE_COMMA = Pattern.compile("(?iu),\\s*(?:who|which|whose)\\b");
    /** What joins listed clauses ("The mean and median ISO", "the most common aperture, shutter speed and ISO"). */
    private static final Pattern LIST_JOIN = Pattern.compile("(?iu)(?:\\s*(?:,|and|or|&|、|和|及|与)\\s*)+");
    /** The physically smallest aperture is the largest f-number ("the smallest aperture", not "the smallest f-number"). */
    private static final Pattern SMALLEST = Pattern.compile("(?iu)\\bsmallest\\s+(?:\\w+\\s+)?aperture|最小光圈"), LARGEST = Pattern.compile("(?iu)\\b(?:largest|biggest)\\s+(?:\\w+\\s+)?aperture|最大光圈");
    private static final Pattern HIGH = Pattern.compile("(?iu)\\b(?:highest|maximum|max|peak\\w*)\\b|最高|最大"), LOW = Pattern.compile("(?iu)\\b(?:lowest|minimum|min)\\b|最低|最小");

    private static int[] sentence(String text, int at) { return cut(text).sentence(at); }
    /** A figure inside parentheses has the parenthetical as its clause; outside, parenthetical text never ends a clause ("ISO 640 (S17) was the most frequent"). */
    private static int[] clause(String text, int at) { return cut(text).clause(at); }

    /** Sentence and clause boundaries of one text, found once: a statement asks for them for every figure and fact. */
    private static final class Cut {
        final String text; final int[] open, digits; final List<int[]> sentences = new ArrayList<>(), raw = new ArrayList<>(), outer = new ArrayList<>();
        Cut(String text) {
            this.text = text; int n = text.length();
            open = new int[n + 1]; digits = new int[n + 1];
            var stack = new ArrayDeque<Integer>(); var blanked = new StringBuilder(text); int depth = 0;
            for (int k = 0; k < n; k++) {
                open[k] = stack.isEmpty() ? -1 : stack.peek(); // the nearest unclosed '(' before position k
                char ch = text.charAt(k);
                digits[k + 1] = digits[k] + (Character.isDigit(ch) ? 1 : 0);
                if (ch == '(') stack.push(k); else if (ch == ')' && !stack.isEmpty()) stack.pop();
                if (ch == '(') depth++;
                if (depth > 0) blanked.setCharAt(k, ' '); // the text outside parentheses
                if (ch == ')' && depth > 0) depth--;
            }
            open[n] = stack.isEmpty() ? -1 : stack.peek();
            spans(SENTENCE, text, sentences);
            spans(SENTENCE, text, raw); spans(CLAUSE, text, raw);
            spans(SENTENCE, blanked, outer); spans(CLAUSE, blanked, outer);
        }
        private static void spans(Pattern boundary, CharSequence text, List<int[]> into) { Matcher m = boundary.matcher(text); while (m.find()) into.add(new int[]{m.start(), m.end()}); }
        /** From the last boundary ending at or before {@code at} to the first starting at or after it; boundaries across it are ignored. */
        private int[] bounds(List<int[]> spans, int at) {
            int start = 0, end = text.length();
            for (int[] s : spans) { if (s[1] <= at) start = Math.max(start, s[1]); else if (s[0] >= at) end = Math.min(end, s[0]); }
            return new int[]{start, end};
        }
        int[] sentence(int at) { return bounds(sentences, at); }
        int[] clause(int at) {
            int o = open[Math.max(0, Math.min(at, text.length()))];
            if (o < 0) return bounds(outer, at);
            // Inside parentheses: the parenthetical, unless it names the value of a phrase before it ("the most used focal length (85 mm)",
            // "your usual aperture (f/1.8)"): then it shares that phrase's clause.
            int[] around = bounds(outer, o);
            if (digits[o] == digits[around[0]]) return around;
            int close = text.indexOf(')', at); if (close < 0) close = text.length();
            int[] inner = bounds(raw, at);
            return new int[]{Math.max(inner[0], o + 1), Math.min(inner[1], close)};
        }
    }
    private static final ThreadLocal<Cut[]> CUTS = ThreadLocal.withInitial(() -> new Cut[2]);
    /** The boundaries of this very text (the statement and its masked scan are checked together, one statement at a time per thread). */
    private static Cut cut(String text) {
        var cuts = CUTS.get();
        for (var c : cuts) if (c != null && c.text == text) return c;
        var made = new Cut(text);
        cuts[1] = cuts[0]; cuts[0] = made;
        return made;
    }
    private static int last(Pattern pattern, String text) { int found = -1; Matcher m = pattern.matcher(text); while (m.find()) found = m.start(); return found; }
    private static boolean has(Pattern pattern, String text) { return pattern.matcher(text).find(); }

    private static List<Marker> markers(String scan) {
        var found = new ArrayList<Marker>();
        for (var entry : Map.of(Kind.HISTORY, HISTORY, Kind.CURRENT, CURRENT, Kind.PLAN, PLAN, Kind.ACTUAL, ACTUAL).entrySet()) {
            Matcher m = entry.getValue().matcher(scan);
            while (m.find()) {
                String before = scan.substring(Math.max(0, m.start() - 32), m.start());
                boolean contrastAfter = CONTRAST_AFTER.matcher(scan).region(m.end(), scan.length()).lookingAt(); // "与上次相比"
                // A source in a clause opened by a comparison ("compared with ISO 1600 most often in the earlier session") is contrasted:
                // the clause is about it, but the statement's topic stays.
                int opened = clause(scan, m.start())[0];
                boolean contrastClause = (entry.getKey() == Kind.HISTORY || entry.getKey() == Kind.PLAN)
                    && (CONTRAST_OPENING.matcher(scan.substring(Math.max(0, opened - 16), opened)).find() || CONTRAST_OPENING_START.matcher(scan).region(opened, m.start()).lookingAt());
                boolean contrast = contrastAfter || contrastClause || CONTRAST_BEFORE.matcher(before).find();
                found.add(new Marker(m.start(), entry.getKey(), contrast, !contrast && ECHO_BEFORE.matcher(before).find()));
            }
        }
        found.sort(Comparator.comparingInt(Marker::start));
        return found;
    }

    /** A bare number: a revision, a count ("6 of 7"), or, by its metric word ("ISO of 800", "Median ISO: 800"), a measurement. */
    private static void type(Figure figure, String scan, List<Figure> all) {
        int[] clause = clause(scan, figure.start);
        String before = scan.substring(Math.max(0, figure.start - 40), figure.start);
        var named = OF_METRIC.matcher(before);
        if ((figure.metric.equals(UNTYPED) || figure.metric.equals(COUNT) && figure.countSubject.isEmpty() && figure.afterOf) && named.find()) {
            String word = (named.group(1) != null ? named.group(1) : named.group(2)).toLowerCase(Locale.ROOT);
            figure.metric = word.startsWith("focal") || word.equals("焦距") ? FOCAL : word.startsWith("aperture") || word.startsWith("f-") || word.startsWith("f ") || word.equals("光圈") ? APERTURE
                : word.startsWith("iso") || word.equals("感光度") ? ISO : word.contains("compensation") || word.equals("ev") || word.equals("exposure value") || word.equals("曝光补偿") ? EV : SHUTTER;
            figure.afterOf = false;
            return;
        }
        if (!figure.metric.equals(UNTYPED)) return;
        String own = scan.substring(clause[0], clause[1]), inClause = scan.substring(clause[0], Math.max(clause[0], figure.start));
        if (REVISION_BEFORE.matcher(inClause).find() || inClause.isBlank() && REVISION_BEFORE.matcher(label(scan, clause)).find()) { figure.metric = REVISION; return; }
        if (figure.afterOf) { figure.metric = COUNT; return; }
        // A metric word types a bare number only when the clause has no figure of that metric already ("ISO 640 on 2" counts photos).
        var words = KEYWORDS.entrySet().stream().filter(e -> e.getValue().matcher(own).find()).map(Map.Entry::getKey)
            .filter(metric -> all.stream().noneMatch(f -> f != figure && f != figure.partner && f.metric.equals(metric) && f.within(clause))).toList();
        if (words.isEmpty()) words = KEYWORDS.entrySet().stream().filter(e -> e.getValue().matcher(label(scan, clause)).find()).map(Map.Entry::getKey).toList();
        if (words.isEmpty() && !words(own).isEmpty()) { // "ISO 最低为 640，最高为 800": the statistic of the clause before, for the same field
            int[] prior = previous(scan, clause, sentence(scan, figure.start));
            if (prior != null) words = all.stream().filter(f -> f != figure && MEASURES.contains(f.metric) && f.within(prior)).map(f -> f.metric).distinct().toList();
        }
        if (words.size() == 1) figure.metric = words.getFirst();
    }
    /** The label before a colon that governs the rest of its sentence ("Most common settings: f/2.8, 1/1000 s and ISO 800"). */
    private static String label(String scan, int[] clause) {
        int[] sentence = sentence(scan, clause[0]);
        int colon = -1;
        for (int k = clause[0] - 1; k >= sentence[0]; k--) { char ch = scan.charAt(k); if ((ch == ':' || ch == '：') && (k + 1 >= scan.length() || !Character.isDigit(scan.charAt(k + 1)))) { colon = k; break; } }
        if (colon < 0) return "";
        return scan.substring(sentence[0], colon); // the whole label: "Median focal length and ISO: 85 mm and ISO 640"
    }

    /**
     * Sources a figure is claimed for. Wording in its own clause decides (earlier-session wording outranks "used"; "the plan
     * for this session" is the plan; planned together with recorded wording claims both; "this session" restores this
     * session); otherwise the topic carried from the statement so far ("In the earlier session, ISO 800 was used" is about
     * the earlier session; "this session" qualifying a plan does not end the plan topic). Contrasted wording ("unlike the
     * earlier session", "与上次相比") never becomes the topic; echoed wording ("as in the earlier session", "as planned",
     * "与计划一致") claims the same value for that source too, as do a following figure-less clause naming a source ("ISO 800,
     * the planned setting, ..."; "both this time and last time") and "in both sessions". Every source must support the
     * figure, except that counts in a clause about the plan may be either recorded or planned.
     */
    private static void scope(Figure f, String scan, List<Marker> markers, List<Figure> all) {
        int[] clause = clause(scan, f.start), sentence = sentence(scan, f.start);
        // A contrast after the figure is about a later figure ("All 7 photos used ISO 800, which is above the planned ISO 400").
        var own = markers.stream().filter(m -> m.start() >= clause[0] && m.start() < clause[1]
            && !(m.contrast() && m.start() > f.end && has(CONTRAST_WORD, scan.substring(f.end, m.start())))).toList();
        // A relative clause speaks of its antecedent: "ISO 800, which is above the planned ISO 400" (ISO 800 is recorded, ISO 400 planned);
        // "the earlier session, which used ISO 1600 most often" (ISO 1600 is the earlier session's).
        Matcher relative = RELATIVE_TAIL.matcher(scan).region(clause[0], clause[1]);
        if (relative.find()) {
            int at = relative.start(); // a relative clause without a figure describes the figure itself ("ISO 800, which is what the plan called for")
            boolean describes = all.stream().noneMatch(x -> x.start > at && x.within(clause));
            own = f.start < at ? (describes ? own : own.stream().filter(m -> m.start() < at).toList())
                : own.stream().filter(m -> m.start() > at || m.kind() != Kind.ACTUAL && m.start() >= at - 40).toList();
        }
        boolean history = own.stream().anyMatch(m -> m.kind() == Kind.HISTORY && !m.echo()), current = own.stream().anyMatch(m -> m.kind() == Kind.CURRENT);
        boolean plan = own.stream().anyMatch(m -> m.kind() == Kind.PLAN && !m.echo()), actual = own.stream().anyMatch(m -> m.kind() == Kind.ACTUAL);
        // A source named right after the figure binds it ("shifted from 85 mm in the earlier session to 50 mm in this session").
        var bound = own.stream().filter(m -> m.start() >= f.end && m.kind() != Kind.ACTUAL && !m.echo() && POST_LINK.matcher(scan.substring(f.end, m.start())).matches()
            && all.stream().noneMatch(x -> x != f && x != f.partner && x.start > f.start && x.start < m.start())).findFirst();
        var sources = new LinkedHashSet<Source>();
        if (bound.isPresent()) sources.add(bound.get().kind() == Kind.HISTORY ? Source.HISTORY : bound.get().kind() == Kind.PLAN ? Source.PLAN : Source.SESSION);
        else if (history) { sources.add(Source.HISTORY); if (current) sources.add(Source.SESSION); }
        else if (plan) { sources.add(Source.PLAN); if (actual) sources.add(Source.SESSION); }
        else if (current) sources.add(Source.SESSION);
        else {
            // The plan stays the topic only within its sentence and up to a semicolon; wording inside a relative clause
            // ("The photographer, who had favoured 85 mm in the earlier session, used ...") never carries beyond it.
            int segment = Math.max(sentence[0], scan.lastIndexOf(';', clause[0]) + 1);
            Source topic = Source.SESSION; int planClause = -1;
            for (var m : markers) {
                if (m.start() >= clause[0]) break;
                int[] at = clause(scan, m.start());
                if (RELATIVE_COMMA.matcher(scan).region(at[0], m.start()).find()) continue;
                switch (m.kind()) {
                    case HISTORY -> { if (!m.contrast() && !m.echo()) topic = Source.HISTORY; }
                    case CURRENT -> { if (!m.contrast() && at[0] != planClause) topic = Source.SESSION; }
                    case PLAN -> { if (!m.contrast() && !m.echo() && topic != Source.HISTORY && m.start() >= segment) { topic = Source.PLAN; planClause = at[0]; } } // "the earlier session's plan" stays earlier
                    case ACTUAL -> { if (topic == Source.PLAN) topic = Source.SESSION; }
                }
            }
            if (topic == Source.PLAN && (actual || INSTEAD_BEFORE.matcher(scan.substring(Math.max(0, clause[0] - 16), clause[0])).find())) topic = Source.SESSION; // "... instead of leaving it at ISO 800"
            sources.add(topic);
        }
        for (var m : markers)
            if (m.echo() && m.start() >= sentence[0] && m.start() < sentence[1]) {
                if (m.kind() == Kind.HISTORY) sources.add(Source.HISTORY);
                if (m.kind() == Kind.PLAN) sources.add(Source.PLAN);
            }
        int[] next = next(scan, clause, sentence);
        if (next != null && !scan.substring(clause[1], next[0]).contains(";") && all.stream().noneMatch(x -> x.within(next)) && !has(NEGATION, scan.substring(next[0], next[1])))
            for (var m : markers)
                if (m.start() >= next[0] && m.start() < next[1] && !m.contrast() && !m.echo() && (m.kind() != Kind.ACTUAL || sources.contains(Source.PLAN)))
                    sources.add(m.kind() == Kind.HISTORY ? Source.HISTORY : m.kind() == Kind.PLAN ? Source.PLAN : Source.SESSION); // "ISO 400 was planned and used", "..., and this session did the same"
        if (has(BOTH, scan.substring(sentence[0], sentence[1]))) { sources.add(Source.SESSION); sources.add(Source.HISTORY); }
        // "Every photo met the planned ISO 400": without a count, meeting the plan claims the recorded value too.
        if (sources.contains(Source.PLAN) && MEASURES.contains(f.metric) && ROLES.get("within").matcher(scan.substring(clause[0], clause[1])).find()
            && all.stream().noneMatch(x -> x.count() && x.within(clause))) sources.add(Source.SESSION);
        if (!f.countSubject.isEmpty() && sources.contains(Source.PLAN)) { f.sources = Set.of(Source.PLAN); f.all = true; return; } // "the plan included 1 camera body"
        if (f.count() && sources.contains(Source.PLAN)) {
            sources.remove(Source.PLAN);
            if (sources.isEmpty() || sources.equals(Set.of(Source.SESSION))) { f.sources = Set.of(Source.SESSION, Source.PLAN); f.all = false; return; }
        }
        f.sources = sources; f.all = true;
    }
    /** The clause after this one in its sentence, or null. */
    private static int[] next(String scan, int[] clause, int[] sentence) {
        for (int k = clause[1]; k < sentence[1]; k++) { int[] c = clause(scan, k); if (c[0] >= clause[1] && k >= c[0] && k < c[1]) return c; }
        return null;
    }
    /** The clause before this one in its sentence, or null. */
    private static int[] previous(String scan, int[] clause, int[] sentence) {
        for (int k = clause[0] - 1; k >= sentence[0]; k--) { int[] c = clause(scan, k); if (c[1] <= clause[0] && k >= c[0] && k < c[1]) return c; }
        return null;
    }

    /**
     * Statistic words a figure is claimed under: its clause's, those of figure-less clauses listed with it ("The mean and
     * median ISO were both 800", "The most common aperture and shutter speed were f/4 and ..."), otherwise the label before a
     * colon; with "respectively" the words before the first figure apply to every value. Shutter "speed" turns highest and
     * lowest around.
     */
    private static Set<String> aggregations(String scan, Figure figure, List<Figure> all) {
        int[] clause = clause(scan, figure.start), sentence = sentence(scan, figure.start);
        String respective = MEASURES.contains(figure.metric) ? respectiveLabel(scan, figure, sentence, all) : null;
        if (respective != null) return adjusted(figure, words(respective), respective, ""); // "The highest ISO and the slowest shutter speed were ISO 800 and 1/1000 s, respectively"
        var text = new StringBuilder(scan.substring(clause[0], clause[1]));
        for (int[] c = clause, p; (p = previous(scan, c, sentence)) != null; c = p) {
            final int[] mate = p;
            if (!LIST_JOIN.matcher(scan.substring(p[1], c[0])).matches() || all.stream().anyMatch(x -> x.within(mate))) break;
            text.append(' ').append(scan, p[0], p[1]);
        }
        text.append(' ').append(appositive(scan, clause, sentence, all)); // "f/4, the aperture you used most, ..."
        var found = words(text.toString());
        String label = label(scan, clause);
        if (found.isEmpty()) found = words(label);
        return adjusted(figure, found, text.toString(), label);
    }
    /** Shutter "speed" turns highest and lowest around; the "smallest" aperture is the largest f-number. */
    private static Set<String> adjusted(Figure figure, Set<String> found, String text, String label) {
        if (figure.metric.equals(SHUTTER) && (SPEED.matcher(text).find() || SPEED.matcher(label).find())) {
            boolean high = HIGH.matcher(text).find(), low = LOW.matcher(text).find();
            if (high) { found.remove("max"); found.add("min"); }
            if (low) { found.remove("min"); found.add("max"); }
        }
        if (figure.metric.equals(APERTURE)) {
            if (SMALLEST.matcher(text).find()) { found.remove("min"); found.add("max"); }
            if (LARGEST.matcher(text).find()) { found.remove("max"); found.add("min"); }
        }
        return found;
    }
    /**
     * With "respectively", the label of the figure's place in the list ("the highest ISO", "the slowest shutter speed"), or
     * the whole list when the places do not line up or its own label has no statistic word; null without "respectively".
     */
    private static String respectiveLabel(String scan, Figure figure, int[] sentence, List<Figure> all) {
        var said = RESPECTIVELY.matcher(scan).region(sentence[0], sentence[1]);
        if (!said.find()) return null;
        var values = all.stream().filter(f -> MEASURES.contains(f.metric) && f.partner == null && f.within(sentence)).sorted(Comparator.comparingInt(f -> f.start)).toList();
        int index = values.indexOf(figure);
        if (index < 0 || values.size() < 2) return null;
        String lead = scan.substring(sentence[0], values.getFirst().start), tail = scan.substring(values.getLast().end, Math.max(values.getLast().end, said.start()));
        String after = scan.substring(Math.min(said.end(), sentence[1]), sentence[1]); // "f/4 和 1/1000 秒分别是最常用的光圈和快门速度"
        String list = !words(lead).isEmpty() ? lead : !words(tail).isEmpty() ? tail : !words(after).isEmpty() ? after : lead;
        var places = Arrays.stream(list.split("(?iu)\\s*(?:,|\\band\\b|\\bor\\b|&|、|和|及|与)\\s*")).filter(s -> !s.isBlank()).toList();
        if (places.size() != values.size()) return list;
        String place = places.get(index);
        return words(place).isEmpty() ? list : place;
    }
    /** The figure-less clause right after this one when it describes the figure ("f/4, the aperture you used most, ..."), else "". */
    private static String appositive(String scan, int[] clause, int[] sentence, List<Figure> all) {
        int[] next = next(scan, clause, sentence);
        if (next == null || scan.substring(clause[1], next[0]).contains(";") || all.stream().anyMatch(x -> x.within(next))) return "";
        String text = scan.substring(next[0], next[1]); // "f/4, the aperture you used most", "f/1.8, where you shot most", "f/1.8，本次大部分照片都用了这个光圈"
        return APPOSITIVE.matcher(text).lookingAt() || has(BACK_REFERENCE, text) ? text : "";
    }
    private static Set<String> words(String text) {
        var found = new HashSet<String>();
        AGGREGATIONS.forEach((name, pattern) -> { if (pattern.matcher(text).find()) found.add(name); });
        if (found.contains("mode")) found.remove("frequent"); // "most often" is the most frequent value, not one of several frequent ones
        if (FREQ_HIGH.matcher(text).find()) found.remove("max"); // "使用频率最高": the highest frequency, not the highest value
        return found;
    }

    private static boolean supported(Figure figure, String scan, Evidence evidence, boolean advice, List<Figure> all, List<int[]> items, List<String> itemNames, List<Marker> markers) {
        int[] clause = clause(scan, figure.start), sentence = sentence(scan, figure.start);
        String own = scan.substring(clause[0], clause[1]), before = scan.substring(clause[0], Math.max(clause[0], figure.start));
        var words = aggregations(scan, figure, all);
        String appositive = appositive(scan, clause, sentence, all);
        boolean echoed = markers.stream().anyMatch(m -> m.echo() && m.start() >= sentence[0] && m.start() < sentence[1] && m.kind() != Kind.CURRENT && m.kind() != Kind.ACTUAL);
        if (advice && figure.cited.isEmpty() && !figure.count() && words.isEmpty()) {
            String since = scan.substring(sentence[0], Math.max(sentence[0], figure.start)), described = own + " " + appositive;
            String tail = scan.substring(Math.min(figure.end, clause[1]), clause[1]); // "ISO 800, which is what the plan called for"
            int suggest = last(SUGGEST, since);
            int planned = -1; // "consider updating the plan to ISO 800" proposes a plan; it does not report one
            for (Matcher p = PLAN.matcher(since); p.find(); ) if (!PLAN_CHANGE.matcher(since.substring(0, p.start())).find()) planned = p.start();
            int factual = List.of(last(HISTORY, since), last(CURRENT, since), last(ACTUAL, since), planned, last(PAST, since)).stream().mapToInt(Integer::intValue).max().orElse(-1);
            // "the 1/60 s you relied on", "your indoor 1/60 s", "for 1/60 s frames", "ISO 1600, the planned setting", "rather than shooting
            // everything at 1/500 s" state facts; "a few f/8 frames" proposes.
            boolean stated = has(ACTUAL, described) || has(PAST, described) || has(HISTORY, described) || has(CURRENT, described) || has(PLAN, appositive) || has(CAUSAL, before)
                || RELATIVE_TAIL.matcher(tail).find() && (has(PLAN, tail) || has(HISTORY, tail) || has(ACTUAL, tail) || has(PAST, tail) || has(CURRENT, tail))
                || INSTEAD_BEFORE.matcher(scan.substring(Math.max(0, clause[0] - 16), clause[0])).find()
                || DEFINITE_BEFORE.matcher(before).find()
                || PHOTO_AFTER.matcher(scan).region(figure.end, scan.length()).lookingAt() && !INDEFINITE_BEFORE.matcher(before).find();
            if (suggest > factual && !stated) {
                if (!echoed) return true;
                figure.sources = figure.sources.stream().filter(s -> s != Source.SESSION).collect(java.util.stream.Collectors.toSet()); // "keep ISO 800 as planned" states the plan
            }
        }
        if (has(REMAINDER, own)) return false; // "the rest at 85 mm" implies a count the evidence does not hold
        if (MEASURES.contains(figure.metric) && !figure.sources.equals(Set.of(Source.PLAN)) && (has(NEGATION, own) || has(RANK, own))) return false; // what was not, or second-most, used cannot be shown
        String prefix = scan.substring(Math.max(0, figure.start - 24), figure.start);
        if (QUALIFIER_BEFORE.matcher(prefix).find() && !(MEASURES.contains(figure.metric) && BOUND_BEFORE.matcher(prefix).find())) return false; // "more than 5", "about 70%"
        if (figure.count() && AFTER_QUALIFIER.matcher(scan).region(figure.end, scan.length()).lookingAt()) return false; // "4 张以上"
        if (!words.isEmpty() && figure.sources.contains(Source.PLAN) && !has(PLAN, own)) { // a statistic is of recorded photos ("The median ISO was 400" after a plan sentence)
            var recorded = new LinkedHashSet<>(figure.sources); recorded.remove(Source.PLAN); recorded.add(Source.SESSION); figure.sources = recorded;
        }
        var facts = evidence.facts();
        if (figure.all) {
            for (Source source : figure.sources) if (!holds(figure, source, words, scan, all, items, itemNames, facts)) return false;
            if (figure.partner != null && figure.sources.contains(Source.SESSION) && MEASURES.contains(figure.metric) && words.isEmpty() && !range(figure, facts)) return false;
            // "all photos at f/2.8", "Across all photos, the aperture stayed at f/2.8", "the widest aperture of f/2.8 on all photos"
            String lead = scan.substring(sentence[0], clause[0]);
            boolean everyPhoto = has(UNIVERSAL, own) || ONLY_BEFORE.matcher(before).find() || has(UNIVERSAL, lead) && all.stream().noneMatch(f -> f.start >= sentence[0] && f.start < clause[0]);
            if (MEASURES.contains(figure.metric) && figure.partner == null && everyPhoto && Collections.disjoint(words, Set.of("mean", "median", "mode", "frequent"))) {
                // "All 7 photos were shot at either f/2.8 or f/4": the values together cover every photo
                var alternatives = all.stream().filter(f -> f.metric.equals(figure.metric) && f.partner == null && f.within(clause)).toList();
                boolean together = alternatives.size() > 1 && has(EITHER, own);
                for (Source source : figure.sources)
                    if (source != Source.PLAN && !(together ? covers(alternatives, source, facts) : sole(figure, source, facts))) return false;
            }
            return !figure.sources.isEmpty();
        }
        return figure.sources.stream().anyMatch(source -> holds(figure, source, words, scan, all, items, itemNames, facts));
    }
    /** Whether a fact of the source supports the figure; a figure claimed under several statistics ("mean and median") needs each of them. */
    private static boolean holds(Figure figure, Source source, Set<String> words, String scan, List<Figure> all, List<int[]> items, List<String> itemNames, List<Fact> facts) {
        if (MEASURES.contains(figure.metric) && source != Source.PLAN && words.size() > 1)
            return words.stream().allMatch(w -> facts.stream().anyMatch(fact -> fact.source() == source && matches(figure, fact, scan, all, items, itemNames, facts, Set.of(w))));
        return facts.stream().anyMatch(fact -> fact.source() == source && matches(figure, fact, scan, all, items, itemNames, facts, words));
    }
    /** Whether every photo with the field recorded this value (this session), or every usable photo of an earlier session did. */
    private static boolean sole(Figure figure, Source source, List<Fact> facts) {
        if (source == Source.SESSION) {
            var total = facts.stream().filter(f -> f.source() == source && f.metric().equals(COUNT) && f.aggregation().equals("withData") && f.subject().equals("field:" + figure.metric)).findFirst();
            return total.isPresent() && facts.stream().anyMatch(f -> f.source() == source && f.metric().equals(COUNT) && f.aggregation().equals("photos") && of(figure, f.subject()) && f.value() == total.get().value());
        }
        return facts.stream().anyMatch(c -> c.source() == source && c.metric().equals(COUNT) && c.aggregation().equals("photos") && of(figure, c.subject())
            && facts.stream().anyMatch(u -> u.source() == source && u.id().equals(c.id()) && u.aggregation().equals("usable") && u.value() == c.value()));
    }

    /** Whether the values together were recorded on every photo with the field (this session only; otherwise unsupported). */
    private static boolean covers(List<Figure> values, Source source, List<Fact> facts) {
        if (source != Source.SESSION) return false;
        var total = facts.stream().filter(f -> f.source() == source && f.metric().equals(COUNT) && f.aggregation().equals("withData") && f.subject().equals("field:" + values.getFirst().metric)).findFirst();
        double sum = 0;
        for (var value : values) {
            var count = facts.stream().filter(f -> f.source() == source && f.metric().equals(COUNT) && f.aggregation().equals("photos") && of(value, f.subject())).findFirst();
            if (count.isEmpty()) return false;
            sum += count.get().value();
        }
        return total.isPresent() && sum == total.get().value();
    }
    /** A stated recorded range is the recorded minimum and maximum, in either order. */
    private static boolean range(Figure figure, List<Fact> facts) {
        var other = figure.partner;
        boolean ok = false;
        for (var min : facts) for (var max : facts) {
            if (min.source() != Source.SESSION || max.source() != Source.SESSION || !min.metric().equals(figure.metric) || !max.metric().equals(figure.metric)
                || !min.aggregation().equals("min") || !max.aggregation().equals("max")) continue;
            ok |= sameValue(figure, figure.metric, min.value()) && sameValue(other, other.metric, max.value())
                || sameValue(figure, figure.metric, max.value()) && sameValue(other, other.metric, min.value());
        }
        return ok;
    }

    private static boolean matches(Figure figure, Fact fact, String scan, List<Figure> all, List<int[]> items, List<String> itemNames, List<Fact> facts) {
        return matches(figure, fact, scan, all, items, itemNames, facts, aggregations(scan, figure, all));
    }
    private static boolean matches(Figure figure, Fact fact, String scan, List<Figure> all, List<int[]> items, List<String> itemNames, List<Fact> facts, Set<String> words) {
        if (figure.metric.equals(UNTYPED) ? !fact.metric().equals(COUNT) : !figure.metric.equals(fact.metric())) return false;
        int[] clause = clause(scan, figure.start), sentence = sentence(scan, figure.start);
        if (MEASURES.contains(fact.metric()) && fact.source() != Source.PLAN && !aggregation(figure, fact, words)) return false;
        String said = scan.substring(sentence[0], sentence[1]);
        if (fact.metric().equals(DATE) && fact.source() == Source.HISTORY && fact.aggregation().equals("savedOn") != has(SAVED, said)) return false;
        if (fact.metric().equals(COUNT) || fact.metric().equals(PERCENT)) {
            String own = scan.substring(clause[0], clause[1]);
            var together = listed(scan, figure, clause, sentence, all).stream().filter(m -> m.partner == null && m.sources.contains(fact.source())).toList();
            if (!figure.afterOf && together.size() > 1 && together.stream().map(m -> m.metric).distinct().count() == 1 && !has(EACH, own) && !has(RESPECTIVELY, said))
                // "50mm 和 85mm 共拍摄了 6 张": the count of those values together
                return fact.metric().equals(COUNT) && figure.decimals == 0 && together.stream().anyMatch(m -> of(m, fact.subject())) && sum(together, fact, facts) == figure.value;
            if (!subject(figure, fact, scan, clause, sentence, all, items, itemNames, facts) || !echoedFields(said, fact, facts)) return false;
        }
        // f-numbers are discrete stops: f/2 is not f/1.8 and f/3 is not f/2.8 (only a mean may be rounded).
        if (fact.metric().equals(APERTURE) && !fact.aggregation().equals("mean") && Math.abs(figure.value - fact.value()) > 1e-9) return false;
        return sameValue(figure, fact.metric(), fact.value());
    }
    /** The measured values of a count's clause and of bare values listed before it ("The 50 mm and 85 mm focal lengths were ..."). */
    private static List<Figure> listed(String scan, Figure figure, int[] clause, int[] sentence, List<Figure> all) {
        var values = new ArrayList<>(all.stream().filter(m -> m != figure && MEASURES.contains(m.metric) && m.within(clause)).toList());
        for (int[] c = clause, p; (p = previous(scan, c, sentence)) != null; c = p) {
            final int[] mate = p;
            var inside = all.stream().filter(m -> m.within(mate)).toList();
            var rest = new StringBuilder(scan.substring(p[0], p[1]));
            for (var m : inside) for (int i = Math.max(m.start, p[0]); i < Math.min(m.end, p[1]); i++) rest.setCharAt(i - p[0], ' ');
            if (!LIST_JOIN.matcher(scan.substring(p[1], c[0])).matches() || inside.isEmpty() || inside.stream().anyMatch(m -> !MEASURES.contains(m.metric))
                || !BARE.matcher(rest).matches()) break;
            values.addAll(inside);
        }
        return values;
    }
    private static double sum(List<Figure> values, Fact like, List<Fact> facts) {
        double total = 0;
        for (var value : values) {
            var count = facts.stream().filter(f -> f.source() == like.source() && f.metric().equals(COUNT) && f.aggregation().equals(like.aggregation()) && of(value, f.subject())).findFirst();
            if (count.isEmpty()) return Double.NaN;
            total += count.get().value();
        }
        return total;
    }
    /** "As with the lens model, exposure compensation is missing for 7 of 7 photos": the named field must have the same count. */
    private static boolean echoedFields(String said, Fact fact, List<Fact> facts) {
        if (!fact.subject().startsWith("field:")) return true;
        for (boolean likened : List.of(true, false)) {
            var m = (likened ? ECHO_FIELD : CONTRAST_FIELD).matcher(said);
            while (m.find()) {
                String w = m.group(1).toLowerCase(Locale.ROOT);
                String field = "field:" + (w.startsWith("lens") ? "lens" : w.startsWith("camera") || w.startsWith("bod") ? "camera" : w.startsWith("focal") ? FOCAL
                    : w.startsWith("shutter") ? SHUTTER : w.startsWith("aperture") ? APERTURE : w.equals("iso") ? ISO : w.startsWith("exposure") ? EV : "captureTime");
                if (fact.subject().equals(field)) continue;
                var other = facts.stream().filter(f -> f.source() == fact.source() && f.metric().equals(fact.metric()) && f.aggregation().equals(fact.aggregation()) && f.subject().equals(field)).toList();
                // likened: the named field has the same count; contrasted: it has a known, different one ("Unlike the capture time, ...")
                if (likened ? other.stream().noneMatch(f -> f.value() == fact.value()) : other.isEmpty() || other.stream().anyMatch(f -> f.value() == fact.value())) return false;
            }
        }
        return true;
    }
    /** Without a statistic word a figure is a recorded value (never a mean or median); earlier sessions saved only their top values. */
    private static boolean aggregation(Figure figure, Fact fact, Set<String> words) {
        String a = fact.aggregation();
        if (words.isEmpty()) return figure.partner != null && fact.source() == Source.SESSION ? Set.of("min", "max").contains(a) : Set.of("value", "mode", "min", "max", "top").contains(a);
        return words.contains(a) || words.contains("frequent") && (a.equals("mode") || a.equals("top"));
    }
    /**
     * A count or share must be of what its sentence names: a denominator is a total; a stated count kind ("missing",
     * "imported", "3 different apertures") must be that kind; in a sentence about the plan, photos "within", "below",
     * "above" or "outside" it are that place's count; otherwise the value it is of (its clause's measured value; two
     * settings at once are never in the evidence), the equipment its clause names, the nearest measured value in its
     * sentence, the equipment its sentence names, or the field named. A count of nothing named is a total.
     */
    private static boolean subject(Figure figure, Fact fact, String scan, int[] clause, int[] sentence, List<Figure> all, List<int[]> items, List<String> itemNames, List<Fact> facts) {
        String subject = fact.subject(), own = scan.substring(clause[0], clause[1]), said = scan.substring(sentence[0], sentence[1]), aggregation = fact.aggregation();
        if (has(RESPECTIVELY, said)) return true; // the pairing is checked as a whole
        if (has(REMAINDER, own)) return false;
        if (has(COMPLETE, own)) // "All 4 photos had complete EXIF metadata": every field was recorded on that many photos
            return Set.of("imported", "usable").contains(aggregation) && FIELDS.keySet().stream()
                .allMatch(f -> facts.stream().anyMatch(c -> c.source() == fact.source() && c.metric().equals(COUNT) && c.subject().equals("field:" + f)
                    && Set.of("withData", "withTimestamp").contains(c.aggregation()) && c.value() == fact.value()));
        if (figure.afterOf) {
            if (!WITH_FIELD.matcher(own).find()) return Set.of("imported", "usable", "withData").contains(aggregation) && (subject.isEmpty() || subject.startsWith("field:"));
            // "Of the 7 photos with recorded capture times": the total of photos that recorded the field the clause names
            var named = FIELDS.entrySet().stream().filter(e -> e.getValue().matcher(own).find()).map(e -> "field:" + e.getKey()).toList();
            return Set.of("withData", "withTimestamp").contains(aggregation) && named.contains(subject);
        }
        if (figure.photos && Set.of("quantity", "distinct").contains(aggregation)) return false; // photos are neither planned units nor distinct values
        if (!figure.countSubject.isEmpty()) return subject.equals(figure.countSubject) && Set.of("distinct", "quantity").contains(aggregation);
        var roles = has(PLAN, said) ? ROLES.entrySet().stream().filter(e -> e.getValue().matcher(own).find()).map(Map.Entry::getKey).toList() : List.<String>of();
        if (!roles.isEmpty() || ROLES.containsKey(aggregation)) { // of the field its own clause names, otherwise one its sentence names
            var named = fields(own, clause, all);
            return roles.contains(aggregation) && (named.isEmpty() ? fields(said, sentence, all) : named).contains(subject);
        }
        var kinds = new ArrayList<>(COUNT_KINDS.entrySet().stream().filter(e -> e.getValue().matcher(own).find()).map(Map.Entry::getKey).toList());
        if (kinds.contains("missing") && figure.value == 0 && NO_DATA.matcher(own).find()) kinds.add("withData"); // "No exposure compensation data was recorded (0 of 7 photos)"
        if (fact.metric().equals(COUNT)) {
            if (kinds.contains("distinct") || aggregation.equals("distinct")) return false; // distinct counts must say what is distinct
            if (!kinds.isEmpty() && !kinds.contains(aggregation)) return false;
        }
        // The setting a leading clause names governs the count too ("At 50 mm, the lens recorded 7 photos"; "Of the photos taken at f/2.8, 71.4% used 50 mm").
        int[] lead = previous(scan, clause, sentence);
        var ownMeasures = all.stream().filter(m -> m != figure && MEASURES.contains(m.metric)
            && (m.within(clause) || lead != null && m.within(lead) && CONTEXT.matcher(scan).region(lead[0], lead[1]).lookingAt())).toList();
        var same = ownMeasures.stream().filter(m -> m.sources.contains(fact.source())).toList();
        if (!same.isEmpty()) {
            if (same.stream().map(m -> m.metric).distinct().count() > 1) return false; // "5 photos at 85 mm with f/2.8": a joint count
            var each = listed(scan, figure, clause, sentence, all).stream().filter(m -> m.sources.contains(fact.source())).toList(); // "The 50 mm and 85 mm focal lengths were each ..."
            if (has(EACH, own)) return each.stream().anyMatch(m -> of(m, subject)) && each.stream().allMatch(m -> facts.stream().anyMatch(c -> c.source() == fact.source()
                && c.metric().equals(fact.metric()) && c.aggregation().equals(aggregation) && of(m, c.subject()) && c.value() == fact.value()));
            return same.stream().anyMatch(m -> of(m, subject));
        }
        if (!ownMeasures.isEmpty()) // only figures of another source here: the photos with that field ("the planned ISO 400 ... 7 photos")
            return aggregation.equals("withData") && ownMeasures.stream().anyMatch(m -> subject.equals("field:" + m.metric));
        var inClause = names(items, itemNames, clause);
        if (!inClause.isEmpty()) return inClause.contains(subject);
        // Fields named by the clause, the clauses listed with it or its label ("Lens model and capture time are missing for 7 of 7 photos"):
        // each must have the count; with no count kind the count is of photos that recorded the field. A field of the clause itself
        // comes first; a label or listed field only after the sentence's values and equipment.
        var text = new StringBuilder(own).append(' ').append(label(scan, clause));
        for (int[] c = clause, p; (p = previous(scan, c, sentence)) != null; c = p) {
            if (!LIST_JOIN.matcher(scan.substring(p[1], c[0])).matches() || has(CONTRAST_FIELD, scan.substring(p[0], p[1]))) break; // "Unlike the capture time, ..." is no list mate
            text.append(' ').append(scan, p[0], p[1]);
        }
        var fields = FIELDS.entrySet().stream().filter(e -> e.getValue().matcher(text).find()).map(e -> "field:" + e.getKey()).toList();
        boolean fieldCount = fields.contains(subject) && (!kinds.isEmpty() || aggregation.equals("withData"))
            && fields.stream().allMatch(f -> facts.stream().anyMatch(c -> c.source() == fact.source() && c.metric().equals(fact.metric())
                && c.aggregation().equals(aggregation) && c.subject().equals(f) && c.value() == fact.value()));
        if (FIELDS.values().stream().anyMatch(p -> p.matcher(own).find())) return fieldCount;
        var measured = all.stream().filter(m -> m != figure && MEASURES.contains(m.metric) && m.within(sentence)).toList();
        var governing = nearest(figure, measured.stream().filter(m -> m.sources.contains(fact.source())).toList()); // "ISO 800, higher than the planned ISO 400, was used on all 7 photos"
        if (governing == null) governing = nearest(figure, measured);
        if (governing != null) return of(governing, subject);
        var inSentence = names(items, itemNames, sentence);
        if (!inSentence.isEmpty()) return inSentence.contains(subject);
        if (!fields.isEmpty()) return fieldCount;
        if (!kinds.isEmpty()) return true;
        return Set.of("imported", "usable").contains(aggregation);
    }
    private static List<String> names(List<int[]> items, List<String> itemNames, int[] segment) {
        var named = new ArrayList<String>();
        for (int i = 0; i < items.size(); i++) if (items.get(i)[0] >= segment[0] && items.get(i)[0] < segment[1]) named.add(item(itemNames.get(i)));
        return named;
    }
    /** Fields a sentence names: those of its measured figures and its field words. */
    private static Set<String> fields(String said, int[] sentence, List<Figure> all) {
        var named = new HashSet<String>();
        all.stream().filter(m -> MEASURES.contains(m.metric) && m.within(sentence)).forEach(m -> named.add("field:" + m.metric));
        FIELDS.forEach((field, pattern) -> { if (pattern.matcher(said).find()) named.add("field:" + field); });
        return named;
    }
    /** Whether a count's subject is the value a measured figure states (a stated range stands for its whole field). */
    private static boolean of(Figure measure, String subject) {
        if (measure.partner != null) return subject.equals("field:" + measure.metric);
        return subject.startsWith("value:" + measure.metric + ":") && sameValue(measure, measure.metric, Double.parseDouble(subject.substring(subject.lastIndexOf(':') + 1)));
    }
    /** The nearest measured figure before the count in its sentence, otherwise the nearest after it. */
    private static Figure nearest(Figure figure, List<Figure> candidates) {
        Figure before = null, after = null;
        for (var m : candidates) {
            if (m.start < figure.start && (before == null || m.start > before.start)) before = m;
            if (m.start > figure.start && (after == null || m.start < after.start)) after = m;
        }
        return before != null ? before : after;
    }
    /** "50 mm and 85 mm on 5 and 1 photos respectively": the n-th count is of the n-th value (values without counts are checked one by one). */
    private static boolean respectively(String scan, List<Figure> figures, Evidence evidence) {
        Matcher m = RESPECTIVELY.matcher(scan);
        while (m.find()) {
            int[] sentence = sentence(scan, m.start());
            var values = figures.stream().filter(f -> MEASURES.contains(f.metric) && f.partner == null && f.within(sentence)).toList();
            var counts = figures.stream().filter(f -> f.count() && !f.afterOf && f.within(sentence)).toList();
            if (counts.isEmpty() && values.size() >= 2) continue;
            if (values.size() != counts.size() || values.size() < 2) return false;
            for (int i = 0; i < values.size(); i++) {
                var v = values.get(i); var c = counts.get(i);
                if (evidence.facts().stream().noneMatch(f -> v.sources.contains(f.source()) && f.metric().equals(c.metric.equals(PERCENT) ? PERCENT : COUNT)
                    && of(v, f.subject()) && sameValue(c, f.metric(), f.value()))) return false;
            }
        }
        return true;
    }
    /** The statement's own rounding of the exact fact value; fractions of a second by their reciprocal; counts exactly. */
    private static boolean sameValue(Figure figure, String metric, double fact) {
        return switch (metric) {
            case COUNT, REVISION, DATE -> figure.decimals == 0 && figure.value == fact;
            case TIME -> figure.decimals == 0 ? Math.floor(fact / 60) * 60 == figure.value : figure.value == fact;
            case SHUTTER -> figure.denominator > 0
                ? figure.numerator == 1 ? fact < 1 && Math.abs(1 / fact - figure.denominator) <= 1e-3 * figure.denominator
                    : Math.abs(fact - figure.value) <= 1e-6 * Math.max(1, fact)
                : rounded(fact, figure.decimals) == figure.value;
            default -> figure.decimals >= 0 && rounded(fact, figure.decimals) == figure.value;
        };
    }
    private static double rounded(double value, int decimals) { return BigDecimal.valueOf(value).setScale(Math.max(0, decimals), RoundingMode.HALF_UP).doubleValue(); }

    // ---------------------------------------------------------------- masking

    private static boolean same(String statement, String line) {
        return trim(statement).equalsIgnoreCase(trim(Normalizer.normalize(line, Normalizer.Form.NFKC)));
    }
    private static String trim(String text) { return text.strip().replaceAll("[.。\\s]+$", ""); }
    /** Names (as written, then without the brand when what remains is still distinctive), longest first, each with its full name. */
    private static List<Map.Entry<String, String>> expand(List<String> names) {
        var all = new LinkedHashMap<String, String>();
        for (String name : names) {
            String normal = Normalizer.normalize(name, Normalizer.Form.NFKC).strip(); all.putIfAbsent(normal, name);
            int space = normal.indexOf(' ');
            if (space > 0 && normal.length() - space - 1 >= 4) all.putIfAbsent(normal.substring(space + 1), name);
        }
        return all.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()).toList();
    }
    /** Blanks a phrase (positions unchanged); a masked deterministic line keeps its sentence end so nothing reads across it. */
    private static void mask(String text, StringBuilder masked, String phrase, List<int[]> spans, List<String> labels, String label, boolean sentenceEnd) {
        if (phrase.isBlank()) return;
        String normal = Normalizer.normalize(phrase, Normalizer.Form.NFKC);
        var m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normal) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        while (m.find()) {
            if (masked.substring(m.start(), m.end()).isBlank()) continue;
            if (sentenceEnd) { // "In the earlier session, ISO: ...", "As with the lens model, <line>" claim more than the line says
                String lead = text.substring(sentence(text, m.start())[0], m.start());
                if (has(HISTORY, lead) || has(ECHO_FIELD, lead) || has(LEAD_CLAIM, lead) || has(BOTH, lead) || FIELDS.values().stream().anyMatch(f -> has(f, lead))) continue;
            }
            for (int i = m.start(); i < m.end(); i++) masked.setCharAt(i, ' ');
            if (sentenceEnd) masked.setCharAt(m.end() - 1, '.');
            if (spans != null) { spans.add(new int[]{m.start(), m.end()}); labels.add(label); }
        }
    }
}
