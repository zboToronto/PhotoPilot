package com.photopilot.inventory;

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

class InventoryCliTest {
    @TempDir Path directory;
    @Test void commandsSharePersistentServiceAndHandleQuotedInputsAndErrors() {
        UUID id=UUID.randomUUID(); var bytes=new ByteArrayOutputStream();
        try (var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString()))) {
            var cli=new PhotoPilotCLI(bootstrap.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes,true,StandardCharsets.UTF_8));
            assertEquals(0,cli.execute("gear help"));
            assertEquals(0,cli.execute("gear add camera id="+id+" brand=\"Test Brand\" model=\"Test Camera\" mount=E quantity=2"));
            assertEquals(2,cli.execute("gear add camera id="+id+" brand=B model=M mount=E --confirm-duplicate"));
            assertEquals(0,cli.execute("gear list query=\"test camera\" type=camera"));
            assertEquals(0,cli.execute("gear edit "+id+" model=\"Updated Camera\" weightGrams=450 stabilization=false"));
            assertEquals("Updated Camera",bootstrap.facade().inventory().list("").value().orElseThrow().items().getFirst().model());
            assertEquals(0,cli.execute("gear edit "+id+" weightGrams=\"\""));
            assertTrue(bootstrap.facade().inventory().list("").value().orElseThrow().items().getFirst().weightGrams().isEmpty());
            for(String command:List.of("gear add", "gear add camera brand=B model=M mount=E quantity=0", "gear list type=unknown", "gear edit invalid", "gear list query=\"unclosed", "gear list unknown=value", "gear list type=camera type=lens", "gear remove "+id+" --confirm=false")) assertEquals(2,cli.execute(command),command);
            assertEquals(0,cli.execute("F05"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Test Brand Test Camera"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("mount*"));
        }
        try (var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString()))) {
            var cli=new PhotoPilotCLI(bootstrap.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes));
            assertEquals("Updated Camera",bootstrap.facade().inventory().list("").value().orElseThrow().items().getFirst().model());
            assertEquals(0,cli.execute("gear remove "+id));
            assertTrue(bootstrap.facade().inventory().list("").value().orElseThrow().items().isEmpty());
        }
    }
}
