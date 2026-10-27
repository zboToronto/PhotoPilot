package com.photopilot.calculation;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationCliTest {
    @Test void actualDispatcherHandlesCalculationsAndErrors() {
        try(var bootstrap=new ApplicationBootstrap(Map.of())) {
            var bytes=new ByteArrayOutputStream();var cli=new PhotoPilotCLI(bootstrap.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes));
            assertEquals(0,cli.execute("F08"));
            assertEquals(0,cli.execute("calc exposure aperture=8 shutter=1/128 iso=400"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 ndStops=10"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 ndFactor=8"));
            assertEquals(0,cli.execute("calc nd shutter=1/125 opticalDensity=3"));
            assertEquals(2,cli.execute("calc nd shutter=0 ndStops=1"));
            assertEquals(2,cli.execute("calc nd shutter=1 shutter=2"));
            assertEquals(2,cli.execute("calc unknown"));
            String output=bytes.toString();assertTrue(output.contains("11 EV100"));assertTrue(output.contains("8.192 s"));assertTrue(output.contains("positive"));
        }
    }
}
