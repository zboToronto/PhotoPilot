package com.photopilot.application;

import com.photopilot.application.DiscoveryGuide.Slot;
import com.photopilot.application.DiscoveryGuide.Stage;
import com.photopilot.application.DiscoveryInterests.Category;
import com.photopilot.application.DiscoveryPresentation.GearCount;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GearInventory;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared Guided Discovery helpers the GUI stages and the CLI wizard both use: preset interests only supplement the
 * typed text, validation messages are grouped by stage and input without new rules, availability windows follow the
 * request's own midnight rule, and the equipment summary keeps inventory records apart from units.
 */
class DiscoveryPresentationViewTest {
    private final LocalizationService english = LocalizationService.english();

    @Test void theGuidedContextCountsRecordsAndNamesMissingFieldsWhileTheLegacyContextStaysAsItWas() {
        var chinese = LocalizationService.english(); chinese.useLanguage("zh-CN");
        var home = new com.photopilot.model.GeoLocation("Downtown Toronto", 43.6532, -79.3832, false, false, Optional.empty());
        var zone = java.time.ZoneId.of("America/Toronto");
        var start = java.time.ZonedDateTime.of(2026, 11, 27, 13, 0, 0, 0, zone);
        var now = java.time.Instant.parse("2026-11-26T12:00:00Z");
        var missing = Set.of("hourly.visibilityM", "hourly.windGustsKmh", "daily.temperatureMaxC", "sun.sunrise@2026-11-28", com.photopilot.model.EnvironmentContext.SUN);
        var env = new com.photopilot.model.EnvironmentContext(home, start.toLocalDate(), start.toLocalDate(), Optional.of(zone), Map.of(), List.of(), List.of(),
            Map.of(com.photopilot.model.EnvironmentContext.HOURLY, java.net.URI.create("https://api.open-meteo.com/v1/forecast")),
            Map.of(com.photopilot.model.EnvironmentContext.HOURLY, now), missing, List.of(), now);
        var gear = new GearInventory(new GearFixtures.Airshow().items());
        var context = new com.photopilot.model.DiscoveryContext(Optional.of(home), start, java.time.Duration.ofHours(4), 40, List.of("landscape"), gear, List.of(),
            Optional.of(env), List.of(), "", "", Optional.empty(), com.photopilot.model.DiscoveryContext.ZoneSource.PROVIDER, true, List.of(), List.of(), Set.of(),
            List.of());
        var result = new com.photopilot.model.DiscoveryResult(context, List.of(), List.of(), List.of(LocalizedMessage.of("discover.notice.noLoadout"),
            LocalizedMessage.of("discover.notice.historyOff")), List.of(), List.of(), List.of(), "", now);
        int records = gear.items().size();

        var legacy = DiscoveryPresentation.context(context, english);
        var guided = DiscoveryPresentation.guidedContext(context, english);
        assertEquals(legacy.size(), guided.size(), "line for line the same context");
        assertTrue(legacy.contains(english.text("discover.context.gear", records, 0)), "the legacy discover output is unchanged");
        assertTrue(legacy.contains("Not supplied by the providers: " + String.join(", ", new TreeSet<>(missing))), "the legacy line keeps the provider names");
        String expected = "Not supplied by the providers: daily.temperatureMaxC, Visibility, Wind gusts, Sunrise on 2026-11-28, Sun times";
        assertTrue(guided.contains("Gear: " + records + " inventory record(s), 0 verified camera and lens combination(s)"), guided::toString);
        assertTrue(guided.contains(expected), guided::toString);
        assertEquals(List.of(expected), DiscoveryPresentation.gaps(result, english), "the always-visible gap line names fields by their labels");
        for (int i = 0; i < legacy.size(); i++) {
            String line = legacy.get(i);
            if (!line.startsWith("Gear:") && !line.startsWith("Not supplied")) assertEquals(line, guided.get(i), "only the gear and gap lines differ");
        }
        // Chinese: records are 条器材记录 like the equipment summary, never 件; field names are Chinese labels.
        var zh = DiscoveryPresentation.guidedContext(context, chinese);
        assertTrue(zh.contains("器材：" + records + " 条器材记录，0 个已核实的机身与镜头组合"), zh::toString);
        assertTrue(zh.stream().noneMatch(line -> line.contains("器材库中有")), zh::toString);
        assertEquals(List.of("服务未提供：daily.temperatureMaxC, 能见度, 阵风, 2026-11-28 的日出, 太阳时间数据"), DiscoveryPresentation.gaps(result, chinese));
        // Notices about limits of the evidence are warnings; plain information is not.
        assertEquals(List.of(new DiscoveryPresentation.Notice(english.text("discover.notice.noLoadout"), true),
            new DiscoveryPresentation.Notice(english.text("discover.notice.historyOff"), false)), DiscoveryPresentation.notices(result, english));
        for (String key : List.of("discover.notice.environmentUnavailable", "discover.notice.noForecast", "discover.notice.zoneComputer",
                "discover.notice.inventoryUnreadable", "discover.notice.noGear", "discover.notice.noCoordinates", "env.notice.stale", "env.notice.partlyPast"))
            assertTrue(DiscoveryPresentation.warning(LocalizedMessage.of(key)), key);
        for (String key : List.of("discover.notice.history", "discover.notice.noHistory", "discover.notice.device", "discover.notice.rejected", "discover.notice.rankingIgnored"))
            assertFalse(DiscoveryPresentation.warning(LocalizedMessage.of(key)), key);
        assertEquals("PhotoPilot 未对此灵感运行检查。", chinese.text("discover.verify.none"));
        assertTrue(chinese.text("discover.verify.counts", 5, 0, 0, 5).startsWith("PhotoPilot 核查："));
    }

