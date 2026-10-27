package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.i18n.*;
import com.photopilot.common.Result;
import java.io.PrintStream;
import java.util.*;

/** F08 command adapter; all parsing and calculation behavior is shared with GUI. */
final class CalculationCommands {
    private final LocalizationService localization;
    private final PhotoPilotFacade app;
    private final PrintStream out;
    CalculationCommands(PhotoPilotFacade app, PrintStream out, LocalizationService localization) { this.app=app;this.out=out;this.localization=localization; }
    int execute(String line) {
        String[] tokens=line.strip().split("\\s+");
        if (tokens.length==1 || tokens[1].equalsIgnoreCase("help")) {
            for (var tool:CalculationForm.Tool.values()) out.println("calc " + tool.command + " " + String.join(" ",tool.fields.stream().map(f -> f.key()+"=<"+f.unit()+">").toList()));
            out.println(localization.text("cli.calc.inputHelp"));
            out.println(localization.text("cli.calc.gearHelp"));
            return 0;
        }
        try {
            var tool=Arrays.stream(CalculationForm.Tool.values()).filter(t -> t.command.equalsIgnoreCase(tokens[1])).findFirst().orElseThrow(() -> new LocalizedException("error.cli.unknownCalculator"));
            Map<String,String> fields=new LinkedHashMap<>();
            for (int i=2;i<tokens.length;i++) {
                String[] pair=tokens[i].split("=",-1);
                if (pair.length!=2 || fields.putIfAbsent(pair[0],pair[1])!=null) throw new LocalizedException("error.cli.arguments");
            }
            String gearId=fields.remove("gear");
            if (gearId!=null) {
                var listed=app.inventory().list("");
                if (!listed.isSuccess()) { out.println(String.join("; ",listed.errors(localization)));return 2; }
                var id=UUID.fromString(gearId);
                var gear=listed.value().orElseThrow().items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> new LocalizedException("error.cli.gearIdNotFound"));
                Map<String,String> merged=new LinkedHashMap<>(CalculationForm.fromGear(tool,gear));
                if (fields.containsKey("ndFactor") || fields.containsKey("opticalDensity")) merged.remove("ndStops");
                merged.putAll(fields);fields=merged;
                out.println(localization.text("cli.calc.gearValues", gear.brand(), gear.model(), fields));
            }
            var parsed=CalculationForm.parse(tool,fields);
            if (!parsed.isSuccess()) { out.println(String.join("; ",parsed.errors(localization)));return 2; }
            var result=app.calculate(parsed.value().orElseThrow());
            if (!result.isSuccess()) { out.println(String.join("; ",result.errors(localization)));return 2; }
            out.println(CalculationForm.format(result.value().orElseThrow(), localization));return 0;
        } catch (IllegalArgumentException e) { out.println(String.join("; ", Result.failure(e).errors(localization)));return 2; }
    }
}
