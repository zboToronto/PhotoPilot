package com.photopilot.calculation;

import com.photopilot.agent.CalculationTool;
import com.photopilot.service.TechnicalCalculator;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationToolTest {
    final CalculationTool tool=new CalculationTool(new TechnicalCalculator());
    final String nd="{\"operation\":\"EXPOSURE_ND\",\"parameters\":{\"shutter\":0.008,\"ndStops\":10},\"units\":{\"shutter\":\"s\",\"ndStops\":\"stops\"}}";
    @Test void schemaAndRealEvidence() {
        assertTrue(JsonParser.parseString(tool.inputSchema()).isJsonObject());
        var result=tool.execute(nd);assertTrue(result.isSuccess(),result.errors().toString());
        var json=JsonParser.parseString(result.value().orElseThrow()).getAsJsonObject();
        assertEquals(8.192,json.getAsJsonObject("values").get("Adjusted shutter").getAsDouble(),1e-12);
        assertTrue(json.has("input"));assertTrue(json.has("assumptions"));
    }
    @Test void rejectsMalformedDuplicateWrongTypeUnsupportedAndOversizeJson() {
        for(String bad:new String[]{"{}", "null", "[]", nd+"{}", nd.replace("0.008","\"0.008\""),nd.replace("0.008","NaN"),nd.replace("0.008","1e999"),nd.replace("\"ndStops\":10","\"ndStops\":10,\"ndStops\":2"),nd.replace("\"units\":","\"typo\":")," ".repeat(16385),nd.replace("\"operation\":","\"operation\":\"STORAGE\",\"operation\":")})
            assertFalse(tool.execute(bad).isSuccess(),bad.substring(0,Math.min(100,bad.length())));
        assertFalse(tool.execute(null).isSuccess());
    }
    @Test void infinitySerializesAsExplicitStringNotInvalidJsonNumber() {
        var result=tool.execute("""
            {"operation":"DEPTH_OF_FIELD","parameters":{"focalLength":50,"aperture":8,"coc":0.03,"distance":20},"units":{"focalLength":"mm","aperture":"f-number","coc":"mm","distance":"m"}}
            """);assertTrue(result.isSuccess(),result.errors().toString());
        var json=JsonParser.parseString(result.value().orElseThrow()).getAsJsonObject();
        assertEquals("Infinity",json.getAsJsonObject("values").get("Far focus limit").getAsString());
    }
}
