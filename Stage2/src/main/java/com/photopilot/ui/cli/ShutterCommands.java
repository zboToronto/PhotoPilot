package com.photopilot.ui.cli;

import com.photopilot.application.OperationKind;
import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.application.ShutterCountPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CameraModel.Brand;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/**
 * Shutter count checker command adapter. Validation, ExifTool reading, counter selection and model comparison run in
 * the shared ShutterCountService, and wording comes from ShutterCountPresentation, so CLI and GUI agree.
 *
 * <pre>
 * shutter help
 * shutter models &lt;sony|nikon&gt; [search words]
 * shutter check &lt;photo&gt; [brand=sony|nikon] [model=&lt;name or ID&gt;]
 * </pre>
 */
final class ShutterCommands {
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;

    ShutterCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization) {
        this.app = app; this.output = output; this.localization = localization;
    }

    int execute(String line) {
        var words = InventoryCommands.tokenize(line.strip());
        String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "help" -> { output.println(localization.text("shutter.cli.help")); yield 0; }
            case "models" -> models(words);
            case "check" -> check(words);
            default -> throw new LocalizedException("error.cli.option", action);
        };
    }

    private int models(List<String> words) {
        if (words.size() < 3) throw new LocalizedException("shutter.cli.modelsUsage");
        Brand brand = brand(words.get(2));
        String query = String.join(" ", words.subList(3, words.size()));
        var catalog = app.shutterCount().catalog();
        var found = catalog.search(brand, Optional.empty(), query);
        output.println(ShutterCountPresentation.source(catalog, brand, localization));
        CameraModel.Series current = null;
        for (CameraModel model : found) {
            if (model.series() != current) {
                current = model.series();
                output.println(localization.text("shutter.cli.series", ShutterCountPresentation.series(current, localization),
                    ShutterCountPresentation.family(current.family(), localization)));
            }
            output.println(localization.text("shutter.cli.model", model.label(), ShutterCountPresentation.level(model.support().level(), localization)));
        }
        output.println(localization.text("shutter.cli.modelCount", found.size()));
        return found.isEmpty() ? 2 : 0;
    }

    private int check(List<String> words) {
        if (words.size() < 3) throw new LocalizedException("shutter.cli.checkUsage");
        Optional<Brand> brand = Optional.empty();
        Optional<CameraModel> model = Optional.empty();
        String file = null;
        for (String word : words.subList(2, words.size())) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (lower.startsWith("brand=")) brand = Optional.of(brand(word.substring(6)));
            else if (lower.startsWith("model=")) {
                String name = word.substring(6);
                List<Brand> brands = brand.map(List::of).orElse(List.of(Brand.values()));
                model = brands.stream().map(candidate -> app.shutterCount().catalog().resolve(candidate, name)).flatMap(Optional::stream).findFirst();
                if (model.isEmpty()) throw new LocalizedException("shutter.cli.unknownModel", name);
            } else if (file == null) file = word;
            else throw new LocalizedException("shutter.cli.checkUsage");
        }
        if (file == null) throw new LocalizedException("shutter.cli.checkUsage");
        Path path;
        try { path = Path.of(file); } catch (InvalidPathException invalid) { throw new LocalizedException("shutter.error.badPath"); }
        output.println(localization.text("shutter.reading"));
        var result = CliProgress.run(OperationKind.SHUTTER_CHECK, () -> app.shutterCount().check(path));
        if (!result.isSuccess()) { result.errors(localization).forEach(output::println); return 2; }
        var value = result.value().orElseThrow();
        Optional<CameraModel> selected = model;
        Optional<Brand> selectedBrand = brand.or(() -> selected.map(CameraModel::brand));
        var check = app.shutterCount().compare(value, selectedBrand, selected);
        ShutterCountPresentation.lines(value, check, selected, localization).forEach(output::println);
        return value.status() == com.photopilot.model.ShutterCountResult.Status.SUCCESS ? 0 : 2;
    }

    private static Brand brand(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "sony" -> Brand.SONY;
            case "nikon" -> Brand.NIKON;
            default -> throw new LocalizedException("shutter.cli.unknownBrand", text);
        };
    }
}