    @Test void presetInterestsSupplementTheTypedTextWithoutRewritingIt() {
        assertEquals("landscape; aviation; waterfalls; markets", DiscoveryInterests.compose(EnumSet.of(Category.AVIATION, Category.LANDSCAPE), "waterfalls, markets"),
            "presets in display order, then the typed entries in their order");
        assertEquals("", DiscoveryInterests.compose(Set.of(), ""), "no interests is valid");
        assertEquals("night", DiscoveryInterests.compose(Set.of(Category.NIGHT), "  "));
        var split = DiscoveryInterests.split("Aviation; waterfalls，night; old harbour");
        assertEquals(EnumSet.of(Category.AVIATION, Category.NIGHT), split.presets(), "exact preset terms are recognised in any case");
        assertEquals(List.of("waterfalls", "old harbour"), split.custom(), "everything else is kept verbatim");
        assertEquals("waterfalls; old harbour", split.customText());
        assertEquals(DiscoveryForm.entries("aviation; night; waterfalls; old harbour"),
            DiscoveryForm.entries(DiscoveryInterests.compose(split.presets(), split.customText())), "split and compose keep the request's entries");
        assertTrue(DiscoveryInterests.split("").presets().isEmpty() && DiscoveryInterests.split(null).custom().isEmpty());
        for (var category : Category.values()) {
            assertEquals(Optional.of(category), DiscoveryInterests.byTerm(category.term().toUpperCase(Locale.ROOT)));
            assertFalse(english.text(category.labelKey()).startsWith("[!"), category.labelKey());
        }
    }

