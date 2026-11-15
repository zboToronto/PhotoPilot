package com.photopilot.service;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CameraMetadata;
import com.photopilot.model.CameraMetadata.Field;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CameraModel.Brand;
import com.photopilot.model.CounterKind;
import com.photopilot.model.ShutterCountResult.CounterReading;
import com.photopilot.model.ShutterCountResult.Relation;
import com.photopilot.model.ShutterCountResult.Status;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Chooses and explains a photo's shutter counter, using only what ExifTool 13.62 documents (TagNames notes and the
 * Sony.pm / Nikon.pm definitions). Candidate fields are never interchangeable:
 *
 * <ul>
 *   <li>Sony: the {@code ShutterCount} of the MakerNotes table ExifTool uses for the model. Tag9050d counts mechanical
 *       shutter actuations; Tag9050a/b/c count "image exposures made by the camera" (Tag9050a modulo 65536 on some models);
 *       MoreInfo0201 and FocusInfo carry older DSLR/SLT counters. {@code ShutterCount2}/{@code ShutterCount3} have no
 *       documented meaning and only cross-check it.</li>
 *   <li>Nikon: MakerNotes 0x00a7 {@code ShutterCount}, documented to include mechanical and electronic shutter
 *       activations (4294965247 means n/a); a ShotInfo copy cross-checks it; {@code MechanicalShutterCount} is a separate
 *       counter shown on its own.</li>
 *   <li>{@code ImageCount} is never used as a shutter count.</li>
 * </ul>
 * A missing or invalid value is reported as such, never replaced by zero or an estimate.
 */
final class ShutterCountRules {
    record Interpretation(Status status, Optional<CounterReading> counter, List<CounterReading> others,
                          Optional<LocalizedMessage> reason, List<LocalizedMessage> notes) { }

    /** Sony primary tables in priority order with what their ShutterCount counts. */
    private static final Map<String, CounterKind> SONY_TABLES = new LinkedHashMap<>();
    static {
        SONY_TABLES.put("Tag9050d", CounterKind.MECHANICAL_ACTUATIONS);
        SONY_TABLES.put("Tag9050c", CounterKind.TOTAL_EXPOSURES);
        SONY_TABLES.put("Tag9050b", CounterKind.TOTAL_EXPOSURES);
        SONY_TABLES.put("Tag9050a", CounterKind.TOTAL_EXPOSURES);
        SONY_TABLES.put("MoreInfo0201", CounterKind.SHUTTER_COUNT);
        SONY_TABLES.put("FocusInfo", CounterKind.SHUTTER_COUNT);
    }
    /** Sony counters are 24-bit (ExifTool masks them with 0x00ffffff). */
    private static final long SONY_MAX = 0xFFFFFFL;
    /** Nikon 0x00a7 is int32u; ExifTool prints 4294965247 as "n/a". */
    private static final long NIKON_MAX = 0xFFFFFFFFL, NIKON_NOT_AVAILABLE = 4294965247L;
    private static final Pattern SONY_MECHANICAL_ONLY = Pattern.compile("^(ILCE-(1M2|7M5|7RM6))");

    private ShutterCountRules() { }

    static Interpretation interpret(CameraMetadata metadata, Optional<CameraModel> catalogModel) {
        List<LocalizedMessage> notes = new ArrayList<>();
        Optional<String> make = metadata.make(), model = metadata.model();
        if (make.isEmpty() && model.isEmpty()) return none(Status.NO_CAMERA, LocalizedMessage.of("shutter.reason.noCamera"), List.of(), notes);
        Optional<Brand> brand = make.flatMap(Brand::fromMake);
        if (brand.isEmpty()) return none(Status.UNSUPPORTED_BRAND, LocalizedMessage.of("shutter.reason.unsupportedBrand", make.orElse("?")), List.of(), notes);
        if (!metadata.makerNotesPresent()) {
            String type = metadata.fileType().orElse("");
            String key = type.equals("JPEG") ? "shutter.reason.noMakerNotesJpeg" : type.equals("DNG") ? "shutter.reason.noMakerNotesDng" : "shutter.reason.noMakerNotes";
            return none(Status.MAKERNOTES_MISSING, LocalizedMessage.of(key), List.of(), notes);
        }
        shutterTypeOfPhoto(metadata, brand.get()).ifPresent(notes::add);
        return brand.get() == Brand.SONY ? sony(metadata, model.orElse(""), catalogModel, notes) : nikon(metadata, catalogModel, notes);
    }

