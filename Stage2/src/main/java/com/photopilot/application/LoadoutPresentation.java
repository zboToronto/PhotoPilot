package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.*;
import com.photopilot.validation.CompatibilityValidator;
import java.util.*;

/** Shared localized F07 text for GUI cards and CLI output; every statement comes from the services. */
public final class LoadoutPresentation {
    private LoadoutPresentation() { }
    /** Recommendations shown in full; the remainder is summarized by count. */
    public static final int SHOWN = 6;

    public static String heading(Candidate candidate, int rank, LocalizationService l) {
        return l.text(candidate.fullySuitable() ? "match.card.full" : "match.card.partial", rank);
    }

    /** Equipment, verified compatibility, matched and unmet needs, trade-offs and any clearly labelled AI note. */
    public static List<String> candidate(Candidate candidate, LocalizationService l) {
        var lines = new ArrayList<String>();
        var loadout = candidate.loadout();
        lines.add(l.text("match.card.gear"));
        for (GearSnapshot item : loadout.gear()) lines.add("  " + gear(item, l));
        lines.add(l.text(loadout.compatibility().isUsable() ? "match.card.verified" : "match.card.notVerified"));
        var met = candidate.needs().stream().filter(n -> n.status() == NeedStatus.MET).toList();
        var unmet = candidate.needs().stream().filter(n -> n.status() != NeedStatus.MET).toList();
        lines.add(l.text("match.card.met", met.isEmpty() ? l.text("match.none") : ""));
        met.forEach(n -> lines.add("  " + need(n, l)));
        lines.add(l.text("match.card.unmet", unmet.isEmpty() && candidate.essentials().isEmpty() ? l.text("match.none") : ""));
        unmet.forEach(n -> lines.add("  " + need(n, l)));
        candidate.essentials().forEach(e -> lines.add("  " + e.render(l)));
        lines.add(l.text("match.card.tradeOffs"));
        candidate.tradeOffs().forEach(t -> lines.add("  " + t.render(l)));
        candidate.aiExplanation().ifPresent(text -> lines.add(l.text("match.card.ai", text)));
        return lines;
    }

    /** How the ranking was produced and what UC14 rejected. */
    public static List<String> summary(GearMatchReport report, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("match.summary.strategy", l.text("match.strategy." + report.strategy())));
        lines.add(report.aiRanked() ? l.text("match.ranking.ai") : report.rankingNotice().map(n -> n.render(l)).orElse(l.text("match.ranking.deterministic")));
        if (report.aiRanked() && !report.aiSummary().isBlank()) lines.add(l.text("match.summary.ai", report.aiSummary()));
        lines.add(l.text("match.summary.counts", report.combinationsChecked(), report.recommendations().size(), report.rejectedCombinations()));
        long full = report.recommendations().stream().filter(Candidate::fullySuitable).count();
        if (report.recommendations().isEmpty()) lines.add(l.text("match.summary.noneUsable"));
        else if (full == 0) lines.add(l.text("match.summary.noFull"));
        else lines.add(l.text("match.summary.full", full));
        if (report.recommendations().size() > SHOWN) lines.add(l.text("match.summary.more", report.recommendations().size() - SHOWN));
        if (report.searchLimited()) lines.add(l.text("match.summary.limited", com.photopilot.service.GearMatchingService.MAX_COMBINATIONS));
        if (report.missingSpecifications()) lines.add(l.text("match.summary.recordMissing"));
        return lines;
    }
    public static List<String> rejections(GearMatchReport report, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (report.rejections().isEmpty()) lines.add(l.text("match.none"));
        report.rejections().forEach(reason -> lines.add("- " + reason.render(l)));
        return lines;
    }

    /** Saved selection, re-checked now against inventory and the current profile. */
    public static List<String> selection(SelectionStatus status, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text(status.evaluation().fullySuitable() ? "match.saved.full" : "match.saved.partial"));
        lines.addAll(candidate(status.evaluation(), l));
        if (status.current()) lines.add(l.text("match.saved.current"));
        else {
            lines.add(l.text("match.saved.stale"));
            status.compatibility().forEach(m -> lines.add("  " + m.render(l)));
            status.availability().forEach(m -> lines.add("  " + m.render(l)));
        }
        return lines;
    }

    /** Confirmation text before saving: what is saved, its unmet needs and whether it replaces a selection. */
    public static List<String> confirmation(Candidate candidate, PhotographySession session, LocalizationService l) {
        var lines = new ArrayList<String>();
        lines.add(l.text("match.confirm.prompt", session.title()));
        for (GearSnapshot item : candidate.loadout().gear()) lines.add("  " + gear(item, l));
        if (!candidate.fullySuitable()) {
            lines.add(l.text("match.confirm.partial"));
            candidate.needs().stream().filter(n -> n.status() != NeedStatus.MET).forEach(n -> lines.add("  " + need(n, l)));
            candidate.essentials().forEach(e -> lines.add("  " + e.render(l)));
        }
        if (session.selectedLoadout().isPresent()) lines.add(l.text("match.confirm.replace"));
        return lines;
    }

    public static String gear(GearSnapshot item, LocalizationService l) {
        return l.text("match.gearLine", item.selectedQuantity(), CompatibilityValidator.name(item), l.text("gear.type." + item.type()));
    }
    public static String need(NeedResult result, LocalizationService l) {
        return l.text("match.need." + result.status().name(), l.text("needs.capability." + result.need().capability()),
            l.text(result.need().hardConstraint() ? "needs.mandatory" : "needs.optional"), result.detail().render(l));
    }
    public static String strategyName(String strategy, LocalizationService l) { return l.text("match.strategy." + strategy); }
    public static String render(List<LocalizedMessage> messages, LocalizationService l) {
        return String.join("; ", messages.stream().map(m -> m.render(l)).toList());
    }
}
