package com.photopilot.agent;

import java.util.*;
import java.io.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.service.TechnicalCalculator;

/** Stage 1 UML element 94: bounded JSON adapter over deterministic F08. No model call. */
public class CalculationTool implements AgentTool {
    private final TechnicalCalculator calculator;
    public CalculationTool(TechnicalCalculator calculator) { this.calculator=Objects.requireNonNull(calculator); }
    public String name() { return "calculation"; }
    public String inputSchema() {
        return """
            {"type":"object","additionalProperties":false,"required":["operation","parameters","units"],
             "properties":{"operation":{"type":"string","enum":["DEPTH_OF_FIELD","HYPERFOCAL","FIELD_OF_VIEW","EXPOSURE_ND","STORAGE","BATTERY_CAPACITY"]},
             "parameters":{"type":"object","maxProperties":6,"additionalProperties":{"type":"number"},
             "description":"Required keys by operation: DEPTH_OF_FIELD=focalLength,aperture,coc,distance; HYPERFOCAL=focalLength,aperture,coc; FIELD_OF_VIEW=focalLength,sensorWidth,sensorHeight; EXPOSURE_ND=shutter plus aperture+iso and/or exactly one ndStops/ndFactor/opticalDensity; STORAGE=shotCount,fileSize,cardCapacity; BATTERY_CAPACITY=shotCount,ratedShots. Positive finite values; ND stops/OD may be zero; ndFactor>=1; shot counts are integers."},
             "units":{"type":"object","maxProperties":6,"additionalProperties":{"type":"string"},
             "description":"Exact same keys as parameters. Optical lengths mm/cm/m; distance m/mm/ft; aperture f-number; shutter s/ms; iso ISO; ndStops stops; ndFactor factor; opticalDensity OD; counts shots; fileSize MB/MiB; cardCapacity GB/GiB."}}}
            """;
    }
    public Result<String> execute(String argumentsJson) {
        if (argumentsJson==null || argumentsJson.length()>16384) return Result.failure("Calculation JSON is required and limited to 16384 characters.");
        try (JsonReader reader=new JsonReader(new StringReader(argumentsJson))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();Set<String> keys=new HashSet<>();String operation=null;
            Map<String,Double> parameters=new LinkedHashMap<>();Map<String,String> units=new LinkedHashMap<>();
            while(reader.hasNext()) {
                String key=reader.nextName();if(!keys.add(key)) throw new IllegalArgumentException("Duplicate key: "+key);
                switch(key) {
                    case "operation" -> { require(reader,JsonToken.STRING);operation=reader.nextString(); }
                    case "parameters" -> {
                        reader.beginObject();
                        while(reader.hasNext()) { String name=reader.nextName();require(reader,JsonToken.NUMBER);if(parameters.putIfAbsent(name,reader.nextDouble())!=null) throw new IllegalArgumentException("Duplicate parameter.");if(parameters.size()>6) throw new IllegalArgumentException("Too many parameters."); }
                        reader.endObject();
                    }
                    case "units" -> {
                        reader.beginObject();
                        while(reader.hasNext()) { String name=reader.nextName();require(reader,JsonToken.STRING);if(units.putIfAbsent(name,reader.nextString())!=null) throw new IllegalArgumentException("Duplicate unit.");if(units.size()>6) throw new IllegalArgumentException("Too many units."); }
                        reader.endObject();
                    }
                    default -> throw new IllegalArgumentException("Unsupported JSON key: "+key);
                }
            }
            reader.endObject();require(reader,JsonToken.END_DOCUMENT);
            if(!keys.equals(Set.of("operation","parameters","units"))) throw new IllegalArgumentException("operation, parameters and units are required.");
            var input=new CalculationInput(CalculationKind.valueOf(operation),parameters,units);
            var result=calculator.calculate(input);
            if(!result.isSuccess()) return Result.failure(String.join("; ",result.errors()));
            var value=result.value().orElseThrow();Gson gson=new Gson();JsonObject output=new JsonObject();JsonObject numbers=new JsonObject();
            value.values().forEach((key,number) -> { if(number==Double.POSITIVE_INFINITY) numbers.addProperty(key,"Infinity");else numbers.addProperty(key,number); });
            output.add("values",numbers);output.add("units",gson.toJsonTree(value.units()));output.addProperty("estimated",value.estimated());output.add("assumptions",gson.toJsonTree(value.assumptions()));output.add("input",gson.toJsonTree(input));
            return Result.success(gson.toJson(output));
        } catch(IOException | IllegalArgumentException | IllegalStateException e) { return Result.failure("Invalid calculation: "+e.getMessage()); }
    }
    private static void require(JsonReader reader,JsonToken token) throws IOException {
        if(reader.peek()!=token) throw new IllegalArgumentException("Expected JSON "+token);
    }
}