    private static Interpretation sony(CameraMetadata metadata, String model, Optional<CameraModel> catalogModel, List<LocalizedMessage> notes) {
        List<Candidate> primary = new ArrayList<>(), invalid = new ArrayList<>();
        for (var table : SONY_TABLES.entrySet())
            for (Field field : metadata.all("ShutterCount")) if (field.inMakerNotes() && field.table().equals(table.getKey()))
                parse(field, SONY_MAX).ifPresentOrElse(value -> primary.add(new Candidate(field, value, table.getValue())), () -> invalid.add(new Candidate(field, -1, table.getValue())));
        List<CounterReading> others = new ArrayList<>();
        Optional<Candidate> chosen = choose(primary, notes);
        for (String related : List.of("ShutterCount2", "ShutterCount3"))
            for (Field field : metadata.all(related)) if (field.inMakerNotes()) parse(field, SONY_MAX).ifPresent(value -> {
                Relation relation = chosen.isEmpty() ? Relation.SEPARATE_COUNTER : value == chosen.get().value() ? Relation.MATCHES_SELECTED : Relation.DIFFERS_FROM_SELECTED;
                others.add(reading(field, value, CounterKind.RELATED, relation));
                if (relation == Relation.DIFFERS_FROM_SELECTED)
                    notes.add(LocalizedMessage.of(related.equals("ShutterCount2") ? "shutter.note.relatedDiffers" : "shutter.note.shutterCount3Differs", related, value, chosen.get().value()));
            });
        imageCounts(metadata, others, notes, chosen.isEmpty());
        if (chosen.isPresent()) {
            Candidate counter = chosen.get();
            switch (counter.field().table()) {
                case "Tag9050a" -> notes.add(LocalizedMessage.of("shutter.note.modulo"));
                case "Tag9050b", "Tag9050c" -> notes.add(LocalizedMessage.of("shutter.note.silent"));
                case "FocusInfo" -> notes.add(LocalizedMessage.of("shutter.note.equalsImageCount"));
                default -> { }
            }
            return success(counter, others, notes);
        }
        if (!invalid.isEmpty()) return invalid(invalid.getFirst(), others, notes);
        if (SONY_MECHANICAL_ONLY.matcher(model).find()) return none(Status.NO_COUNTER, LocalizedMessage.of("shutter.reason.mechanicalOnly"), others, notes);
        return none(Status.NO_COUNTER, noCounterReason(catalogModel), others, notes);
    }

    private static Interpretation nikon(CameraMetadata metadata, Optional<CameraModel> catalogModel, List<LocalizedMessage> notes) {
        List<Candidate> primary = new ArrayList<>(), invalid = new ArrayList<>(), shotInfo = new ArrayList<>();
        for (Field field : metadata.all("ShutterCount")) {
            if (!field.inMakerNotes()) continue;
            boolean main = field.table().equals("MakerNotes");
            boolean copy = field.table().startsWith("ShotInfo");
            if (!main && !copy) continue;
            CounterKind kind = main ? CounterKind.SHOTS_INCLUDING_ELECTRONIC : CounterKind.SHUTTER_COUNT;
            Optional<Long> value = parseNikon(field);
            if (value.isEmpty()) invalid.add(new Candidate(field, -1, kind));
            else (main ? primary : shotInfo).add(new Candidate(field, value.get(), kind));
        }
        // The documented main counter first; a ShotInfo copy is used only when the main field is absent.
        Optional<Candidate> chosen = choose(primary.isEmpty() ? shotInfo : primary, notes);
        List<CounterReading> others = new ArrayList<>();
        if (!primary.isEmpty()) for (Candidate copy : shotInfo) {
            Relation relation = copy.value() == chosen.get().value() ? Relation.MATCHES_SELECTED : Relation.DIFFERS_FROM_SELECTED;
            others.add(reading(copy.field(), copy.value(), CounterKind.SHUTTER_COUNT, relation));
            if (relation == Relation.DIFFERS_FROM_SELECTED) notes.add(LocalizedMessage.of("shutter.note.relatedDiffers", copy.field().table() + " ShutterCount", copy.value(), chosen.get().value()));
        }
        for (Field field : metadata.all("MechanicalShutterCount")) if (field.inMakerNotes()) parseNikon(field).ifPresent(value -> {
            others.add(reading(field, value, CounterKind.MECHANICAL_BY_NAME, Relation.SEPARATE_COUNTER));
            if (chosen.isPresent() && chosen.get().kind() == CounterKind.SHOTS_INCLUDING_ELECTRONIC && value > chosen.get().value())
                notes.add(LocalizedMessage.of("shutter.note.mechanicalAboveTotal", value, chosen.get().value()));
        });
        imageCounts(metadata, others, notes, chosen.isEmpty());
        if (chosen.isPresent()) {
            if (chosen.get().kind() == CounterKind.SHUTTER_COUNT) notes.add(LocalizedMessage.of("shutter.note.nikonShotInfoOnly"));
            return success(chosen.get(), others, notes);
        }
        if (!invalid.isEmpty()) return invalid(invalid.getFirst(), others, notes);
        return none(Status.NO_COUNTER, noCounterReason(catalogModel), others, notes);
    }

