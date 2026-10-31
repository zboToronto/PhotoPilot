package com.photopilot;

import com.photopilot.agent.*;
import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.common.Result;
import com.photopilot.config.*;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.*;
import com.photopilot.service.*;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {
    @TempDir Path temporary;

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @Test void compositionSharesDependenciesAndDoesNotCreateUserData() throws Exception {
        Path data = temporary.resolve("no-user-data");
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", data.toString(), "PHOTOPILOT_CONFIG_DIR", data.resolve("config").toString()))) {
            PhotoPilotFacade facade = bootstrap.facade();
            assertSame(facade, bootstrap.facade());
            Object planning = facade.planning();
            Object repository = field(facade.sessions(), "repository");
            assertSame(repository, field(planning, "sessions"));
            assertSame(repository, field(facade.reviews(), "sessions"));
            assertSame(repository, field(facade.inventory(), "sessions"));
            assertSame(field(facade, "calculations"), field(planning, "calculator"));
            Object agent = field(planning, "agent");
            Object registry = field(agent, "tools");
            Map<?, ?> tools = (Map<?, ?>) field(registry, "allowedTools");
            assertEquals(4, tools.size());
            assertSame(field(planning, "matching"), field(tools.get("gearMatching"), "matching"));
            assertSame(field(planning, "environment"), field(tools.get("environment"), "environment"));
            assertSame(agent, field(field(planning, "matching"), "ranking") instanceof com.photopilot.strategy.PortabilityRanking ranking
                ? field(ranking, "agent") : null);
            assertFalse(Files.exists(data));
        }
    }

    @Test void facadeAndAdaptersNeverPretendFeaturesAreImplemented() throws Exception {
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", temporary.toString(), "PHOTOPILOT_CONFIG_DIR", temporary.resolve("config").toString()))) {
            PhotoPilotFacade facade = bootstrap.facade();
            Result<PhotographySession> created = facade.createSession("bootstrap test fixture", LocalDate.of(2026, 1, 1));
            assertTrue(created.isSuccess(), created.errors().toString());
            assertEquals("DRAFT", created.value().orElseThrow().status());
            assertFalse(facade.generatePlan(UUID.randomUUID()).isSuccess());
            assertFalse(facade.calculate(null).isSuccess());
            LLMClient model = (LLMClient) field(field(facade.planning(), "agent"), "model");
            assertFalse(model.complete(null).isSuccess());
            assertTrue(facade.sessions().search("anything").isSuccess());
        }
    }

    @Test void cliHandlesHelpStatusEveryUnavailableFeatureUnknownInputAndExit() {
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", temporary.toString(), "PHOTOPILOT_CONFIG_DIR", temporary.resolve("config").toString()))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PhotoPilotCLI cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(),
                new Scanner("help\nstatus\nf01\nunknown\nexit\nf02\n"), new PrintStream(bytes, true, StandardCharsets.UTF_8));
            cli.run(new String[0]);
            String output = bytes.toString(StandardCharsets.UTF_8);
            for (int i = 1; i <= 11; i++) assertTrue(output.contains("F%02d ".formatted(i)));
            assertTrue(output.contains("0 session(s)"));
            assertTrue(output.contains("Unknown command"));
            assertTrue(output.endsWith("PhotoPilot closed." + System.lineSeparator()));
            assertFalse(output.contains("Not implemented: F02"));
            for (int i = 1; i <= 11; i++) assertEquals(i == 1 || i == 5 || i == 8 ? 0 : 2, cli.execute("F%02d".formatted(i)));
            assertEquals(11, FeatureCatalog.lines().size());
            assertEquals(1, cli.execute("exit"));
        }
    }

    @Test void configuredStrategyIsReplaceableAndDuplicateToolsAreRejected() throws Exception {
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_RANKING", "coverage", "PHOTOPILOT_DATA_DIR", temporary.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", temporary.resolve("config").toString()))) {
            GearMatchingService matching = (GearMatchingService) field(bootstrap.facade().planning(), "matching");
            assertInstanceOf(com.photopilot.strategy.CoverageRanking.class, field(matching, "ranking"));
            var strategy = new com.photopilot.strategy.PortabilityRanking((PhotoPilotAgent) field(bootstrap.facade().planning(), "agent"));
            matching.setRanking(strategy);
            assertSame(strategy, field(matching, "ranking"));
            ToolRegistry registry = new ToolRegistry(Map.of());
            registry.register(new CalculationTool(new TechnicalCalculator()));
            assertThrows(IllegalArgumentException.class, () -> registry.register(new CalculationTool(new TechnicalCalculator())));
        }
        assertThrows(IllegalArgumentException.class, () -> new ApplicationBootstrap(Map.of("PHOTOPILOT_RANKING", "invalid", "PHOTOPILOT_DATA_DIR", temporary.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", temporary.resolve("config").toString())));
    }
}