    @Test void validationMessagesAreGroupedByStageAndInputWithoutNewRules() {
        var parsed = DiscoveryForm.parse(DiscoveryForm.defaults(LocalDate.now()), null, Optional.empty());
        assertFalse(parsed.isSuccess());
        var keys = parsed.messages().stream().map(LocalizedMessage::key).toList();
        assertEquals(List.of("discover.error.location", "env.error.time", "discover.error.distance"), keys, "the defaults miss exactly the required inputs");
        assertEquals(parsed.messages(), DiscoveryGuide.problems(parsed, Stage.LOCATION_TIME));
        assertEquals(Optional.of(Stage.LOCATION_TIME), DiscoveryGuide.firstStage(parsed.messages()));
        var grouped = DiscoveryGuide.group(parsed.messages());
        assertEquals(List.of(Slot.LOCATION, Slot.TIME, Slot.DISTANCE), List.copyOf(grouped.keySet()));

        Result<Object> mixed = Result.failures(List.of(LocalizedMessage.of("discover.error.list", 20, 100), LocalizedMessage.of("discover.error.coordinatePair"),
            LocalizedMessage.of("discover.error.context")));
        assertEquals(List.of("discover.error.coordinatePair"), DiscoveryGuide.problems(mixed, Stage.LOCATION_TIME).stream().map(LocalizedMessage::key).toList(),
            "leaving stage 1 only needs stage 1 to be valid");
        assertEquals(2, DiscoveryGuide.problems(mixed, Stage.PREFERENCES).size());
        assertEquals(3, DiscoveryGuide.problems(mixed, Stage.REVIEW).size());
        assertTrue(DiscoveryGuide.slot(LocalizedMessage.of("discover.error.coordinatePair")).advanced());
        assertTrue(DiscoveryGuide.slot(LocalizedMessage.of("discover.error.durationWindow", 90, 60)).advanced());
        assertEquals(Slot.DATE, DiscoveryGuide.slot(LocalizedMessage.of("env.error.pastDate", "2020-01-01")));
        assertEquals(Slot.GENERAL, DiscoveryGuide.slot(LocalizedMessage.of("discover.error.context")));
        assertEquals(Stage.REVIEW, Slot.GENERAL.stage());
        assertTrue(DiscoveryGuide.problems(Result.success("ok"), Stage.REVIEW).isEmpty());
        assertEquals(Set.of("location", "date", "from", "until", "distance"), DiscoveryGuide.REQUIRED);
        for (String field : DiscoveryForm.FIELDS) assertNotEquals(Stage.REVIEW, DiscoveryGuide.stage(field), field + " belongs to an input stage");
    }

    @Test void availabilityWindowsFollowTheRequestsMidnightRule() {
        var day = DiscoveryGuide.window("13:00", "17:00").orElseThrow();
        assertEquals(240, day.length().toMinutes()); assertFalse(day.overnight());
        var night = DiscoveryGuide.window(" 22:00 ", "02:00").orElseThrow();
        assertEquals(240, night.length().toMinutes()); assertTrue(night.overnight());
        assertEquals("240 min available, ends the next day", DiscoveryPresentation.window(night, english));
        assertEquals("240 min available", DiscoveryPresentation.window(day, english));
        assertTrue(DiscoveryGuide.window("25:00", "02:00").isEmpty(), "the form's strict HH:MM rule");
        assertTrue(DiscoveryGuide.window("9:00", "10:00").isEmpty());
        assertTrue(DiscoveryGuide.window(null, "10:00").isEmpty());
        assertEquals(1, DiscoveryGuide.window("08:00", "08:01").orElseThrow().length().toMinutes());
        var almostDay = DiscoveryGuide.window("08:01", "08:00").orElseThrow();
        assertEquals(24 * 60 - 1, almostDay.length().toMinutes(), "an earlier end is the next day, as the request computes it"); assertTrue(almostDay.overnight());
        assertTrue(DiscoveryGuide.window("08:00", "08:00").isEmpty(), "an equal start and end is the empty window the request rejects, never 24 h");
        var equal = DiscoveryForm.defaults(LocalDate.now());
        equal.put("location", "Harbourfront"); equal.put("from", "08:00"); equal.put("until", "08:00"); equal.put("distance", "5");
        assertEquals(List.of("env.error.emptyAvailability"), DiscoveryForm.parse(equal, null, Optional.empty()).messages().stream().map(LocalizedMessage::key).toList());
    }

    @Test void aDistanceIsShownOnlyWhenTheFormAndTheRequestAcceptIt() {
        assertEquals(Optional.of(0.0), DiscoveryGuide.distance("0"));
        assertEquals(Optional.of(40.0), DiscoveryGuide.distance(" 40 "));
        assertEquals(Optional.of(1000.0), DiscoveryGuide.distance("1000"));
        assertEquals(Optional.of(12.125), DiscoveryGuide.distance("12.125"));
        for (String rejected : List.of("", "far", "1000.5", "5000", "40.12345", "-3", "1e3", "12.", ".5"))
            assertTrue(DiscoveryGuide.distance(rejected).isEmpty(), rejected);
        for (String text : List.of("0", "40", "1000", "12.125", "1000.5", "5000", "40.12345", "far")) {
            var values = DiscoveryForm.defaults(LocalDate.now());
            values.put("location", "Harbourfront"); values.put("from", "08:00"); values.put("until", "10:00"); values.put("distance", text);
            assertEquals(DiscoveryForm.parse(values, null, Optional.empty()).isSuccess(), DiscoveryGuide.distance(text).isPresent(), "same verdict as the request for " + text);
        }
    }

