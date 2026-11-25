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
            for (var tool:CalculationForm.Tool.values()) {
                var arguments=new ArrayList<>(tool.fields.stream().map(f -> f.key()+"=<"+f.unit()+">").toList());
                tool.optional.forEach(f -> arguments.add("["+f.key()+"=<"+f.unit()+">]"));
                if (tool==CalculationForm.Tool.EXPOSURE) arguments.addAll(List.of("[step=third|half|full|exact]", "[solve=aperture|shutter|iso target=<EV100>]"));
                out.println("calc " + tool.command + " " + String.join(" ",arguments));
            }
            out.println(localization.text("cli.calc.inputHelp"));
            out.println(localization.text("cli.calc.optionalHelp"));
            out.println(localization.text("cli.calc.exposureHelp"));
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
            // Standard stops and solving are asked for explicitly; without them the exact result is printed as before.
            if (tool==CalculationForm.Tool.EXPOSURE && (fields.containsKey("step") || fields.containsKey("solve") || fields.containsKey("target"))) return exposure(fields);
            var parsed=CalculationForm.parse(tool,fields);
            if (!parsed.isSuccess()) { out.println(String.join("; ",parsed.errors(localization)));return 2; }
            var result=app.calculate(parsed.value().orElseThrow());
            if (!result.isSuccess()) { out.println(String.join("; ",result.errors(localization)));return 2; }
            out.println(CalculationForm.format(result.value().orElseThrow(), localization));
            if (tool==CalculationForm.Tool.HYPERFOCAL) {
                // The near limit when focused at H comes from the depth of field operation at s = H, not from a separate rule.
                var focused=app.calculate(CalculationForm.atHyperfocal(result.value().orElseThrow()));
                if (focused.isSuccess()) {
                    var range=focused.value().orElseThrow().values();
                    double far=range.get(com.photopilot.service.TechnicalCalculator.FAR_LIMIT);
                    out.println(localization.text("cli.calc.hyperfocalFocus", CalculationForm.formatNumber(range.get(com.photopilot.service.TechnicalCalculator.NEAR_LIMIT)),
                        far==Double.POSITIVE_INFINITY ? localization.text("calc.infinity") : CalculationForm.formatNumber(far)+" m"));
                }
            }
            return 0;
        } catch (IllegalArgumentException e) { out.println(String.join("; ", Result.failure(e).errors(localization)));return 2; }
    }
    /**
     * The exposure value report with an exposure step (default 1/3 EV, the GUI's default) and optionally one solved
     * setting: the same {@link ExposureSolver} and stops as the GUI page, so equal inputs give equal results.
     */
    private int exposure(Map<String,String> arguments) {
        Map<String,String> fields=new LinkedHashMap<>(arguments);
        String stepText=fields.remove("step"), solveText=fields.remove("solve"), targetText=fields.remove("target");
        var step=stepText==null ? ExposureStops.Step.THIRD : ExposureStops.Step.parse(stepText).orElseThrow(() -> new LocalizedException("error.cli.exposureStep", stepText));
        ExposureStops.Parameter solve=null;
        if (solveText!=null || targetText!=null) {
            if (solveText==null || targetText==null) throw new LocalizedException("error.cli.exposureSolve");
            solve=ExposureStops.Parameter.parse(solveText).orElseThrow(() -> new LocalizedException("error.cli.exposureSolve"));
            if (fields.containsKey(solve.key)) throw new LocalizedException("error.cli.exposureSolve");
        }
        for (String key:fields.keySet()) if (ExposureStops.Parameter.parse(key).filter(p -> p.key.equals(key)).isEmpty()) throw new LocalizedException("error.calc.parameter", key);
        double[] values=new double[3];
        for (var parameter:ExposureStops.Parameter.values()) {
            if (parameter==solve) { values[parameter.ordinal()]=Double.NaN; continue; }
            String text=fields.get(parameter.key);
            if (text==null) throw new LocalizedException("calc.status.input", LocalizedMessage.of(parameter.nameKey()), LocalizedMessage.of("calc.error.required"));
            values[parameter.ordinal()]=CalculationForm.number(text, parameter==ExposureStops.Parameter.SHUTTER);
        }
        var request=ExposureSolver.Request.of(step, values[0], values[1], values[2]);
        if (solve!=null) request=request.solving(solve, CalculationForm.number(targetText, false));
        var solved=ExposureSolver.solve(request, app::calculate);
        if (!solved.isSuccess()) {
            var first=solved.messages().getFirst();
            // The page's wording talks about turning a hold off; the command line names its own arguments instead.
            if (first.key().equals("calc.exposure.holdRange")) out.println(localization.text("cli.calc.exposure.solveRange", first.arguments().getFirst(), targetText));
            else out.println(String.join("; ",solved.errors(localization)));
            return 2;
        }
        var solution=solved.value().orElseThrow();
        out.println(ExposurePresentation.report(solution, ExposureSolver.equivalents(solution, 3), localization));
        return 0;
    }
}
