package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CounterKind;
import com.photopilot.model.ShutterCountResult;
import com.photopilot.model.ShutterCountResult.CounterReading;
import com.photopilot.model.ShutterCountResult.ModelCheck;
import com.photopilot.service.CameraModelCatalog;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shutter count checker wording shared by the Camera Shutter Count page and the {@code shutter} CLI command. */
public final class ShutterCountPresentation {
    private ShutterCountPresentation() { }

    public static String count(long value, LocalizationService localization) {
        return NumberFormat.getIntegerInstance(localization.language().locale()).format(value);
    }
    public static String kind(CounterKind kind, LocalizationService localization) { return localization.text("shutter.kind." + kind.name()); }
    public static String meaning(CounterKind kind, LocalizationService localization) { return localization.text("shutter.meaning." + kind.name()); }
    public static String status(ShutterCountResult.Status status, LocalizationService localization) { return localization.text("shutter.status." + status.name()); }
    public static String series(CameraModel.Series series, LocalizationService localization) { return localization.text("shutter.series." + series.name()); }
    public static String family(CameraModel.Family family, LocalizationService localization) { return localization.text("shutter.family." + family.name()); }
    public static String level(CameraModel.Level level, LocalizationService localization) { return localization.text("shutter.level." + level.name()); }

    /** "Documented by ExifTool · Sony Tag9050c ShutterCount · …": what is known about reading this model's counter. */
    public static String support(CameraModel model, LocalizationService localization) { return support(model, localization, true); }

    /** As {@link #support(CameraModel, LocalizationService)}; without the documented condition when a result note already explains it. */
    public static String support(CameraModel model, LocalizationService localization, boolean withCondition) {
        var support = model.support();
        List<String> parts = new ArrayList<>();
        parts.add(level(support.level(), localization));
        support.field().ifPresent(parts::add);
        support.kind().ifPresent(kind -> parts.add(kind(kind, localization)));
        String text = localization.text("shutter.support.text", String.join(" · ", parts), localization.text(support.evidence()));
        return support.condition().filter(condition -> withCondition).map(condition -> text + " " + localization.text(condition)).orElse(text);
    }

    /** "ExifTool 13.62 · tested-camera list (https://exiftool.org/models.html), retrieved 2026-11-09 · 123 Sony models". */
    public static String source(CameraModelCatalog catalog, CameraModel.Brand brand, LocalizationService localization) {
        var source = catalog.source();
        return localization.text("shutter.catalog.source", source.exiftoolVersion(), source.url(), source.retrieved(), catalog.models(brand).size(), brand.label());
    }

    /** File size in MB with one decimal ("69.3 MB"), the way the reference layout shows it. */
    public static String size(long bytes, LocalizationService localization) {
        if (bytes < 1024 * 1024) return localization.text("shutter.size.kb", String.format(localization.language().locale(), "%.1f", bytes / 1024.0));
        return localization.text("shutter.size.mb", String.format(localization.language().locale(), "%.1f", bytes / 1024.0 / 1024.0));
    }

    /** EXIF "2026:09:08 11:26:03" as "2026-09-08 11:26:03"; anything else unchanged. */
    public static String date(String exif) {
        return exif.matches("\\d{4}:\\d{2}:\\d{2}( .*)?") ? exif.substring(0, 10).replace(':', '-') + exif.substring(10) : exif;
    }

    /** "Sony ILCE-7RM5" from the photo, or the make / model alone. */
    public static String camera(ShutterCountResult result) {
        String make = result.brand().map(CameraModel.Brand::label).or(result::make).orElse("");
        String model = result.model().orElse("");
        return (make + " " + model).strip();
    }

    /** The detected model with its catalog name when known: "α7R V (ILCE-7RM5)". */
    public static String model(ShutterCountResult result) {
        return result.catalogModel().map(CameraModel::label).or(result::model).orElse("");
    }

    /** The comparison with the selector, or empty when nothing needs saying. */
    public static Optional<String> modelCheck(ModelCheck check, ShutterCountResult result, Optional<CameraModel> selected, LocalizationService localization) {
        return switch (check) {
            case NOT_SELECTED -> Optional.empty();
            case MATCH -> Optional.of(localization.text("shutter.check.match", selected.map(CameraModel::label).orElse("")));
            case MISMATCH -> Optional.of(localization.text("shutter.check.mismatch", model(result), selected.map(CameraModel::label).orElse("")));
            case BRAND_MISMATCH -> Optional.of(localization.text("shutter.check.brandMismatch", camera(result)));
            case UNKNOWN -> Optional.of(localization.text("shutter.check.unknown", camera(result), selected.map(CameraModel::label).orElse("")));
        };
    }

    /** One other counter field: "ShutterCount2 (MakerNotes › Tag9050c) = 54,147 · matches the selected counter". */
    public static String other(CounterReading reading, LocalizationService localization) {
        return localization.text("shutter.other", reading.source(), count(reading.value(), localization),
            localization.text("shutter.relation." + reading.relation().name()), kind(reading.kind(), localization));
    }

    public static List<String> notes(ShutterCountResult result, LocalizationService localization) {
        return result.notes().stream().map(message -> message.render(localization)).toList();
    }

    /** The complete result as text lines (CLI). */
    public static List<String> lines(ShutterCountResult result, ModelCheck check, Optional<CameraModel> selected, LocalizationService localization) {
        List<String> lines = new ArrayList<>();
        lines.add(localization.text("shutter.cli.file", result.fileName(), size(result.fileSize(), localization), result.fileType()));
        lines.add(localization.text("shutter.cli.status", status(result.status(), localization)));
        if (result.make().isPresent() || result.model().isPresent()) lines.add(localization.text("shutter.cli.camera", camera(result)));
        result.catalogModel().ifPresent(model -> lines.add(localization.text("shutter.cli.support", support(model, localization, result.counter().isEmpty()))));
        modelCheck(check, result, selected, localization).ifPresent(lines::add);
        result.counter().ifPresent(counter -> {
            lines.add(localization.text("shutter.cli.count", count(counter.value(), localization), kind(counter.kind(), localization)));
            lines.add(localization.text("shutter.cli.source", counter.source(), counter.original()));
            lines.add(localization.text("shutter.cli.meaning", meaning(counter.kind(), localization)));
        });
        result.reason().map(reason -> reason.render(localization)).ifPresent(lines::add);
        result.captureDate().ifPresent(date -> lines.add(localization.text("shutter.cli.date", date(date))));
        for (CounterReading other : result.otherCounters()) lines.add(localization.text("shutter.cli.other", other(other, localization)));
        for (String note : notes(result, localization)) lines.add(localization.text("shutter.cli.note", note));
        return lines;
    }

    /** Renders a failure message list; never includes a folder path. */
    public static List<String> errors(List<LocalizedMessage> messages, LocalizationService localization) {
        return messages.stream().map(message -> message.render(localization)).toList();
    }
}
