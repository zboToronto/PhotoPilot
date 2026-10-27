package com.photopilot.calculation;

import com.photopilot.application.*;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CalculationInventoryTest {
    @TempDir Path directory;
    @Test void optionalGearPrefillsOnlyKnownRelevantValuesAndNeverMutatesInventory() throws Exception {
        try(var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString()))) {
            var filter=EquipmentForm.parse(EquipmentType.FILTER,UUID.randomUUID(),Map.of("brand","Test","model","ND","diameterMm","77","ndStops","10"),null).value().orElseThrow();
            assertTrue(bootstrap.facade().inventory().add(filter,false).isSuccess());
            var stored=Files.readAllBytes(directory.resolve("inventory.json"));
            var bytes=new ByteArrayOutputStream();var cli=new PhotoPilotCLI(bootstrap.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(bytes));
            assertEquals(0,cli.execute("calc nd gear="+filter.id()+" shutter=1/125"));assertTrue(bytes.toString().contains("8.192 s"));
            bytes.reset();assertEquals(0,cli.execute("calc nd gear="+filter.id()+" shutter=1/125 ndStops=0"));assertTrue(bytes.toString().contains("0.008 s"));
            bytes.reset();assertEquals(0,cli.execute("calc nd gear="+filter.id()+" shutter=1/125 ndFactor=8"));assertTrue(bytes.toString().contains("0.064 s"));
            assertEquals(2,cli.execute("calc nd gear="+UUID.randomUUID()+" shutter=1/125"));
            assertArrayEquals(stored,Files.readAllBytes(directory.resolve("inventory.json")));
            var zoom=EquipmentForm.parse(EquipmentType.LENS,UUID.randomUUID(),Map.of("brand","Test","model","Zoom","mount","E","minFocalMm","24","maxFocalMm","70","maxAperture","2.8"),null).value().orElseThrow();
            assertTrue(CalculationForm.fromGear(CalculationForm.Tool.DOF,zoom).isEmpty());
            var prime=EquipmentForm.parse(EquipmentType.LENS,UUID.randomUUID(),Map.of("brand","Test","model","Prime","mount","E","minFocalMm","50","maxFocalMm","50","maxAperture","1.8"),null).value().orElseThrow();
            assertEquals(Map.of("focalLength","50.0"),CalculationForm.fromGear(CalculationForm.Tool.DOF,prime));
            var battery=EquipmentForm.parse(EquipmentType.BATTERY,UUID.randomUUID(),Map.of("brand","Test","model","Battery","batteryType","X","capacityMah","2000"),null).value().orElseThrow();
            assertTrue(CalculationForm.fromGear(CalculationForm.Tool.BATTERY,battery).isEmpty());
        }
    }
}
