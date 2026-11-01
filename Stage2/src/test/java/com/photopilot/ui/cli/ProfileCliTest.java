package com.photopilot.ui.cli;

import com.photopilot.application.ProfileManager;
import com.photopilot.repository.StorageScope;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProfileCliTest {
    @TempDir Path root;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    Map<String,String> env() { return Map.of("PHOTOPILOT_DATA_DIR",root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR",root.resolve("config").toString()); }
    PhotoPilotCLI cli(ProfileManager manager) { return new PhotoPilotCLI(manager,new Scanner(""),new PrintStream(output,true,StandardCharsets.UTF_8)); }
    @Test void actualDispatcherCrudUnicodeDuplicatesAndConfirmation() throws Exception {
        UUID a, b;
        try (var manager = new ProfileManager(env())) {
            var cli = cli(manager); a=manager.activeProfile().value().orElseThrow().id();
            assertEquals(0,cli.execute("profile create \"夜景 trip\""));
            b=manager.listProfiles().value().orElseThrow().stream().filter(p -> !p.id().equals(a)).findFirst().orElseThrow().id();
            assertEquals(0,cli.execute("profile create \"夜景 trip\""));
            assertEquals(3,manager.listProfiles().value().orElseThrow().size());
            assertEquals(0,cli.execute("profile list")); assertTrue(output.toString(StandardCharsets.UTF_8).contains(b.toString()));
            assertEquals(0,cli.execute("profile select " + b)); assertEquals(0,cli.execute("profile current"));
            assertEquals(0,cli.execute("session create title=CLI date=2026-11-01"));
            Path file=manager.activeContext().storageScope().sessionsDirectory().resolve("sessions.json"); byte[] saved=Files.readAllBytes(file);
            assertEquals(0,cli.execute("profile rename " + b + " \"Renamed 名称\""));
            assertEquals(b,manager.activeProfile().value().orElseThrow().id()); assertArrayEquals(saved,Files.readAllBytes(file));
            assertEquals(2,cli.execute("profile delete " + b + " --confirm"));
            assertEquals(2,cli.execute("profile delete " + a + " --confirm"));
            assertEquals(0,cli.execute("profile select " + a));
            assertTrue(manager.activeContext().facade().sessions().search("").value().orElseThrow().isEmpty());
            assertEquals(2,cli.execute("profile delete " + b)); assertTrue(manager.getProfile(b).isSuccess());
            assertEquals(0,cli.execute("profile delete " + b + " --confirm")); assertFalse(manager.getProfile(b).isSuccess());
            assertArrayEquals(saved,Files.readAllBytes(file));
        }
        try (var manager=new ProfileManager(env())) { assertEquals(a,manager.activeProfile().value().orElseThrow().id()); assertFalse(manager.getProfile(b).isSuccess()); }
    }
    @Test void malformedCommandsAndUnknownIdsDoNotMutateStorage() throws Exception {
        try (var manager=new ProfileManager(env())) {
            var cli=cli(manager); Path file=root.resolve("data/profiles.json"); byte[] before=Files.readAllBytes(file);
            for (String command:List.of("profile create", "profile create \"\"", "profile create \"bad", "profile create one two", "profile list extra",
                    "profile current extra", "profile select 1-1-1-1-1", "profile select ../../bad", "profile select " + UUID.randomUUID(),
                    "profile rename not-a-uuid x", "profile rename " + UUID.randomUUID() + " name", "profile delete " + UUID.randomUUID() + " --force", "profile nope"))
                assertEquals(2,cli.execute(command),command);
            assertArrayEquals(before,Files.readAllBytes(file));
            assertEquals(0,cli.execute("profile help")); assertTrue(output.toString().contains("--confirm"));
        }
    }
    @Test void failedSelectionKeepsFacadeAndCorruptFilesUntouched() throws Exception {
        try (var manager=new ProfileManager(env())) {
            var cli=cli(manager); var context=manager.activeContext(); var b=manager.createProfile("B").value().orElseThrow();
            Path activeFile=context.storageScope().inventoryFile(); Files.writeString(activeFile,"broken active");
            assertEquals(2,cli.execute("profile select " + manager.activeProfile().value().orElseThrow().id()));
            assertSame(context,manager.activeContext()); assertEquals("broken active",Files.readString(activeFile)); Files.delete(activeFile);
            Path broken=StorageScope.forProfile(root.resolve("data"),b.id()).inventoryFile(); Files.writeString(broken,"broken");
            assertEquals(2,cli.execute("profile select " + b.id())); assertSame(context,manager.activeContext());
            assertEquals(0,cli.execute("session create title=Kept date=2026-11-01"));
            assertEquals(1,context.facade().sessions().search("").value().orElseThrow().size()); assertEquals("broken",Files.readString(broken));
            Path registry=root.resolve("data/profiles.json"); Files.writeString(registry,"broken registry");
            for(String command:List.of("profile list","profile create Test","profile rename " + b.id() + " Test","profile delete " + b.id() + " --confirm"))
                assertEquals(2,cli.execute(command));
            assertEquals("broken registry",Files.readString(registry));
        }
    }
    @Test void optionsAbortBeforeBusinessCommandsAndLanguageOverrideSurvivesSelection() {
        try (var manager=new ProfileManager(env())) {
            UUID b=manager.createProfile("B").value().orElseThrow().id();
            for(String[] options:List.of(new String[]{"--profile"}, new String[]{"--profile=bad"}, new String[]{"--profile="+UUID.randomUUID()},
                    new String[]{"--profile="+b,"--profile="+b})) {
                var args=new ArrayList<>(List.of(options)); args.add("session create title=Wrong date=2026-11-01");
                var cli=cli(manager); cli.run(args.toArray(String[]::new)); assertEquals(2,cli.exitCode());
                assertTrue(manager.activeContext().facade().sessions().search("").value().orElseThrow().isEmpty());
            }
            var cli=cli(manager); cli.run(new String[]{"--lang=zh-CN","--profile",b.toString(),"profile current","session create title=正确 date=2026-11-01"});
            assertEquals(0,cli.exitCode()); assertEquals("zh-CN",manager.activeContext().localization().language().tag());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("（当前）"));
        }
        try (var manager=new ProfileManager(env())) { assertEquals("en",manager.activeContext().localization().language().tag()); }
    }
    @Test void realJvmRestartSelectionExitCodesAndDefaultIsolation() throws Exception {
        UUID a,b;
        assertTrue(process(0,"profile create \"Process B\"").contains("Process B"));
        try(var manager=new ProfileManager(env())) {
            a=manager.activeProfile().value().orElseThrow().id(); b=manager.listProfiles().value().orElseThrow().stream().filter(p -> !p.id().equals(a)).findFirst().orElseThrow().id();
        }
        process(0,"--profile",b.toString(),"session create title=ProcessRecord date=2026-11-01");
        assertTrue(process(0,"--profile="+b,"session list").contains("ProcessRecord"));
        String defaultOutput=process(0,"profile current","session list"); assertTrue(defaultOutput.contains(a.toString())); assertFalse(defaultOutput.contains("ProcessRecord"));
        process(2,"--profile=bad","session create title=Never date=2026-11-01");
        process(2,"profile delete " + b); process(2,"profile select " + UUID.randomUUID());
        process(0,"profile delete " + b + " --confirm"); process(2,"--profile="+b,"session list");
    }
    @Test void failedBatchSelectionStopsBeforeWritingToPreviousProfile() throws Exception {
        try (var manager = new ProfileManager(env())) {
            var cli = cli(manager);
            cli.run(new String[]{"profile select " + UUID.randomUUID(), "session create title=Wrong date=2026-11-01"});
            assertEquals(2, cli.exitCode());
            assertTrue(manager.activeContext().facade().sessions().search("").value().orElseThrow().isEmpty());
        }
        process(2, "profile select " + UUID.randomUUID(), "session create title=WrongPipe date=2026-11-01");
        try (var manager = new ProfileManager(env())) {
            assertTrue(manager.activeContext().facade().sessions().search("").value().orElseThrow().isEmpty());
        }
    }
    @Test void explicitStartupOpensHealthyProfileDespiteCorruptDefault() throws Exception {
        UUID b; Path broken;
        try (var manager = new ProfileManager(env())) {
            b = manager.createProfile("Healthy B").value().orElseThrow().id();
            broken = manager.activeContext().storageScope().inventoryFile();
        }
        Files.writeString(broken, "broken default");
        byte[] registry = Files.readAllBytes(root.resolve("data/profiles.json"));
        process(2, "session list");
        process(0, "--profile=" + b, "session create title=Healthy date=2026-11-01");
        assertTrue(process(0, "--profile", b.toString(), "session list").contains("Healthy"));
        assertEquals("broken default", Files.readString(broken));
        assertArrayEquals(registry, Files.readAllBytes(root.resolve("data/profiles.json")));
    }
    @Test void realJvmCorruptStartupDoesNotResetRegistry() throws Exception {
        process(0,"profile list"); Path registry=root.resolve("data/profiles.json"); Files.writeString(registry,"broken");
        String failure=process(2,"profile list"); assertFalse(failure.contains("Exception in thread")); assertEquals("broken",Files.readString(registry));
    }
    private String process(int expected,String... args) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),PhotoPilotCLI.class.getName(),"--lang=en"));
        var lines=new ArrayList<String>();
        for(int i=0;i<args.length;i++) { if(args[i].startsWith("--")) { command.add(args[i]); if(args[i].equals("--profile"))command.add(args[++i]); } else lines.add(args[i]); }
        Path log=Files.createTempFile(root,"cli-",".log");
        var builder=new ProcessBuilder(command); builder.environment().putAll(env()); builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process child=builder.start(); try(var writer=child.outputWriter(StandardCharsets.UTF_8)) { writer.write(String.join("\n",lines)+"\nexit\n"); }
        try { assertTrue(child.waitFor(20,TimeUnit.SECONDS)); String text=Files.readString(log); assertEquals(expected,child.exitValue(),text); return text; }
        finally { if(child.isAlive())child.destroyForcibly(); }
    }
}