    @Test void equipmentSummaryKeepsInventoryRecordsApartFromUnits() {
        var airshow = new GearFixtures.Airshow();
        var counts = DiscoveryPresentation.gearCounts(airshow.inventory());
        assertEquals(List.of(new GearCount(EquipmentType.CAMERA, 2, 2), new GearCount(EquipmentType.LENS, 5, 5), new GearCount(EquipmentType.BATTERY, 2, 6),
            new GearCount(EquipmentType.STORAGEMEDIA, 1, 2), new GearCount(EquipmentType.FILTER, 1, 1)), counts);
        assertEquals("6 unit(s)", DiscoveryPresentation.gearUnits(counts.get(2), english));
        assertEquals("2 inventory record(s)", DiscoveryPresentation.gearRecords(counts.get(2), english));
        assertEquals("Camera bodies: 2 · Lenses: 5 · Batteries: 6 · Memory cards: 2 · Filters: 1 (11 inventory record(s), 16 unit(s) in total)",
            DiscoveryPresentation.gearOverview(airshow.inventory(), english));

        var withoutFilter = new GearInventory(airshow.items().stream().filter(item -> item != airshow.cpl).toList());
        assertTrue(DiscoveryPresentation.gearCounts(withoutFilter).stream().noneMatch(c -> c.type() == EquipmentType.FILTER), "filters only when recorded");
        var empty = DiscoveryPresentation.gearCounts(new GearInventory(List.of()));
        assertEquals(4, empty.size(), "the four main categories are always summarised");
        assertTrue(empty.stream().allMatch(c -> c.records() == 0 && c.units() == 0));
        assertEquals(english.text("discover.gear.empty"), DiscoveryPresentation.gearOverview(new GearInventory(List.of()), english));

        var groups = DiscoveryPresentation.gearGroups(airshow.inventory(), english);
        assertEquals(List.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.BATTERY, EquipmentType.STORAGEMEDIA, EquipmentType.FILTER), List.copyOf(groups.keySet()));
        assertEquals(List.of("Sony NP-FZ100 ×4", "Sony LP-E6NH ×2"), groups.get(EquipmentType.BATTERY));
        assertEquals(airshow.items().size(), groups.values().stream().mapToInt(List::size).sum(), "every record is listed once");
    }

    @Test void forecastWarningsAndTravelLimitsOfAPassingCheckStayVisible() {
        var idea = new com.photopilot.model.ActivitySuggestion(UUID.randomUUID(), "Harbour sunset", "landscape", "Pier", "Harbourfront", false,
            Optional.of(java.time.ZonedDateTime.parse("2030-06-01T19:00-04:00[America/Toronto]")), java.time.Duration.ofMinutes(60), Optional.empty(), "any",
            List.of(), "Warm light.", List.of(), "UNVERIFIED", Optional.empty());
        var feasible = com.photopilot.model.ActivitySuggestion.Feasibility.FEASIBLE;
        var checked = idea.withChecks(List.of(
            new com.photopilot.model.ActivitySuggestion.Check("environment", feasible, List.of(LocalizedMessage.of("discover.check.env.forecast", 2, "2030-06-01 08:00 America/Toronto"),
                LocalizedMessage.of("discover.check.env.warn.thunder"), LocalizedMessage.of("discover.check.env.warn.gusts", "85"))),
            new com.photopilot.model.ActivitySuggestion.Check("travel", feasible, List.of(LocalizedMessage.of("discover.check.travel.within", "Harbourfront", "3", "40"),
                LocalizedMessage.of("discover.check.travel.straightLine"))),
            new com.photopilot.model.ActivitySuggestion.Check("avoid", com.photopilot.model.ActivitySuggestion.Feasibility.INFEASIBLE,
                List.of(LocalizedMessage.of("discover.check.avoid.conflict", "crowds")))));
        var view = DiscoveryPresentation.view(checked, english);
        var environment = view.checks().get(0);
        assertEquals(List.of(english.text("discover.check.env.warn.thunder"), english.text("discover.check.env.warn.gusts", "85")), environment.shown(),
            "adverse-forecast warnings of a passing check are visible");
        assertEquals(List.of(english.text("discover.check.env.forecast", 2, "2030-06-01 08:00 America/Toronto")), environment.details(), "plain evidence may collapse");
        assertEquals(List.of(english.text("discover.check.travel.straightLine")), view.checks().get(1).shown(), "the straight-line limitation is visible");
        assertEquals(view.checks().get(2).findings(), view.checks().get(2).shown(), "every finding of a check that did not pass is visible");
        assertTrue(view.checks().get(2).details().isEmpty());
        for (var check : view.checks()) {
            var both = new ArrayList<>(check.shown()); both.addAll(check.details());
            assertEquals(new HashSet<>(check.findings()), new HashSet<>(both), "nothing is dropped: " + check.criterion());
            assertEquals(check.findings().size(), both.size());
        }
        assertTrue(DiscoveryPresentation.caution(LocalizedMessage.of("discover.check.time.travelExcluded")));
        assertFalse(DiscoveryPresentation.caution(LocalizedMessage.of("discover.check.time.ok", "a", "b", 60)));
        assertEquals(String.join("\n", DiscoveryPresentation.idea(checked, english)), String.join("\n", view.lines()), "the shared text is unchanged");
    }

    @Test void listProblemsNameTheListToCorrect() {
        String thirteen = String.join("; ", Collections.nCopies(13, "x").stream().map(s -> s + UUID.randomUUID()).toList());
        assertEquals(List.of("avoid"), DiscoveryGuide.listInputs("", thirteen));
        assertEquals(List.of("interests"), DiscoveryGuide.listInputs(thirteen, "crowds"));
        assertEquals(List.of("interests", "avoid"), DiscoveryGuide.listInputs("a".repeat(101), thirteen));
        assertEquals(List.of(), DiscoveryGuide.listInputs("night; ferries", "crowds"));
        assertEquals(List.of(), DiscoveryGuide.listInputs(null, null));
        var values = DiscoveryForm.defaults(LocalDate.now());
        values.put("location", "Harbourfront"); values.put("from", "08:00"); values.put("until", "10:00"); values.put("distance", "5"); values.put("avoid", thirteen);
        assertEquals(List.of("discover.error.list"), DiscoveryForm.parse(values, null, Optional.empty()).messages().stream().map(LocalizedMessage::key).toList(),
            "the same rule the form applies");
    }

    @Test void presetInterestsReadAsTheirLabelsInEveryLanguage() {
        var chinese = LocalizationService.english(); chinese.useLanguage("zh-CN");
        assertEquals(List.of(chinese.text("discover.category.LANDSCAPE"), chinese.text("discover.category.AVIATION"), "瀑布"),
            DiscoveryPresentation.interests(List.of("landscape", "aviation", "瀑布"), chinese));
        assertFalse(DiscoveryPresentation.interests(List.of("landscape"), chinese).getFirst().matches(".*[A-Za-z].*"), "no English term in Chinese");
    }

    @Test void requestConditionsAreReadFromTheRequestItself() {
        var values = DiscoveryForm.defaults(LocalDate.now());
        values.put("location", "Harbourfront"); values.put("from", "22:00"); values.put("until", "01:30"); values.put("distance", "12.5");
        values.put("interests", DiscoveryInterests.compose(Set.of(Category.NIGHT), "ferries")); values.put("history", "no");
        var request = DiscoveryForm.parse(values, null, Optional.empty()).value().orElseThrow();
        var rows = DiscoveryPresentation.conditions(request, english);
        assertEquals(List.of("location", "date", "time", "distance", "interests", "history"), rows.stream().map(DiscoveryPresentation.Condition::key).toList());
        assertEquals("Harbourfront (name only, no coordinates)", rows.get(0).value());
        assertEquals("22:00–01:30 the next day (210 min)", rows.get(2).value());
        assertEquals("12.5 km straight-line", rows.get(3).value());
        assertEquals("Night photography; ferries", rows.get(4).value(), "a preset category reads as its label, custom entries as typed");
        assertEquals("Not used", rows.get(5).value());
        assertEquals(List.of("night", "ferries"), request.interests(), "the request itself keeps the fixed term");
    }
}
