package com.photopilot.capability;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityCliTest {
    @TempDir Path directory;
    @Test void cliAnalyzesActualF05EntriesAndHandlesInvalidCommandsAndLanguage() {
        try(var app=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString(),"PHOTOPILOT_CONFIG_DIR",directory.resolve("config").toString()))) {
            var bytes=new ByteArrayOutputStream(); var cli=new PhotoPilotCLI(app.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes,true,StandardCharsets.UTF_8),app.localization());
            assertEquals(0,cli.execute("F06"));assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("No equipment yet")); bytes.reset();
            assertTrue(app.facade().inventory().add(GearCapabilityTest.lens(20,70,2.8,4.),false).isSuccess());
            for(String action:List.of("all","overview","coverage","aperture","compatibility","summary","help")) assertEquals(0,cli.execute("capability "+action));
            String text=bytes.toString(StandardCharsets.UTF_8);assertTrue(text.contains("20–70 mm"));assertTrue(text.contains("f/2.8"));assertTrue(text.contains("f/4"));
            assertEquals(2,cli.execute("capability bogus"));assertEquals(2,cli.execute("capability all extra"));
            bytes.reset();app.localization().useLanguage("zh-CN");assertEquals(0,cli.execute("capability coverage"));assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("覆盖区间"));
            assertFalse(app.facade().analyzeNeeds(UUID.randomUUID()).isSuccess(),"UC06 analysis needs an existing session; it is never replaced by inventory analysis");
        }
    }

    /** The structured terminal report renders the same analysis the GUI renders: same findings, same pair rows. */
    @Test void cliSectionsShareTheAnalysisWithTheDashboard() {
        try(var app=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString(),"PHOTOPILOT_CONFIG_DIR",directory.resolve("config").toString()))) {
            for (var item : CapabilityDemoInventory.items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            var bytes=new ByteArrayOutputStream(); var cli=new PhotoPilotCLI(app.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes,true,StandardCharsets.UTF_8),app.localization());
            var report=app.facade().capabilities().analyze().value().orElseThrow(); var l=app.localization();
            assertEquals(0,cli.execute("capability"));
            String summary=bytes.toString(StandardCharsets.UTF_8); bytes.reset();
            assertTrue(summary.contains("GEAR CAPABILITY REPORT"), summary);
            for (var finding : report.findings()) {
                String text = "[" + com.photopilot.application.CapabilityPresentation.level(finding.level(), l) + "] " + com.photopilot.application.CapabilityPresentation.finding(finding, report, l);
                assertTrue(summary.replaceAll("\\s+", " ").contains(text), text);
            }
            assertTrue(summary.contains("[INFO] Continuous coverage: 20–400 mm"));
            assertTrue(summary.contains("[WARN] Coverage gap between 16 mm and 20 mm"));
            assertFalse(summary.contains(CapabilityDemoInventory.A7V.toString()), "identifiers stay out of the summary");
            for (String section : List.of("summary","overview","cameras","lenses","coverage","coverage 1","aperture","compatibility","matrix","matrix 2","pair 5 2","quality","all","technical"))
                assertEquals(0,cli.execute("capability "+section),section);
            String all=bytes.toString(StandardCharsets.UTF_8); bytes.reset();
            for (String expected : List.of("33 MP","10 fps","35.9 × 23.9 mm","f/4.5–6.3","20.1–70.2 mm","Not recorded","Possible alias","Equivalent labels",
                    "Mount matching alone does not establish complete camera–lens compatibility.","gear edit "+CapabilityDemoInventory.A7V,"Sony E: recorded as \"E\" × 9, \"Sony E\" × 1"))
                assertTrue(all.contains(expected), expected);
            // capability pair <lens> <camera> lists exactly the GUI detail rows of that combination.
            assertEquals(0,cli.execute("capability pair 5 2"));
            String pair=bytes.toString(StandardCharsets.UTF_8).replaceAll("\\s+"," "); bytes.reset();
            var tamron=report.lenses().get(4); var a7v=report.cameras().get(1);
            assertEquals(CapabilityDemoInventory.T50400, tamron.lens().id()); assertEquals(CapabilityDemoInventory.A7V, a7v.camera().id());
            var rows=com.photopilot.application.CapabilityPresentation.pairDetails(report.pair(a7v.camera().id(), tamron.lens().id()).orElseThrow(), tamron,
                com.photopilot.application.CapabilityPresentation.names(report.equipment()), l);
            rows.forEach((key, value) -> { assertTrue(pair.contains(key), key); assertTrue(pair.contains(value.replaceAll("\\s+"," ")), value); });
            // Invalid numbers explain the valid range; nothing is printed as if it existed.
            assertEquals(2,cli.execute("capability pair 9 1")); assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("No lens 9")); bytes.reset();
            assertEquals(2,cli.execute("capability coverage 7")); assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("No camera 7")); bytes.reset();
            assertEquals(2,cli.execute("capability pair 1")); assertEquals(2,cli.execute("capability matrix 1 2")); bytes.reset();
            assertEquals(0,cli.execute("F06")); assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("GEAR CAPABILITY REPORT")); bytes.reset();
            l.useLanguage("zh-CN");
            assertEquals(0,cli.execute("capability"));
            String chinese=bytes.toString(StandardCharsets.UTF_8);
            assertTrue(chinese.contains("[信息] 连续覆盖：20–400 mm"), chinese);
            assertTrue(chinese.contains("[注意] 覆盖缺口：16 mm 与 20 mm 之间"), chinese);
        }
    }
}
