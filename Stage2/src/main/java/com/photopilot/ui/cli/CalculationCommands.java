package com.photopilot.ui.cli;

import com.photopilot.application.*;
import java.io.PrintStream;
import java.util.*;

/** F08 command adapter; all parsing and calculation behavior is shared with GUI. */
final class CalculationCommands {
    private final PhotoPilotFacade app;
    private final PrintStream out;
    CalculationCommands(PhotoPilotFacade app, PrintStream out) { this.app=app;this.out=out; }
    int execute(String line) {
        String[] tokens=line.strip().split("\\s+");
        if (tokens.length==1 || tokens[1].equalsIgnoreCase("help")) {
            for (var tool:CalculationForm.Tool.values()) out.println("calc " + tool.command + " " + String.join(" ",tool.fields.stream().map(f -> f.key()+"=<"+f.unit()+">").toList()));
            out.println("Shutter accepts 1/125 or decimal seconds. For ND use exactly one: ndStops, ndFactor (ND8 = factor 8), opticalDensity (OD 3.0). All inputs required; units as listed.");
            return 0;
        }
        try {
            var tool=Arrays.stream(CalculationForm.Tool.values()).filter(t -> t.command.equalsIgnoreCase(tokens[1])).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown calculator. Type calc help."));
            Map<String,String> fields=new LinkedHashMap<>();
            for (int i=2;i<tokens.length;i++) {
                String[] pair=tokens[i].split("=",-1);
                if (pair.length!=2 || fields.putIfAbsent(pair[0],pair[1])!=null) throw new IllegalArgumentException("Use unique key=value arguments. Type calc help.");
            }
            var parsed=CalculationForm.parse(tool,fields);
            if (!parsed.isSuccess()) { out.println(String.join("; ",parsed.errors()));return 2; }
            var result=app.calculate(parsed.value().orElseThrow());
            if (!result.isSuccess()) { out.println(String.join("; ",result.errors()));return 2; }
            out.println(CalculationForm.format(result.value().orElseThrow()));return 0;
        } catch (IllegalArgumentException e) { out.println(e.getMessage());return 2; }
    }
}
