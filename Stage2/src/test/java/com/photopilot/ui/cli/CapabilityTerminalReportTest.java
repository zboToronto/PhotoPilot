package com.photopilot.ui.cli;

import com.photopilot.application.FocalScale;
import com.photopilot.capability.CapabilityDemoInventory;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Terminal layout: fits the width without truncating names, ASCII marks on the shared log scale, narrow fallback. */
class CapabilityTerminalReportTest {
    @TempDir Path directory;
    ApplicationBootstrap app;
    GearCapabilityAnalysis report;
    @BeforeEach void setup() {
        app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()));
        var items = new ArrayList<Equipment>(CapabilityDemoInventory.items());
        items.add(CapabilityDemoInventory.lens(UUID.randomUUID(), "Fixture", "Extraordinarily Long Lens Name 100-500mm F4.5-7.1 Sports Edition", "E", 100, 500, 4.5, 7.1, true, true, 95.));
        report = app.facade().capabilities().analyzeItems(items).value().orElseThrow();
    }
    @AfterEach void close() { app.close(); }
    static List<String> all(CapabilityTerminalReport r) {
        var lines = new ArrayList<String>();
        lines.addAll(r.summary()); lines.addAll(r.overview()); lines.addAll(r.cameras()); lines.addAll(r.lenses()); lines.addAll(r.coverage(Optional.empty()));
        lines.addAll(r.matrix(Optional.empty())); lines.addAll(r.pair(4, 1)); lines.addAll(r.quality());
        return lines;
    }

    @Test void everySectionFitsTheWidthAndNeverTruncatesNames() {
        for (int width : List.of(40, 60, 80, 120)) {
            var r = new CapabilityTerminalReport(report, LocalizationService.english(), width);
            var lines = all(r);
            // Unbreakable tokens (an identifier in a review command) may exceed a very narrow width; nothing else does.
            for (String line : lines) if (!line.contains("gear edit")) assertTrue(TextTable.width(line) <= width, width + ": " + line);
            String text = String.join(" ", lines).replaceAll("\\s+", " ");
            for (String word : "Extraordinarily Long Lens Name 100-500mm F4.5-7.1 Sports Edition".split(" ")) assertTrue(text.contains(word), width + " lost " + word);
        }
    }

    @Test void chartPlacesMarksOnTheSharedLogarithmicScale() {
        var r = new CapabilityTerminalReport(report, LocalizationService.english(), 100);
        var lines = r.coverage(Optional.empty());
        String prime = lines.stream().filter(line -> line.startsWith("Viltrox AF 16mm F1.8 FE")).findFirst().orElseThrow();
        String zoom = lines.stream().filter(line -> line.startsWith("Sony FE 20-70mm F4 G")).findFirst().orElseThrow();
        // Labels take 100 / 3 = 33 columns plus a two-column gap; the plot's 65 columns map the same scale as the GUI.
        var scale = FocalScale.of(report.coverage()).orElseThrow();
        int origin = 35, last = 100 - origin - 1;
        int mark = prime.indexOf('o', origin);
        assertEquals(origin + Math.round(scale.fraction(16) * last), mark);
        assertEquals(origin + Math.round(scale.fraction(20) * last), zoom.indexOf('['));
        assertEquals(origin + Math.round(scale.fraction(70) * last), zoom.indexOf(']'));
        assertEquals("o", prime.substring(origin).strip(), "a prime is one point, not a bar");
        String combined = lines.stream().filter(line -> line.startsWith("All lenses combined")).findFirst().orElseThrow();
        assertEquals(mark, combined.indexOf('o', origin), "the isolated 16 mm in the combined row");
        assertEquals('.', combined.charAt(mark + 1), "the gap after it");
        assertEquals(zoom.indexOf('['), combined.indexOf('#'), "continuous coverage starts at 20 mm");
        assertTrue(String.join("\n", lines).contains("Covered intervals: 16 mm; 20–500 mm"));
        assertTrue(String.join("\n", lines).contains("between 16 mm and 20 mm"));
        // Too narrow for a readable chart: the intervals alone, nothing misleading.
        var narrow = new CapabilityTerminalReport(report, LocalizationService.english(), 40).coverage(Optional.empty());
        assertTrue(String.join(" ", narrow).replaceAll("\\s+", " ").contains("The terminal is too narrow for the chart; the intervals are listed below."), String.join("\n", narrow));
        for (String line : narrow) assertTrue(TextTable.width(line) <= 40, line);
        assertTrue(String.join(" ", narrow).contains("16 mm"));
    }

    @Test void matrixUsesCameraColumnsAndBlocksForNarrowTerminals() {
        var r = new CapabilityTerminalReport(report, LocalizationService.english(), 50);
        var lines = r.matrix(Optional.empty());
        assertTrue(lines.stream().anyMatch(line -> line.contains("C1") && line.contains("Sony A7 IV")));
        assertTrue(lines.stream().anyMatch(line -> line.matches(".*\\bM\\b.*\\?.*\\bM\\b.*")), String.join("\n", lines));
        var single = r.matrix(Optional.of(1));
        assertTrue(single.stream().noneMatch(line -> line.contains("C3")), "a camera number shows that column only");
        var chinese = LocalizationService.english(); chinese.useLanguage("zh-CN");
        for (String line : new CapabilityTerminalReport(report, chinese, 60).cameras()) assertTrue(TextTable.width(line) <= 60, line);
    }

    @Test void tableWrapsCellsAndCountsWideCharacters() {
        assertEquals(4, TextTable.width("机身"));
        var table = new TextTable(TextTable.Column.number("#"), TextTable.Column.text("Name", 6), TextTable.Column.fixed("Qty"));
        table.row("1", "A rather long equipment name", "2").row("2", "镜头名称很长很长", "1");
        var lines = table.render(20);
        for (String line : lines) assertTrue(TextTable.width(line) <= 20, line);
        String joined = String.join(" ", lines);
        for (String word : List.of("rather", "long", "equipment", "name", "镜头名称")) assertTrue(joined.contains(word), word);
        // Too narrow for the columns: the stacked layout keeps every value.
        var stacked = table.render(8);
        for (String line : stacked) assertTrue(TextTable.width(line) <= 8, line);
        assertTrue(String.join(" ", stacked).contains("Qty:"));
        assertEquals(List.of("abc", "def", "g"), TextTable.wrap("abcdefg", 3));
        assertEquals(80, CapabilityTerminalReport.width(Map.of()));
        assertEquals(132, CapabilityTerminalReport.width(Map.of("COLUMNS", "132")));
        assertEquals(40, CapabilityTerminalReport.width(Map.of("COLUMNS", "10")));
    }
}
