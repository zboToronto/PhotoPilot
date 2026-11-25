package com.photopilot.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.ShootRequirement;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.CliTerminalAccess;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guided Discovery CLI wizard ("discover wizard") through the real command loop, on the same Facade, DiscoveryForm,
 * DiscoveryGuide and DiscoveryPresentation as the GUI: the three stages, validation by input with a chance to correct,
 * navigation that keeps every value, structured results with AI text and PhotoPilot's checks apart, the numbered
 * actions, saved ideas without any AI request, Chinese, narrow terminals, optional colour, and scripts that are never
 * prompted. Answers come from the same Scanner as the commands, exactly as typed in an interactive terminal.
 * Transcripts are written only when PHOTOPILOT_TRANSCRIPTS names a folder (system property or environment variable).
 */
class DiscoveryWizardCliTest {
    @TempDir Path root;

    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    ApplicationBootstrap app(FakeGemini gemini, FakeEnvironmentProviders providers, String name) {
        return new ApplicationBootstrap(providers.environment(gemini.environment(root.resolve(name))));
    }
    static void gear(ApplicationBootstrap app) { for (var item : new GearFixtures.Airshow().items()) ok(app.facade().inventory().add(item, true)); }
    /** An embedded CLI whose command loop (and so the wizard) reads {@code lines}. */
    static PhotoPilotCLI cli(ApplicationBootstrap app, ByteArrayOutputStream bytes, int width, boolean colour, boolean echo, List<String> lines) {
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(String.join("\n", lines)),
            new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        CliTerminalAccess.configure(cli, width, colour, echo);
        return cli;
    }
    static List<String> lines(Object... parts) {
        var all = new ArrayList<String>();
        for (var part : parts) if (part instanceof List<?> list) list.forEach(item -> all.add((String) item)); else all.add((String) part);
        return all;
    }
    /** Stage 1 on its first pass (no lookup), the exact fixture coordinates under Advanced, then Next. */
    static List<String> stageOne(LocalDate day, String place) {
        var form = DiscoveryFixtures.form(day);
        return List.of(place, "n", day.toString(), form.get("from"), form.get("until"), "40", "5", DiscoveryFixtures.LAT, DiscoveryFixtures.LON, "", "N");
    }
    /** Stage 2: categories 1 and 3, custom "documentary", avoid portraits, one bag, no notes, gear list, history yes, Discover. */
    static List<String> stageTwo() { return List.of("1 3", "documentary", "portraits", "one bag", "", "y", "y", "D"); }
    static List<String> happy(LocalDate day) {
        return lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "3", "1", "N", "5", "1", "0", "0", "X");
    }
    /** Whitespace-insensitive: wrapped sentences still match (line breaks and indentation count as one space). */
    static void contains(String output, String... parts) {
        String flat = flat(output);
        for (String part : parts) assertTrue(flat.contains(flat(part)), () -> "missing \"" + part + "\" in:\n" + output);
    }
    static String flat(String text) { return text.replaceAll("\\s+", " "); }
    /** The output from the first {@code start} to the next {@code end} after it. */
    static String between(String output, String start, String end) {
        int from = output.indexOf(start), to = output.indexOf(end, from + 1);
        assertTrue(from >= 0 && to > from, () -> "no \"" + start + "\" ... \"" + end + "\" in:\n" + output);
        return output.substring(from, to);
    }
    static int count(String output, String part) { return flat(output).split(java.util.regex.Pattern.quote(flat(part)), -1).length - 1; }
    static String args(LocalDate day) {
        return DiscoveryFixtures.form(day).entrySet().stream().filter(e -> !e.getValue().isEmpty())
            .map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(Collectors.joining(" "));
    }
    /** The wizard's own output: from its heading to the line that says it was left. */
    static List<String> wizardLines(String output, String heading, String left) {
        int start = output.indexOf(heading), end = output.indexOf(left, start);
        assertTrue(start >= 0 && end > start, output);
        return output.substring(start, output.indexOf('\n', end) < 0 ? output.length() : output.indexOf('\n', end)).lines().toList();
    }
    /**
     * No line wider than the terminal. Captured output has no typed input, so the text after a "> " prompt starts on the
     * prompt's line; in a terminal the answer and its Enter come first, so the prompt is not counted. The only exemption
     * is one unbreakable ASCII token (no space and no East Asian wide character, which the wizard may break between).
     */
    static void fits(List<String> lines, int width) {
        for (String captured : lines) {
            String line = captured.startsWith("> ") ? captured.substring(2) : captured;
            String body = line.strip();
            boolean token = !body.contains(" ") && body.codePoints().allMatch(cp -> CliTerminalAccess.columns(new String(Character.toChars(cp))) == 1);
            assertTrue(CliTerminalAccess.columns(line) <= width || token, () -> "wider than " + width + ": [" + line + "]");
        }
    }
    static void transcript(String name, String title, String output) throws Exception {
        String folder = System.getProperty("PHOTOPILOT_TRANSCRIPTS", System.getenv("PHOTOPILOT_TRANSCRIPTS"));
        if (folder == null || folder.isBlank()) return;
        Files.createDirectories(Path.of(folder));
        Files.writeString(Path.of(folder, name), title + System.lineSeparator() + System.lineSeparator() + output, StandardCharsets.UTF_8);
    }
    /** Without any whitespace: wrapped text (also text broken inside a long token or between Chinese characters) still matches. */
    static String squeezed(String text) { return text.replaceAll("\\s+", ""); }
    static void squeezedContains(String output, String... parts) {
        for (String part : parts) assertTrue(squeezed(output).contains(squeezed(part)), () -> "missing \"" + part + "\" in:\n" + output);
    }
    /**
     * The default fake forecast with some hourly series replaced by a constant and others left out, as a provider that
     * forecasts storms or does not supply a field would answer.
     */
    static Function<Map<String, String>, FakeEnvironmentProviders.Reply> forecast(Map<String, Double> constant, Set<String> missing) {
        return q -> {
            var body = JsonParser.parseString(FakeEnvironmentProviders.defaultForecast(q).body()).getAsJsonObject();
            var hourly = body.getAsJsonObject("hourly");
            if (hourly != null) {
                constant.forEach((name, value) -> {
                    if (!hourly.has(name)) return;
                    var series = new JsonArray();
                    for (int i = 0; i < hourly.getAsJsonArray(name).size(); i++) series.add(value);
                    hourly.add(name, series);
                });
                missing.forEach(hourly::remove);
            }
            return new FakeEnvironmentProviders.Reply(200, body.toString());
        };
    }
    /** Thunderstorms (weather code 95), 90% precipitation probability and 85 km/h gusts in every forecast hour. */
    static final Map<String, Double> STORM = Map.of("weather_code", 95.0, "precipitation_probability", 90.0, "wind_gusts_10m", 85.0);
    static int indent(String line) { return line.length() - line.stripLeading().length(); }
    /** The listing block of idea {@code number}: from its "n. subject" heading to the blank line that ends it. */
    static List<String> listed(String output, int number, String subject) {
        var lines = output.lines().toList();
        String start = number + ". " + subject.split(" ")[0];
        int from = -1;
        for (int i = 0; i < lines.size() && from < 0; i++) if (lines.get(i).startsWith(start)) from = i;
        assertTrue(from >= 0, () -> "no listed idea \"" + start + "\" in:\n" + output);
        int to = from + 1;
        while (to < lines.size() && !lines.get(to).isBlank()) to++;
        return lines.subList(from, to);
    }
    /** The lines printed under the row of {@code block} that starts with {@code row}, up to the next row at its indentation. */
    static String under(List<String> block, String row) {
        int at = -1;
        for (int i = 0; i < block.size() && at < 0; i++) if (block.get(i).strip().startsWith(row)) at = i;
        assertTrue(at >= 0, () -> "no row \"" + row + "\" in:\n" + String.join("\n", block));
        var below = new StringBuilder();
        for (int i = at + 1; i < block.size() && indent(block.get(i)) > indent(block.get(at)); i++) below.append(block.get(i)).append('\n');
        return below.toString();
    }
    /** Lines from the wizard heading to the end of the output (a hand-off leaves the wizard without its "left" line). */
    static List<String> fromHeading(String output, String heading) {
        int start = output.indexOf(heading);
        assertTrue(start >= 0, output);
        return output.substring(start).lines().toList();
    }

    @Test void wizardWalksTheThreeStagesAndItsResultsStayUsableByTheDiscoverCommands() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "happy")) {
            gear(app);
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, happy(day));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(1, gemini.requests.size(), "one Gemini request for one Discover");
            contains(out, "What will you photograph today?", "Guided Discovery · Step 1 of 3: Location & Time",
                " [1 Location & Time] > 2 Photography Preferences > 3 Discover & Review", "Place or area (required)", "e.g. Toronto, Ontario",
                "[not set]", "Find matching places for this name? [y/N]", "Date (required)", "Available from (required)", "240 min available",
                "Distance is measured in a straight line, not by road", "Maximum travel distance (km, straight-line) (required)",
                "Downtown Toronto · 43.65320, -79.38320 · entered coordinates", "40 km straight-line", "Coordinates go only to the weather",
                "Guided Discovery · Step 2 of 3: Photography Preferences", " Location & Time (done) > [2 Photography Preferences] > 3 Discover & Review",
                "[ ] 1 Landscape", "[ ] 9 Events", "Other interests (optional)", "Avoid (optional)", "Your confirmed gear (read only)",
                "11 inventory record(s)", "Show the full equipment list? [y/N]", "Camera bodies · ", "Lenses · ",
                "Reference my previously saved photography plans? [Y/n]",
                "Up to 5 saved sessions are sent to Gemini as a hint (their subjects, saved ideas, dates and status, never notes or locations), and nothing in them is changed.",
                "Gemini proposes ideas; PhotoPilot checks each one.", "Landscape; Architecture; documentary",
                "Guided Discovery · Step 3 of 3: Discover & Review",
                " Location & Time (done) > Photography Preferences (done) > [3 Discover & Review]",
                "Discovering photography opportunities based on your preferences and equipment...",
                "Conditions for these results", "Feasible ideas", "Unverified ideas", "Not suitable for your constraints",
                "Checked by PhotoPilot", "Suggested by AI (Gemini)", "[OK] Feasible · ", "[??] Unverified · ", "[NO] Not feasible · ",
                "PhotoPilot checks: ", "AI notes (Gemini; advice, not facts)", "Whether you prefer quieter places.",
                "Created draft session", "was saved in", "Saved in session", "Saved ideas by session", "1 saved idea(s)", "Left Guided Discovery");
            // One term for the notes input: the prompt, the review row, the menu and the problem label all say "restrictions".
            contains(out, "Other restrictions (optional)", "Avoid, portability, restrictions");
            assertTrue(out.lines().anyMatch(line -> line.matches("\\s+Other restrictions\\s+not set")), "review row: " + out);
            assertFalse(out.contains("constraints (optional)") || out.contains("portability, constraints"), out);
            var latest = app.facade().discovery().latest().orElseThrow();
            for (var idea : latest.ideas()) contains(out, idea.subject());
            // AI proposal first, then PhotoPilot's checks; PhotoPilot's own notes about an idea stay under its checks.
            String murals = flat(between(out, "3. Hidden laneway murals", "Not suitable for your constraints"));
            int ai = murals.indexOf("Suggested by AI (Gemini)"), checked = murals.indexOf("Checked by PhotoPilot");
            assertTrue(ai > 0 && ai < checked && checked < murals.indexOf("1 AI statement(s) about this idea were removed because they quoted")
                && checked < murals.indexOf("PhotoPilot cannot verify") && murals.indexOf("Limitations noted by AI:") < checked, murals);
            assertTrue(murals.indexOf("Straight-line distance") < 0 || murals.indexOf("Straight-line distance") > checked, "the computed distance is a check result");
            String street = flat(between(out, "4. Small-town main street", "5. Night skyline"));
            assertTrue(street.indexOf("Checked by PhotoPilot") < street.indexOf("PhotoPilot cannot verify"), "a not-feasible idea keeps its notes visible: " + street);
            String conditions = flat(between(out, "Conditions for these results", "Results"));
            contains(conditions, "Interests Landscape; Architecture; documentary");
            // Preset chips supplement the custom interest and reach the request exactly; the other inputs too.
            assertEquals(List.of("landscape", "architecture", "documentary"), latest.context().preferences());
            assertEquals(List.of("portraits"), latest.context().avoid());
            assertEquals("one bag", latest.context().portability());
            assertEquals(40, latest.context().maxDistanceKm());
            var session = ok(app.facade().sessions().search("")).getFirst();
            assertEquals(List.of(latest.ideas().getFirst().subject()), session.savedIdeas().stream().map(i -> i.subject()).toList());
            contains(out, latest.ideas().getFirst().subject() + " (" + (latest.ideas().getFirst().feasibility().name().equals("FEASIBLE") ? "Feasible" : "Unverified") + " when suggested on");

            // The wizard's results are the commands' results: same numbers, and "again" reuses its inputs.
            bytes.reset();
            assertEquals(0, cli.execute("discover show 1"));
            assertTrue(text(bytes).startsWith("Idea 1 - ") && text(bytes).contains(latest.ideas().getFirst().subject()), text(bytes));
            assertEquals(0, cli.execute("discover list"));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            assertEquals(0, cli.execute("discover again"));
            assertEquals(2, gemini.requests.size());
            assertEquals(List.of("landscape", "architecture", "documentary"), app.facade().discovery().latest().orElseThrow().context().preferences());
            assertFalse(out.contains("\u001b"), "plain unless colour is enabled");
        }
    }

    @Test void invalidInputsAreShownByInputAndAskedAgainWithoutAnyRequest() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "invalid")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto", "n", day.toString(), form.get("from"), form.get("until"),
                "far", "40",                      // distance corrected in place
                "1", "-", "\"\"", "next",          // a required input cannot be cleared; an empty one is reported, then skipped
                "N", "Downtown Toronto", "n",      // Next is refused, grouped by input, and the place is asked again
                "5", "43.6", "-", "", "-79.4", "", // one coordinate alone is a coordinates problem
                "4", "", "25:00", "", "13:00", "", // an impossible time is asked again
                "2",                               // lookup without a match: the name stays, manual entry is offered
                "1", "Toronto", "2", "9", "1",     // an ambiguous name lists every match; an invalid pick is refused
                "3",                               // device location: unsupported here, so consent is never asked
                "exit"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertTrue(gemini.requests.isEmpty(), "never a request while inputs are invalid");
            contains(out, "[!] Maximum travel: The maximum travel distance must be a number of km",
                "Place or area is required; it cannot be cleared.", "[!] Place or area: Enter a place or area",
                "[!] Check these inputs before continuing:", "  [!] Place or area: Enter a place or area",
                "[!] Coordinates: Enter both latitude and longitude, or neither.", "[!] Available time: ",
                "Downtown Toronto · 43.6, -79.4 · entered coordinates", "Left Guided Discovery");
            assertTrue(out.indexOf("[!] Maximum travel") < out.indexOf("Maximum travel distance (km, straight-line) (required)", out.indexOf("[!] Maximum travel")),
                "the distance is asked again right after its problem");
            assertFalse(out.contains("Step 2 of 3"), "never advanced with problems");
            contains(out, "You can keep the place name without coordinates", "3 matching places found", "1. Toronto, Ontario, Canada",
                "Choose a place number from 1 to 3.", "[OK] Selected Toronto, Ontario, Canada.", "Toronto, Ontario, Canada · ", " · place lookup",
                "3  Use device location (not available here)", "[!] Device location is not supported on this computer.");
            assertFalse(out.contains("Allow device location once?"), "no consent question when the device cannot provide a location");
            assertEquals(2, providers.count("/v1/search"), "one lookup per explicit request");
        }
    }

    @Test void navigationKeepsEveryValueExitResumesAndResetRestoresTheDefaults() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "navigation")) {
            var bytes = new ByteArrayOutputStream();
            var form = DiscoveryFixtures.form(day);
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto", "n", day.toString(), form.get("from"), form.get("until"), "40",
                "N", "1 3", "documentary", "\"back\"", "one bag", "", "", // empty inventory: no list question
                "B",                    // stage 2 -> stage 1 menu
                "N",                    // back to the stage 2 menu, values kept
                "X",                    // leave; inputs stay
                "discover wizard",      // resumes in stage 2
                "B", "1", "", "n",      // place prompt shows the kept value in brackets; Enter keeps it
                "R", "y",               // reset to the defaults
                "exit"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertTrue(gemini.requests.isEmpty());
            int back = out.indexOf("Guided Discovery · Step 1 of 3", out.indexOf("Step 2 of 3"));
            assertTrue(back > 0, out);
            String afterBack = out.substring(back);
            contains(afterBack, "Downtown Toronto · name only, no coordinates", day.toString(), form.get("from") + "–" + form.get("until"), "40 km straight-line");
            String secondStageTwo = afterBack.substring(afterBack.indexOf("Step 2 of 3"));
            contains(secondStageTwo, "Landscape; Architecture; documentary", "one bag");
            assertTrue(secondStageTwo.lines().anyMatch(line -> line.matches("\\s+Avoid\\s+back")), "a quoted navigation word is a value: " + secondStageTwo);
            String resumed = out.substring(out.indexOf("Continuing with your earlier wizard inputs"));
            assertTrue(resumed.indexOf("Step 2 of 3") < resumed.indexOf("Step 1 of 3"), "resumes where it was left");
            contains(resumed, "[Downtown Toronto]", "Inputs reset.");
            String reset = resumed.substring(resumed.indexOf("Inputs reset."));
            contains(reset, "Place or area (required)", "[not set]");
            contains(out, "Your inventory is empty.");
            assertEquals(2, out.split("Left Guided Discovery", -1).length - 1);
        }
    }

    @Test void geminiOutageKeepsTheInputsThenRetryResultsEditsAndAnUnconfirmedHandoff() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "outage")) {
            gear(app);
            gemini.status(503, "");
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), "", "", "", "", "", "n", "", "D",
                "T",                 // the outage is shown; try again explicitly
                "6", "2",            // edit conditions -> Photography Preferences
                "1", "4", "",        // interests changed to Aviation
                "V",                 // the shown results now say they are for the earlier conditions
                "3", "4",            // an infeasible idea cannot be saved
                "2", "1", "N"));     // continue idea 1 in a new draft session: unconfirmed F03 draft
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(2, gemini.requests.size(), "one failed request and one explicit retry");
            contains(out, "No ideas were generated and none were invented", "T  Try again", "E  Edit conditions",
                "Conditions for these results", "Aviation", "These results are for the conditions shown above, not your later changes.",
                "This idea conflicts with your constraints and cannot be continued or saved.", "Created draft session",
                "is open in Requirements", "unconfirmed F03 draft");
            assertTrue(out.indexOf("No ideas were generated") < out.indexOf("Conditions for these results"));
            var session = ok(app.facade().sessions().search("")).getFirst();
            assertTrue(ok(app.facade().sessions().open(session.id())).requirements().filter(ShootRequirement::confirmed).isEmpty(), "nothing confirmed");
            assertEquals(0, cli.execute("requirement draft"), "the F03 draft is open in the shared requirement state");
            assertEquals(List.of(), app.facade().discovery().latest().orElseThrow().context().preferences(), "the shown results keep their conditions");
        }
    }

    @Test void savedIdeasAreBrowsedWithoutAnyRequestAndASavedIdeaContinuesInRequirements() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "saved")) {
            gear(app);
            var first = new ByteArrayOutputStream();
            var commands = cli(app, first, 80, false, false, List.of());
            String args = DiscoveryFixtures.form(day).entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(Collectors.joining(" "));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            assertEquals(0, commands.execute("discover run " + args), text(first));
            assertEquals(0, commands.execute("discover save 1 new"), text(first));
            var session = ok(app.facade().sessions().search("")).getFirst();
            var saved = session.savedIdeas().getFirst();

            // A fresh CLI process state: no request, no results; saved ideas are read from the session record.
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "next", "6", "1", "C", "9", "1"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(1, gemini.requests.size(), "browsing saved ideas makes no AI request");
            contains(out, "6  Saved ideas", "Saved ideas by session", session.title(), "1 saved idea(s)", "Saved ideas in " + session.title(),
                "[OK] 1. " + saved.subject() + " (Feasible when suggested on", "Check access before going.",
                "C  Continue a saved idea in Shooting Requirements", "Choose a saved idea number from 1 to 1.",
                "is open in Requirements", "unconfirmed F03 draft", "Subject / genre: " + saved.subject());
            assertTrue(ok(app.facade().sessions().open(session.id())).requirements().filter(ShootRequirement::confirmed).isEmpty(), "nothing confirmed");
        }
    }

    @Test void chineseWizardUsesTheChineseStagesPromptsAndResults() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "zh")) {
            gear(app);
            app.localization().useLanguage("zh-CN");
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            // "1　3": a Chinese input method types an ideographic space between the numbers.
            var cli = cli(app, bytes, 80, false, false, lines("discover wizard", stageOne(day, "多伦多市中心"), "1　3", "纪实", "", "一个包", "", "y", "", "D", "退出"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertFalse(out.contains("请使用 1 到 9 的编号"), out);
            String conditions = between(out, "当前结果所用的条件", "PhotoPilot 说明");
            contains(conditions, "拍摄兴趣 风光; 建筑; 纪实");
            assertFalse(conditions.contains("landscape"), "preset interests are shown with their Chinese labels: " + conditions);
            // Chinese wraps between characters, so compare without any whitespace.
            assertTrue(out.replaceAll("\\s+", "").contains("最多5个已保存计划的主题、已保存灵感、日期和状态会作为参考发送给Gemini（不含备注和地点），且不会修改其中任何内容。"), out);
            // An idea is 灵感 in the wizard; 建议 is only the AI's suggestion (AI 建议（Gemini）).
            contains(out, "查看某个灵感的详情", "保存某个灵感", "其他限制（可选）");
            assertFalse(out.contains("某个建议") || out.contains("建议编号"), out);
            contains(out, "今天，去拍点什么？", "引导式发现 · 第 1 步（共 3 步）：地点与时间", "[1 地点与时间] > 2 拍摄偏好 > 3 灵感推荐",
                "地点或区域（必填）", "例如：Toronto, Ontario", "[未设置]", "查找与此名称匹配的地点？ [y/N]", "可用 240 分钟",
                "引导式发现 · 第 2 步（共 3 步）：拍摄偏好", "地点与时间（已完成） > [2 拍摄偏好] > 3 灵感推荐", "[ ] 1 风光", "[ ] 7 夜景", "其他兴趣（可选）",
                "11 条器材记录", "机身", "参考我已保存的拍摄计划？ [Y/n]", "风光; 建筑; 纪实", "一个包",
                "引导式发现 · 第 3 步（共 3 步）：灵感推荐", "当前结果所用的条件", "可行的建议", "PhotoPilot 核查", "AI 建议（Gemini）",
                "[OK] 可行 · ", "[NO] 不可行 · ", "AI 说明（Gemini；建议而非事实）", "请选择：", "已离开引导式发现");
            assertFalse(out.contains("[!discover."), "no missing keys");
            // Chinese wraps between characters: a mark or an English word never ends up alone before an unspaced sentence.
            assertTrue(out.lines().noneMatch(line -> line.strip().equals("[i]") || line.strip().equals("- Gemini")), out);
            fits(wizardLines(out, "今天，去拍点什么？", "已离开引导式发现"), 80);
            assertEquals(List.of("landscape", "architecture", "纪实"), app.facade().discovery().latest().orElseThrow().context().preferences());
        }
    }

    @Test void narrowTerminalsGetNoLineWiderThanTheirWidth() throws Exception {
        for (int width : new int[] {40, 60}) {
            var day = DiscoveryFixtures.day();
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "narrow" + width)) {
                gear(app);
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, width, false, false, happy(day));
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                var wizard = wizardLines(out, "What will you photograph today?", "Left Guided Discovery");
                fits(wizard, width);
                contains(out, "Checked by PhotoPilot", "[OK]", "was saved in");
                if (width == 40) {
                    contains(out, "Guided Discovery · Step 1/3", "  [1 Location & Time]", "  2 Photography Preferences");
                    assertFalse(out.contains(" [1 Location & Time] > 2"), "progress is listed one stage per line");
                }
            }
        }
        // Chinese at 40 columns: wide characters count as two columns.
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "narrow-zh")) {
            gear(app);
            app.localization().useLanguage("zh-CN");
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 40, false, false, lines("discover wizard", stageOne(day, "多伦多市中心"), "1 3", "纪实", "", "", "", "y", "", "D", "1", "2", "退出"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            fits(wizardLines(out, "今天，去拍点什么？", "已离开引导式发现"), 40);
            contains(out, "引导式发现 · 第 1/3 步", "PhotoPilot 核查");
        }
    }

    @Test void colourIsOptionalDecorationOnTopOfThePlainMarksAndWords() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "colour")) {
            gear(app);
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, true, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            contains(out, "\u001b[", "\u001b[32m[OK]\u001b[0m Feasible · ", "\u001b[31m[NO]\u001b[0m Not feasible · ", "[??]", "Unverified", "Checked by PhotoPilot");
            String plain = out.replaceAll("\u001b\\[[0-9;]*m", "");
            contains(plain, "[OK] Feasible · ", "[NO] Not feasible · ", "[??] Unverified · ", " [1 Location & Time] > 2 Photography Preferences");
            fits(wizardLines(plain, "What will you photograph today?", "Left Guided Discovery"), 80);
        }
        // The decision main makes: opt-in by terminal capability, NO_COLOR and TERM=dumb win over auto.
        assertTrue(CliTerminalAccess.colour(Map.of(), true, false));
        assertFalse(CliTerminalAccess.colour(Map.of(), false, false), "no console: plain");
        assertFalse(CliTerminalAccess.colour(Map.of("NO_COLOR", "1"), true, false));
        assertFalse(CliTerminalAccess.colour(Map.of("TERM", "dumb"), true, false));
        assertFalse(CliTerminalAccess.colour(Map.of(), true, true), "a classic Windows console is not assumed to understand ANSI");
        assertTrue(CliTerminalAccess.colour(Map.of("WT_SESSION", "x"), true, true));
        assertTrue(CliTerminalAccess.colour(Map.of("ConEmuANSI", "ON"), true, true));
        assertTrue(CliTerminalAccess.colour(Map.of("PHOTOPILOT_COLOR", "always"), false, true));
        assertFalse(CliTerminalAccess.colour(Map.of("PHOTOPILOT_COLOR", "never", "WT_SESSION", "x"), true, true));
    }

    @Test void scriptsBatchArgumentsAndPipesAreNeverPromptedAndExistingHelpIsKept() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "scripted")) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner("Downtown Toronto\n"), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(0, cli.execute("discover wizard"));
            String out = text(bytes);
            contains(out, "Guided Discovery asks questions, so it runs only in an interactive terminal.", "discover gear: review your equipment",
                "discover place <name>", "discover run location=", "discover saved <session-id>",
                "To use it, start PhotoPilot in a terminal without command arguments and type discover wizard.");
            assertTrue(out.lines().noneMatch(line -> line.startsWith(">")), "no prompt: " + out);
            assertFalse(out.contains("What will you photograph today?"));
            bytes.reset();
            assertEquals(2, cli.execute("discover wizard now"));
            assertTrue(text(bytes).startsWith("Error: "), text(bytes));
            bytes.reset();
            assertEquals(0, cli.execute("F02"));
            String f02 = text(bytes);
            bytes.reset();
            assertEquals(0, cli.execute("discover help"));
            assertEquals(text(bytes), f02);
            contains(f02, "discover run location=", "discover wizard [width=<columns>]: guided discovery in three steps", "also discover guided");
            bytes.reset();
            assertEquals(0, cli.execute("discover guided"));
            contains(text(bytes), "only in an interactive terminal");
            bytes.reset();
            assertEquals(2, cli.execute("discover wizard width=wide"));
            assertTrue(text(bytes).startsWith("Error: "), text(bytes));
            bytes.reset();
            assertEquals(0, cli.execute("help"));
            contains(text(bytes), "F02 / discover:", "F02 / discover wizard: guided discovery in three steps");

            // Batch arguments never read the remaining input as answers.
            var batchBytes = new ByteArrayOutputStream();
            var batch = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner("Downtown Toronto\nn\n"), new PrintStream(batchBytes, true, StandardCharsets.UTF_8), app.localization());
            batch.run(new String[] {"discover wizard"});
            assertEquals(0, batch.exitCode());
            contains(text(batchBytes), "only in an interactive terminal", "start PhotoPilot in a terminal without command arguments and type discover wizard");
            assertTrue(text(batchBytes).lines().noneMatch(line -> line.startsWith(">")));

            // An embedded interactive CLI is plain unless colour is enabled explicitly.
            var plainBytes = new ByteArrayOutputStream();
            var plain = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner("discover wizard\nexit\n"), new PrintStream(plainBytes, true, StandardCharsets.UTF_8), app.localization());
            plain.run(new String[0]);
            assertEquals(0, plain.exitCode());
            contains(text(plainBytes), "What will you photograph today?", "Left Guided Discovery");
            assertFalse(text(plainBytes).contains("\u001b"));
            assertTrue(gemini.requests.isEmpty());
        }

        // A real process with piped input has no console: the wizard explains the commands and the next line stays a command.
        Path work = Files.createDirectories(root.resolve("work"));
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-Duser.home=" + root.resolve("home"), "-cp", System.getProperty("java.class.path"), "com.photopilot.ui.cli.PhotoPilotCLI", "--lang=en"));
        var builder = new ProcessBuilder(command).directory(work.toFile());
        builder.environment().remove("PHOTOPILOT_DATA_DIR"); builder.environment().remove("PHOTOPILOT_CONFIG_DIR");
        builder.environment().put("PHOTOPILOT_PROGRESS", "0"); builder.environment().put("PHOTOPILOT_COLOR", "always");
        Path log = Files.createTempFile(work, "out-", ".log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process piped = builder.start();
        try (var in = piped.getOutputStream()) { in.write("discover wizard\nDowntown Toronto\nstatus\n".getBytes(StandardCharsets.UTF_8)); }
        assertTrue(piped.waitFor(60, TimeUnit.SECONDS));
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertEquals(2, piped.exitValue(), "the next script line is an (unknown) command, not an answer: " + text);
        contains(text, "only in an interactive terminal", "Unknown command. Type help.", "F11 AI Post-Shoot Analysis and Review - IMPLEMENTED");
        assertTrue(text.lines().noneMatch(line -> line.startsWith(">")), text);
        assertFalse(text.contains("\u001b"), "no colour codes outside the interactive wizard");
    }

    @Test void endOfInputInsideTheWizardEndsItAndTheCommandLoopCleanly() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "eof")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto"));
            var errors = new ByteArrayOutputStream();
            cli.diagnostics(new PrintStream(errors, true, StandardCharsets.UTF_8));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            contains(out, "Find matching places for this name? [y/N]", "Left Guided Discovery");
            assertFalse(out.contains("unexpected internal error"), out);
            assertEquals("", text(errors));
            assertTrue(gemini.requests.isEmpty());
        }
    }

    @Test void timeWindowProblemsAreShownRightAfterTheTimesAndNeverDescribedAsAValidWindow() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "window")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto", "n", day.toString(),
                "13:00", "13:00",          // equal times: an empty period for the request, not "24 hours, ends the next day"
                "13:00", "13:10",          // 10 minutes: shorter than the shortest activity
                "13:00", "17:00", "40",
                "5", "", "", "300", "180", // a longest activity beyond the 240 min window is its own problem, asked again
                "exit"));
            cli.run(new String[0]);
            String out = text(bytes), flat = flat(out);
            assertEquals(0, cli.exitCode(), out);
            assertTrue(gemini.requests.isEmpty());
            assertFalse(out.contains("1,440 min") || out.contains("1440 min") || out.contains("ends the next day"), out);
            int empty = flat.indexOf("[!] Available time: The availability period is empty");
            int tooShort = flat.indexOf("[!] Available time: The available period must be at least 15 minutes");
            int distance = flat.indexOf("Maximum travel distance (km, straight-line) (required)");
            assertTrue(empty > 0 && empty < tooShort && tooShort < distance, "each time problem right after the times, before the next input: " + out);
            assertFalse(flat.substring(0, distance).contains("10 min available"), "no window note for a window the request refuses");
            contains(out, "[i] 240 min available", "13:00–17:00 · 240 min available", "[!] Longest activity: ", "up to 180 min");
            assertTrue(flat.indexOf("[!] Longest activity: ") < flat.lastIndexOf("Longest activity (minutes, optional)"), "asked again after its problem");
        }
    }

    @Test void aDateAlreadyPastAtThePlaceIsReportedOnceByInputAndEditLeadsStraightToTheDate() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        String past = LocalDate.now().minusDays(2).toString();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "past")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto", "n", past, form.get("from"), form.get("until"), "40", "N",
                "", "", "", "", "", "", "D",   // stage 2 first pass (empty inventory: no list question), then Discover
                "E", day.toString(), "exit")); // Edit conditions asks the date again
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertTrue(gemini.requests.isEmpty(), "the service refuses the date before any AI request");
            contains(out, "[!] No ideas were generated and none were invented. Your inputs are kept.", "[!] Date: The shooting date " + past + " is in the past");
            assertEquals(1, count(out, "is in the past"), "the reason is printed once: " + out);
            String edit = out.substring(out.indexOf("No ideas were generated"));
            int step = edit.indexOf("Step 1 of 3"), date = edit.indexOf("Date (required)", Math.max(0, step));
            assertTrue(step > 0 && date > step && date < edit.indexOf("Choose:", step) && edit.indexOf("[" + past + "]", date) > date,
                "E asks the date first, before the stage menu: " + edit);
        }
    }

    @Test void laterCommandsReplaceTheWizardResultsAndAFreshWizardStartsFromTheLastRequest() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "replaced")) {
            gear(app);
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "X",
                "discover run " + args(day), "discover wizard", "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(2, gemini.requests.size());
            String resumed = out.substring(out.indexOf("Continuing with your earlier wizard inputs"));
            contains(resumed, "The wizard's earlier results were replaced by a later discover request.", "Guided Discovery · Step 2 of 3");
            assertFalse(resumed.contains("Step 3 of 3") || resumed.contains("Conditions for these results") || resumed.contains("View current results"),
                "results that are gone are never shown or offered: " + resumed);
            assertFalse(resumed.contains("No current results"), "one note only");
        }
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "fromLast")) {
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover run " + args(day), "discover wizard", "", "exit"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            String wizard = out.substring(out.indexOf("What will you photograph today?"));
            contains(wizard, "Starting from the inputs of your last discover request.", "[" + DiscoveryFixtures.PLACE + "]", "[" + day + "]");
            assertEquals(1, gemini.requests.size(), "starting the wizard sends nothing");
        }
    }

    @Test void aProfileSwitchForgetsTheWizardInputs() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var profiles = new ProfileManager(providers.environment(gemini.environment(root.resolve("profiles"))))) {
            UUID first = ok(profiles.activeProfile()).id(), second = ok(profiles.createProfile("Second")).id();
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(profiles, new Scanner(String.join("\n", "discover wizard", "Harbourfront", "n", "exit",
                "profile select " + second, "discover wizard", "exit", "profile select " + first, "discover wizard", "exit")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
            CliTerminalAccess.configure(cli, 80, false, false);
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(3, count(out, "What will you photograph today?"), out);
            assertFalse(out.contains("Continuing with your earlier wizard inputs"), "a profile switch clears the wizard: " + out);
            String switched = out.substring(out.indexOf("What will you photograph today?", out.indexOf("Left Guided Discovery")));
            contains(switched, "Place or area (required)", "[not set]");
            assertFalse(switched.contains("Harbourfront"), switched);
            assertTrue(gemini.requests.isEmpty());
        }
    }

    @Test void chineseNavigationWordsHelpAndADeclinedResetKeepTheInputs() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "zh-nav")) {
            app.localization().useLanguage("zh-CN");
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "帮助", "多伦多市中心", "n", day.toString(), form.get("from"), form.get("until"),
                "下一步",        // skips the distance: its problem is listed, not asked
                "N", "上一步",   // Next is refused and the distance asked again; 上一步 leaves that question
                "重置", "n",     // reset declined: every input kept
                "4", "返回",     // 返回 also goes back
                "退出"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            contains(out, "引导式发现的导航方式", "back/上一步：回到上一步", "按 80 列换行。", "[!] 继续之前请检查以下输入：", "[!] 最大出行距离：",
                "将向导中的所有输入重置为默认值？ [y/N]", "多伦多市中心 · 仅名称，无坐标", "N  下一步：拍摄偏好", "已离开引导式发现");
            assertFalse(out.contains("输入已重置"), "declined");
            assertFalse(out.contains("第 2 步（共 3 步）"), "never advanced with a missing distance");
            assertTrue(gemini.requests.isEmpty());
        }
    }

    @Test void noIdeasOffersNoIdeaActionsAndTheConditionsKeepThePlaceCaveat() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "none")) {
            gemini.reply(DiscoveryFixtures.reply(new com.google.gson.JsonArray()));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Downtown Toronto", "n", day.toString(), form.get("from"), form.get("until"), "40", "N",
                "", "", "", "", "", "", "D", "3", "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            contains(out, "0 feasible, 0 unverified, 0 not suitable", "No usable idea was found for these constraints.",
                "Place: Downtown Toronto (name only; no coordinates, so distance and weather are not checked)",
                "5  Browse saved ideas", "7  Discover again", "[!] Choose one of the listed options.");
            String results = out.substring(out.indexOf("Conditions for these results"));
            assertFalse(results.contains("Show details of an idea") || results.contains("Save an idea") || results.contains("Dismiss an idea"), results);
            assertFalse(out.contains("(1–0)"), "no idea number prompt that no answer can satisfy");
        }
    }

    @Test void resetAtTheResultsForgetsThemAndStartsAgainFromTheDefaults() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "reset")) {
            gear(app);
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(),
                "4", "3",                    // dismiss idea 3: counts and remaining numbers, not the whole list again
                "R", "y",
                DiscoveryFixtures.PLACE, "n", day.toString(), form.get("from"), form.get("until"), "40", "N",
                "", "", "", "", "", "n", "", // stage 2 first pass from the defaults
                "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(1, gemini.requests.size());
            String dismissed = between(out, "was dismissed from these results", "Choose:");
            contains(dismissed, "1 feasible, 1 unverified, 3 not suitable", "Still listed: 1, 2, 4, 5, 6. Choose L to list them again.");
            assertFalse(dismissed.contains("Checked by PhotoPilot"), "no full re-render after a dismissal");
            String after = out.substring(out.indexOf("Inputs reset."));
            contains(after, "Place or area (required)", "[not set]", "Guided Discovery · Step 2 of 3", "D  Discover photography ideas");
            assertFalse(after.contains("View current results") || after.contains("Conditions for these results"), "reset forgets the shown results: " + after);
        }
    }

    @Test void aWidthArgumentNarrowsTheWizardWhereTheTerminalExportsNoColumns() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "width")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard width=40", "help", "exit"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            contains(out, "Guided Discovery · Step 1/3", "Lines wrap to 40 columns.", "discover wizard width=<columns>", "COLUMNS");
            fits(wizardLines(out, "What will you photograph today?", "Left Guided Discovery"), 40);
        }
    }

    static String mark(Feasibility status) {
        return switch (status) { case FEASIBLE -> "[OK]"; case UNVERIFIED -> "[??]"; case INFEASIBLE -> "[NO]"; };
    }

    @Test void passingChecksKeepTheirForecastWarningsAndTravelLimitsVisibleInTheListing() throws Exception {
        var day = DiscoveryFixtures.day();
        var warnings = List.of("Warning: thunderstorms are forecast during this time.", "Warning: precipitation probability up to 90%.",
            "Warning: wind gusts up to 85 km/h.");
        for (int width : new int[] {40, 50, 80}) {
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "storm" + width)) {
                gear(app);
                providers.forecast = forecast(STORM, Set.of());
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, width, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "1", "1", "X"));
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                fits(wizardLines(out, "What will you photograph today?", "Left Guided Discovery"), width);
                var first = app.facade().discovery().latest().orElseThrow().ideas().getFirst();
                assertEquals(Feasibility.FEASIBLE, first.feasibility(), "the forecast warnings lower the ranking but do not decide feasibility");
                var view = DiscoveryPresentation.view(first, app.localization());
                var weather = view.checks().stream().filter(c -> c.name().equals("Light and weather")).findFirst().orElseThrow();
                assertEquals(Feasibility.FEASIBLE, weather.status());
                assertTrue(weather.shown().containsAll(warnings), weather.shown()::toString);
                // Listed under the [OK] Light and weather row of the feasible idea, each with a [!] mark and its words.
                var block = listed(out, 1, first.subject());
                String below = under(block, "[OK] Light and weather: Feasible");
                for (String warning : warnings) squeezedContains(below, "[!] " + warning);
                // Every check keeps its visible findings under its own row: the cautions of passing checks (travel limits
                // too) marked [!], every finding of a check that did not pass as before.
                for (var check : view.checks()) {
                    String rows = under(block, mark(check.status()) + " " + check.name() + ":");
                    for (String finding : check.shown()) squeezedContains(rows, (check.status() == Feasibility.FEASIBLE ? "[!] " : "- ") + finding);
                }
                squeezedContains(String.join("\n", block), "[!] Travel time is not estimated and is not included");
                // The details action still prints every finding, including the evidence the listing leaves to it.
                assertTrue(view.checks().stream().anyMatch(c -> !c.details().isEmpty()), "the scenario has details-only evidence");
                String details = between(out, "Idea 1 - ", "Choose:");
                for (var check : view.checks()) for (String finding : check.findings()) squeezedContains(details, finding);
            }
        }
    }

    @Test void forecastFieldsTheProvidersDidNotSupplyAreShownAsWarningsWithTheResults() throws Exception {
        var day = DiscoveryFixtures.day();
        for (int width : new int[] {40, 80}) {
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "gaps" + width)) {
                gear(app);
                providers.forecast = forecast(Map.of(), Set.of("precipitation_probability", "visibility", "wind_gusts_10m"));
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, width, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "X"));
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                fits(wizardLines(out, "What will you photograph today?", "Left Guided Discovery"), width);
                var gaps = DiscoveryPresentation.gaps(app.facade().discovery().latest().orElseThrow(), app.localization());
                assertEquals(List.of("Not supplied by the providers: Precipitation chance, Visibility, Wind gusts"), gaps);
                // In the results header, with the notes and before the first idea, as a [!] line; printed once.
                String header = between(out, "Conditions for these results", "Checked by PhotoPilot");
                squeezedContains(between(header, "PhotoPilot notes", "Suggested by AI (Gemini)"), "[!] " + gaps.getFirst());
                assertEquals(1, squeezed(out).split(Pattern.quote(squeezed(gaps.getFirst())), -1).length - 1, out);
            }
        }
    }

    @Test void typedCoordinatesReplaceALookupMatchEvenWhenANavigationWordEndsTheLongitudePrompt() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "stale-next")) {
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Toronto", "y", "1", day.toString(), form.get("from"), form.get("until"), "40",
                "5", "43.70000", "next",       // a new latitude, then next at the longitude prompt
                "N", "", "", "", "", "", "",   // stage 2 first pass (empty inventory: no list question)
                "D", "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(1, gemini.requests.size());
            contains(out, "[OK] Selected Toronto, Ontario, Canada.", "Toronto, Ontario, Canada · 43.70643, -79.39864 · place lookup");
            String edited = out.substring(out.indexOf("Latitude (optional)"));
            contains(edited, "Toronto, Ontario, Canada · 43.70000, -79.39864 · entered coordinates", "Conditions for these results",
                "Toronto, Ontario, Canada (43.70000, -79.39864)");
            assertFalse(edited.contains("· place lookup"), edited);
            assertFalse(out.contains("The place is approximate") || out.contains("approximate point"), out);
            // A later "discover again" uses the typed coordinates too, not the earlier match.
            bytes.reset();
            assertEquals(0, cli.execute("discover again"), text(bytes));
            String again = text(bytes);
            assertEquals(2, gemini.requests.size());
            contains(again, "Place: Toronto, Ontario, Canada (43.70000, -79.39864)");
            assertFalse(again.contains("The place is approximate") || again.contains("approximate point"), again);
        }
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "stale-exit")) {
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover wizard", "Toronto", "y", "1", day.toString(), form.get("from"), form.get("until"), "40",
                "5", "44.00000", "exit",       // exit at the longitude prompt after a new latitude
                "discover wizard", "exit"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            String resumed = out.substring(out.indexOf("Continuing with your earlier wizard inputs"));
            contains(resumed, "Toronto, Ontario, Canada · 44.00000, -79.39864 · entered coordinates");
            assertFalse(resumed.contains("· place lookup"), resumed);
            assertTrue(gemini.requests.isEmpty());
        }
    }

    @Test void theHandOffDraftIsWrappedToTheWidthAndKeepsTheCommandsText() throws Exception {
        var day = DiscoveryFixtures.day();
        for (int width : new int[] {40, 50, 80}) {
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "handoff" + width)) {
                gear(app);
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, width, false, false, lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "2", "1", "N"));
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                contains(out, "is open in Requirements", "unconfirmed F03 draft");
                fits(fromHeading(out, "What will you photograph today?"), width);
                // The command itself still prints the same draft unwrapped; the wizard shows exactly its text.
                bytes.reset();
                assertEquals(0, cli.execute("requirement draft"));
                String draft = text(bytes);
                assertTrue(draft.lines().anyMatch(line -> CliTerminalAccess.columns(line) > 80), "requirement draft is unchanged (unwrapped): " + draft);
                squeezedContains(out, draft);
            }
        }
    }

    /** {@code token} is printed on one line, and no two neighbouring lines hold a split of it (its head, then its tail). */
    static void whole(List<String> lines, String token, int width) {
        assertTrue(lines.stream().anyMatch(line -> line.contains(token)), () -> "\"" + token + "\" not on one line at " + width + ":\n" + String.join("\n", lines));
        for (int i = 0; i + 1 < lines.size(); i++)
            for (int cut = 1; cut < token.length(); cut++) {
                String line = lines.get(i), next = lines.get(i + 1);
                assertFalse(line.stripTrailing().endsWith(token.substring(0, cut)) && next.stripLeading().startsWith(token.substring(cut)),
                    () -> "\"" + token + "\" split at " + width + " columns: [" + line + "] / [" + next + "]");
            }
    }

    @Test void chineseCommandTokensStayWholeInTheHandOffDraftAndTheHelpAtEveryWidth() throws Exception {
        var day = DiscoveryFixtures.day();
        for (int width = 40; width <= 80; width++) {
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "zh-tokens" + width)) {
                gear(app);
                app.localization().useLanguage("zh-CN");
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, width, false, false, lines("discover wizard", "帮助", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "2", "1", "N"));
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                var wizard = fromHeading(out, app.localization().text("discover.cli.wizard.heading"));
                fits(wizard, width);
                squeezedContains(out, "引导式发现的导航方式", "未确认 F03 草稿",
                    "下一步：requirement set 字段=值、requirement clarify <回答>、requirement confirm 或 requirement cancel。");
                // The help's width template and the draft's "Next:" commands are copied as typed: never split inside a token.
                whole(wizard, "width=<列数>", width);
                whole(wizard, "<回答>", width);
                whole(wizard, "字段=值", width);
            }
        }
    }

    @Test void scriptedGuidanceNeverSplitsACommandTemplateAndSaysHowToStartTheWizard() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "scripted-zh")) {
            app.localization().useLanguage("zh-CN");
            for (int width : new int[] {80, 50, 40}) {
                var bytes = new ByteArrayOutputStream();
                var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
                CliTerminalAccess.configure(cli, width, false, false);
                assertEquals(0, cli.execute("discover wizard"));
                String out = text(bytes);
                fits(out.lines().toList(), width);
                assertTrue(out.lines().anyMatch(line -> line.contains("distance=<公里>")), "the template token stays whole at " + width + ":\n" + out);
                assertTrue(out.lines().anyMatch(line -> line.contains("<拍摄计划ID>：")), out);
                squeezedContains(out, "引导式发现需要回答问题，因此只能在交互式终端中运行。", "请在终端中不带命令参数启动 PhotoPilot，然后输入 discover wizard",
                    "discover run location=\"..\" date=YYYY-MM-DD from=HH:MM until=HH:MM distance=<公里> [interests=\"a; b\"]：发现拍摄灵感");
                assertTrue(out.lines().noneMatch(line -> line.startsWith(">")), "never a prompt: " + out);
            }
            assertTrue(gemini.requests.isEmpty());
        }
    }

    @Test void interestsKeptWithEnterReachTheRequestExactlyAsTheCommandSentThem() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        form.put("interests", "Night, ferries; Aviation; night");
        String args = form.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(Collectors.joining(" "));
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, "kept-interests")) {
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            var bytes = new ByteArrayOutputStream();
            var cli = cli(app, bytes, 80, false, false, List.of("discover run " + args, "discover wizard",
                "", "", "", "", "",            // stage 1 first pass: place, date, from, until and distance kept
                "N", "", "", "", "", "", "",   // stage 2 first pass: categories and every text kept (empty inventory)
                "D", "X"));
            cli.run(new String[0]);
            String out = text(bytes);
            assertEquals(0, cli.exitCode(), out);
            assertEquals(2, gemini.requests.size(), out);
            var interests = Pattern.compile("interests[\\\\\"]*\\s*:\\s*\\[[^\\]]*\\]");
            var command = interests.matcher(gemini.requests.get(0)); var wizard = interests.matcher(gemini.requests.get(1));
            assertTrue(command.find() && wizard.find(), gemini.requests::toString);
            assertTrue(command.group().contains("Night") && command.group().contains("ferries"), command.group());
            assertEquals(command.group(), wizard.group(), "the wizard sends the interests exactly as the command did");
            assertEquals(List.of("Night", "ferries", "Aviation", "night"), app.facade().discovery().latest().orElseThrow().context().preferences());
        }
    }

    @Test void representativeTranscriptsStayWithinTheirWidth() throws Exception {
        var day = DiscoveryFixtures.day();
        var form = DiscoveryFixtures.form(day);
        /** {@code forecast} null: the default fake forecast; {@code handOff}: the wizard ends with a hand-off, not with its "left" line. */
        record Scenario(String file, String title, String language, int width, Function<Map<String, String>, FakeEnvironmentProviders.Reply> forecast,
                boolean handOff, List<String> lines) { }
        var scenarios = List.of(
            new Scenario("wizard-en-80.txt", "English, 80 columns: full flow, save an idea, browse saved ideas", "en", 80, null, false, happy(day)),
            new Scenario("wizard-zh-80.txt", "Simplified Chinese, 80 columns: full flow and idea details", "zh-CN", 80, null, false,
                lines("discover wizard", stageOne(day, "多伦多市中心"), "1 3", "纪实", "", "一个包", "", "y", "", "D", "1", "2", "退出")),
            new Scenario("wizard-en-50.txt", "English, 50 columns: a validation error, results, details and a dismissal", "en", 50, null, false,
                lines("discover wizard", DiscoveryFixtures.PLACE, "n", day.toString(), form.get("from"), form.get("until"), "far", "40",
                    "5", DiscoveryFixtures.LAT, DiscoveryFixtures.LON, "", "N", stageTwo(), "1", "3", "4", "6", "X")),
            new Scenario("wizard-en-40.txt", "English, 40 columns: stormy forecast without visibility data, idea details and a hand-off to Shooting Requirements",
                "en", 40, forecast(STORM, Set.of("visibility")), true,
                lines("discover wizard", stageOne(day, DiscoveryFixtures.PLACE), stageTwo(), "1", "1", "2", "1", "N")));
        for (var scenario : scenarios) {
            try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders(); var app = app(gemini, providers, scenario.file())) {
                gear(app);
                app.localization().useLanguage(scenario.language());
                if (scenario.forecast() != null) providers.forecast = scenario.forecast();
                gemini.reply(DiscoveryFixtures.acceptanceReply(day));
                var bytes = new ByteArrayOutputStream();
                var cli = cli(app, bytes, scenario.width(), false, true, scenario.lines());
                cli.run(new String[0]);
                String out = text(bytes);
                assertEquals(0, cli.exitCode(), out);
                String heading = app.localization().text("discover.cli.wizard.heading"), left = app.localization().text("discover.cli.wizard.left");
                fits(scenario.handOff() ? fromHeading(out, heading) : wizardLines(out, heading, left.substring(0, 6)), scenario.width());
                if (scenario.forecast() != null)
                    squeezedContains(out, "[!] Warning: thunderstorms are forecast during this time.", "[!] Not supplied by the providers: Visibility",
                        "unconfirmed F03 draft");
                transcript(scenario.file(), "# PhotoPilot CLI transcript - " + scenario.title() + System.lineSeparator()
                    + "# Typed at the command loop: discover wizard. Each answer is shown after its \"> \" prompt.", out);
            }
        }
    }
}
