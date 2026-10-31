package com.photopilot.calculation;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationCliTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void actualDispatcherHandlesCalculationsAndErrors() {
        try(var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            var bytes=new ByteArrayOutputStream();var cli=new PhotoPilotCLI(bootstrap.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes));
            assertEquals(0,cli.execute("F08"));
            assertEquals(0,cli.execute("calc exposure aperture=8 shutter=1/128 iso=400"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 ndStops=10"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 ndFactor=8"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 opticalDensity=3"));
            assertEquals(2,cli.execute("calc nd shutter=0 ndStops=1"));
            assertEquals(2,cli.execute("calc nd shutter=1 shutter=2"));
            assertEquals(2,cli.execute("calc unknown"));
            assertEquals(0,cli.execute("calc dof focalLength=50 aperture=8 coc=.03 distance=20"));
            assertEquals(0,cli.execute("calc hyperfocal focalLength=50 aperture=8 coc=.03"));
            assertEquals(0,cli.execute("calc fov focalLength=50 sensorWidth=36 sensorHeight=24"));
            assertEquals(0,cli.execute("calc storage shotCount=1000 fileSize=25 cardCapacity=16"));
            assertEquals(0,cli.execute("calc battery shotCount=1000 ratedShots=600"));
            String output=bytes.toString();assertTrue(output.contains("11 EV100"));assertTrue(output.contains("8.192 s"));assertTrue(output.contains("positive"));assertTrue(output.contains("Infinity m"));assertTrue(output.contains("10.466667 m"));assertTrue(output.contains("39.597753 degrees"));assertTrue(output.contains("25 GB"));assertTrue(output.contains("2 batteries"));
        }
    }
}
