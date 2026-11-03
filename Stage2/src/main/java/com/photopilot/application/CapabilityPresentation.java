package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.stream.Collectors;

/** Shared localized report formatting; all observations and calculations come from the service. */
public final class CapabilityPresentation {
    private CapabilityPresentation() { }
    public enum Section { OVERVIEW, COVERAGE, APERTURE, COMPATIBILITY, SUMMARY }
    public static String format(GearCapabilityAnalysis report, Section section, LocalizationService l) {
        var lines = new ArrayList<String>();
        switch (section) {
            case OVERVIEW -> {
                lines.add(l.text("capability.count", report.equipment().size()));
                for (var type : EquipmentType.values()) lines.add(l.text("capability.quantity", l.text("gear.type." + type.name()), report.quantities().getOrDefault(type, 0L)));
                if (report.equipment().isEmpty()) lines.add(l.text("capability.empty"));
                for (var e : report.equipment()) {
                    lines.add(name(e) + " [" + e.id() + "]");
                    lines.add(l.text("capability.item", l.text("gear.type." + e.type().name()), e.quantity()));
                    for (var field : e.type().fields()) lines.add("  " + l.text("gear.field." + field.key()) + ": "
                        + e.specifications().getOrDefault(field.key(), l.text("capability.unknown")));
                }
                lines.add(l.text("capability.evidence"));
            }
            case COVERAGE -> {
                lines.add(l.text("capability.actual"));
                lines.add(l.text("capability.covered", intervals(report.coverage(), l)));
                if (!report.coverage().isEmpty()) lines.add(l.text("capability.extent", interval(new Interval(report.coverage().getFirst().startMm(), report.coverage().getLast().endMm()))));
                lines.add(l.text("capability.overlaps", intervals(report.overlaps(), l)));
                lines.add(l.text("capability.gaps", report.gaps().isEmpty() ? l.text("capability.none") : report.gaps().stream()
                    .map(g -> "(" + number(g.startMm()) + ", " + number(g.endMm()) + ") mm").collect(Collectors.joining("; "))));
                lines.add(l.text("capability.gapNote"));
                for (var lens : report.lenses()) lines.add(name(lens.lens()) + ": " + interval(lens.focal()));
                lines.add(l.text("capability.poolNote"));
            }
            case APERTURE -> {
                lines.add(l.text("capability.largest", report.largestAperture().map(a -> "f/" + number(a)).orElse(l.text("capability.unknown"))));
                for (var entry : report.lenses()) {
                    var lens = entry.lens();
                    lines.add(name(lens) + ": " + l.text("capability.aperture." + entry.apertureKind().name(),
                        number(lens.maxAperture()), lens.maxApertureAtMaxFocal().map(CapabilityPresentation::number).orElse(l.text("capability.unknown"))));
                }
                lines.add(l.text("capability.apertureNote"));
            }
            case COMPATIBILITY -> {
                if (report.pairs().isEmpty()) lines.add(l.text("capability.noPairs"));
                for (var pair : report.pairs()) {
                    lines.add(name(pair.camera()) + " + " + name(pair.lens()) + ": " + l.text("capability.mount." + pair.mountStatus().name()));
                    lines.add(l.text("capability.equivalent", pair.equivalentFocal().map(CapabilityPresentation::equivalentInterval).orElse(l.text("capability.unknown"))));
                }
                lines.add(l.text("capability.compatibilityNote"));
                lines.add(l.text("capability.equivalentNote"));
            }
            case SUMMARY -> {
                lines.add(l.text("capability.summaryCount", report.quantities().getOrDefault(EquipmentType.CAMERA, 0L), report.quantities().getOrDefault(EquipmentType.LENS, 0L)));
                lines.add(l.text("capability.covered", intervals(report.coverage(), l)));
                lines.add(l.text("capability.summaryIntervals", report.coverage().size(), report.overlaps().size(), report.gaps().size()));
                lines.add(l.text("capability.largest", report.largestAperture().map(a -> "f/" + number(a)).orElse(l.text("capability.unknown"))));
                lines.add(l.text("capability.compatibilityNote"));
                lines.add(l.text("capability.scope"));
            }
        }
        return String.join("\n", lines);
    }
    public static String all(GearCapabilityAnalysis report, LocalizationService l) {
        return Arrays.stream(Section.values()).map(s -> l.text("capability.section." + s.name()) + "\n" + format(report, s, l)).collect(Collectors.joining("\n\n"));
    }
    private static String name(Equipment e) { return e.brand() + " " + e.model(); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String equivalentInterval(Interval r) {
        return BigDecimal.valueOf(r.startMm()).round(new MathContext(8)).stripTrailingZeros().toPlainString()
            + (r.startMm() == r.endMm() ? "" : "–" + BigDecimal.valueOf(r.endMm()).round(new MathContext(8)).stripTrailingZeros().toPlainString()) + " mm";
    }
    private static String interval(Interval r) { return number(r.startMm()) + (r.startMm() == r.endMm() ? "" : "–" + number(r.endMm())) + " mm"; }
    private static String intervals(List<Interval> ranges, LocalizationService l) { return ranges.isEmpty() ? l.text("capability.none") : ranges.stream().map(CapabilityPresentation::interval).collect(Collectors.joining("; ")); }
}