    private static void imageCounts(CameraMetadata metadata, List<CounterReading> others, List<LocalizedMessage> notes, boolean onlyCounter) {
        boolean found = false;
        for (Field field : metadata.all("ImageCount")) if (field.inMakerNotes()) {
            Optional<Long> value = parse(field, NIKON_MAX);
            if (value.isEmpty()) continue;
            others.add(reading(field, value.get(), CounterKind.IMAGE_COUNT, Relation.SEPARATE_COUNTER));
            found = true;
        }
        if (found && onlyCounter) notes.add(LocalizedMessage.of("shutter.note.imageCountOnly"));
    }

    /** One value per table: the highest-priority valid candidate; different values elsewhere are reported, not hidden. */
    private static Optional<Candidate> choose(List<Candidate> candidates, List<LocalizedMessage> notes) {
        if (candidates.isEmpty()) return Optional.empty();
        Candidate chosen = candidates.getFirst();
        for (Candidate other : candidates.subList(1, candidates.size()))
            if (other.value() != chosen.value())
                notes.add(LocalizedMessage.of("shutter.note.conflict", other.field().source(), other.value(), chosen.field().source(), chosen.value()));
        return Optional.of(chosen);
    }

    /** What the photo itself says about the shutter used (informational: the counter belongs to the camera). */
    private static Optional<LocalizedMessage> shutterTypeOfPhoto(CameraMetadata metadata, Brand brand) {
        if (brand == Brand.SONY) {
            for (Field field : metadata.all("Shutter")) if (field.inMakerNotes() && field.value().strip().matches("0( 0)*"))
                return Optional.of(LocalizedMessage.of("shutter.note.photoElectronic"));
            for (Field field : metadata.all("ShutterType")) if (field.inMakerNotes() && field.value().strip().equals("7"))
                return Optional.of(LocalizedMessage.of("shutter.note.photoElectronic"));
        } else {
            for (Field field : metadata.all("ShutterMode")) if (field.inMakerNotes() && Set.of("16", "64", "96").contains(field.value().strip()))
                return Optional.of(LocalizedMessage.of("shutter.note.photoElectronic"));
        }
        return Optional.empty();
    }

    private static LocalizedMessage noCounterReason(Optional<CameraModel> catalogModel) {
        if (catalogModel.isPresent() && catalogModel.get().support().level() == CameraModel.Level.UNAVAILABLE)
            return LocalizedMessage.of("shutter.reason.unavailableModel", catalogModel.get().label());
        return LocalizedMessage.of("shutter.reason.noCounter");
    }

    private record Candidate(Field field, long value, CounterKind kind) { }

    private static Optional<Long> parse(Field field, long max) {
        String text = field.value().strip();
        if (!text.matches("\\d{1,19}")) return Optional.empty();
        try {
            long value = Long.parseLong(text);
            return value >= 1 && value <= max ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException overflow) { return Optional.empty(); }
    }
    private static Optional<Long> parseNikon(Field field) {
        return parse(field, NIKON_MAX).filter(value -> value != NIKON_NOT_AVAILABLE);
    }
    private static CounterReading reading(Field field, long value, CounterKind kind, Relation relation) {
        return new CounterReading(field.tag(), field.directory(), field.source(), value, field.value().strip(), kind, relation);
    }
    private static Interpretation success(Candidate counter, List<CounterReading> others, List<LocalizedMessage> notes) {
        return new Interpretation(Status.SUCCESS, Optional.of(reading(counter.field(), counter.value(), counter.kind(), Relation.SELECTED)), others, Optional.empty(), notes);
    }
    private static Interpretation invalid(Candidate counter, List<CounterReading> others, List<LocalizedMessage> notes) {
        return new Interpretation(Status.INVALID_COUNTER, Optional.empty(), others,
            Optional.of(LocalizedMessage.of("shutter.reason.invalid", counter.field().source(), counter.field().value().strip())), notes);
    }
    private static Interpretation none(Status status, LocalizedMessage reason, List<CounterReading> others, List<LocalizedMessage> notes) {
        return new Interpretation(status, Optional.empty(), others, Optional.of(reason), notes);
    }
}
