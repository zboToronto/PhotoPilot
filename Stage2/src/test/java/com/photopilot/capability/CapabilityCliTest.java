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
}
