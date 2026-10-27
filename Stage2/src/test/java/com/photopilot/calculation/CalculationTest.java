package com.photopilot.calculation;

import com.photopilot.application.CalculationForm;
import com.photopilot.model.*;
import com.photopilot.service.TechnicalCalculator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationTest {
    final TechnicalCalculator service=new TechnicalCalculator();
    CalculationResult calculate(CalculationForm.Tool tool, Map<String,String> fields) {
        var parsed=CalculationForm.parse(tool,fields);assertTrue(parsed.isSuccess(),parsed.errors().toString());
        var result=service.calculate(parsed.value().orElseThrow());assertTrue(result.isSuccess(),result.errors().toString());return result.value().orElseThrow();
    }
    @Test void exposureAtIso100And400() {
        var fields=new HashMap<>(Map.of("aperture","8","shutter","1/128","iso","100"));
        var result=calculate(CalculationForm.Tool.EXPOSURE,fields);
        assertEquals(13,result.values().get("Camera setting EV"),1e-12);
        assertEquals(13,result.values().get("ISO-normalized EV100"),1e-12);
        fields.put("iso","400");result=calculate(CalculationForm.Tool.EXPOSURE,fields);
        assertEquals(13,result.values().get("Camera setting EV"),1e-12);
        assertEquals(11,result.values().get("ISO-normalized EV100"),1e-12);
        assertTrue(result.assumptions().getFirst().contains("not exposure compensation"));
    }
    @Test void ndKnownAnswerAndThreeConventions() {
        var result=calculate(CalculationForm.Tool.ND,Map.of("shutter","1/125","ndStops","10"));
        assertEquals(8.192,result.values().get("Adjusted shutter"),1e-12);
        assertTrue(CalculationForm.format(result).contains("8.192 s"));
        assertEquals(.064,calculate(CalculationForm.Tool.ND,Map.of("shutter","1/125","ndFactor","8")).values().get("Adjusted shutter"),1e-12);
        assertEquals(8,calculate(CalculationForm.Tool.ND,Map.of("shutter","1/125","opticalDensity","3")).values().get("Adjusted shutter"),1e-12);
        assertEquals(.008,calculate(CalculationForm.Tool.ND,Map.of("shutter",".008","ndStops","0")).values().get("Adjusted shutter"),1e-12);
        assertEquals(Math.sqrt(2),calculate(CalculationForm.Tool.ND,Map.of("shutter","1","ndStops",".5")).values().get("Adjusted shutter"),1e-12);
    }
    @Test void highStopsAndOverflow() {
        assertEquals(Math.pow(2,30),calculate(CalculationForm.Tool.ND,Map.of("shutter","1","ndStops","30")).values().get("Adjusted shutter"),.0001);
        assertTrue(calculate(CalculationForm.Tool.ND,Map.of("shutter","1e-300","ndStops","1100")).values().get("Adjusted shutter")>1e30);
        var input=CalculationForm.parse(CalculationForm.Tool.ND,Map.of("shutter","1","ndStops","2000")).value().orElseThrow();
        assertFalse(service.calculate(input).isSuccess());
    }
    @Test void rejectsInvalidMissingConflictingAndUnknownInputs() {
        for (String key:List.of("aperture","shutter","iso")) for (String bad:List.of("0","-1","NaN","Infinity","","word")) {
            var fields=new HashMap<>(Map.of("aperture","8","shutter","1/125","iso","100"));fields.put(key,bad);
            assertFalse(CalculationForm.parse(CalculationForm.Tool.EXPOSURE,fields).isSuccess(),key+"="+bad);
        }
        for (String bad:List.of("1/0","-1/-125","1/Infinity","1/2/3","1e-300/1e300"))
            assertFalse(CalculationForm.parse(CalculationForm.Tool.ND,Map.of("shutter",bad,"ndStops","1")).isSuccess());
        for (var fields:List.of(Map.of("shutter","1"),Map.of("shutter","1","ndStops","-1"),Map.of("shutter","1","ndFactor",".5"),Map.of("shutter","1","ndStops","1","ndFactor","2"),Map.of("shutter","1","typo","2")))
            assertFalse(CalculationForm.parse(CalculationForm.Tool.ND,fields).isSuccess());
        assertFalse(service.calculate(null).isSuccess());
    }
    @Test void unitConversionAndInputEvidence() {
        var input=new CalculationInput(CalculationKind.EXPOSURE_ND,Map.of("shutter",8.,"ndStops",10.),Map.of("shutter","ms","ndStops","stops"));
        var result=service.exposureAndND(input).value().orElseThrow();assertEquals(8.192,result.values().get("Adjusted shutter"),1e-12);assertSame(input,result.input());
        assertFalse(new CalculationInput(input.operation(),input.parameters(),Map.of("shutter","minutes","ndStops","stops")).validate().isSuccess());
        assertFalse(new CalculationInput(input.operation(),input.parameters(),Map.of()).validate().isSuccess());
        assertFalse(service.hyperfocal(input).isSuccess());
        assertThrows(UnsupportedOperationException.class,() -> result.values().put("fake",1.));
    }
    @Test void formattingRetainsSmallNumbersAndExplicitInfinity() {
        assertEquals("1.234568",CalculationForm.formatNumber(1.2345678));
        assertEquals("1E-9",CalculationForm.formatNumber(1e-9));
        assertEquals("Infinity",CalculationForm.formatNumber(Double.POSITIVE_INFINITY));
    }
}
