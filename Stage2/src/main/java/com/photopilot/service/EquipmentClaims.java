package com.photopilot.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.photopilot.command.ShootPlan;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.service.EquipmentIdentity.Comparison;
import com.photopilot.service.EquipmentIdentity.Kind;
import com.photopilot.validation.CompatibilityValidator;

/**
 * F11 UC13 check of what an AI review statement says about equipment identity (Stage2OptRe1 PP-03). Figures are checked
 * by {@link ReviewClaims}; a statement with correct figures can still be wrong about equipment, e.g. "SONY ILCE-7M4 ...
 * differing from the planned Sony Alpha 7 IV", although both names denote one camera model.
 *
 * <p>Mentions of the recorded (EXIF) and planned (approved plan loadout) names, in any catalog spelling, are marked in
 * the statement; a relation phrase anchored to the marks ("differing from the planned X", "Y instead of X", "Y is a
 * different camera", "Y matches the planned X") is then checked against {@link EquipmentIdentity}:
 * <ul>
 * <li>a claimed difference needs DIFFERENT_MODEL for every pair; a SAME_MODEL pair contradicts it and an UNKNOWN pair
 * leaves it unsupported, unless the statement only says that the names differ or that the identity is uncertain;</li>
 * <li>a claimed match needs SAME_MODEL for every pair;</li>
 * <li>a claim that one individual body or lens was used (same body, serial number) is never supported by EXIF model
 * names, unless the statement says it cannot be known.</li>
 * </ul>
 * Contradicted and unsupported statements are removed before a draft is shown or saved; the deterministic comparison
 * keeps the qualified facts. {@link #audit} applies the same check to saved reviews without changing them.
 */
public final class EquipmentClaims {
    private EquipmentClaims() { }

    public enum Verdict { NONE, SUPPORTED, UNSUPPORTED, CONTRADICTED }

    /** The planned and recorded names of one kind of equipment. */
    public record Names(List<String> planned, List<String> recorded) {
        public Names { planned = List.copyOf(planned); recorded = List.copyOf(recorded); }
    }
    /** Planned (approved plan loadout) and recorded (EXIF) camera and lens names of one review. */
    public record Evidence(Names cameras, Names lenses) {
        public static final Evidence NONE = new Evidence(new Names(List.of(), List.of()), new Names(List.of(), List.of()));
        public static Evidence of(ExifStatistics stats, Optional<ShootPlan> plan) {
            var gear = plan.flatMap(ShootPlan::loadout).map(l -> l.gear()).orElse(List.of());
            return new Evidence(new Names(names(gear, "CAMERA"), new ArrayList<>(new TreeSet<>(stats.cameraCounts().keySet()))),
                new Names(names(gear, "LENS"), new ArrayList<>(new TreeSet<>(stats.lensCounts().keySet()))));
        }
        Names of(Kind kind) { return kind == Kind.CAMERA ? cameras : lenses; }
        private static List<String> names(List<GearSnapshot> gear, String type) {
            return gear.stream().filter(g -> g.type().equals(type)).map(CompatibilityValidator::name).distinct().toList();
        }
    }

    /** The outcome for one statement: the verdict and, when it failed, the recorded/planned pair that decided it. */
    public record Finding(Verdict verdict, Optional<Kind> kind, String recorded, String planned, Optional<Comparison> comparison) {
        static final Finding NONE = new Finding(Verdict.NONE, Optional.empty(), "", "", Optional.empty());
        public boolean rejected() { return verdict == Verdict.UNSUPPORTED || verdict == Verdict.CONTRADICTED; }
        /** True for a rejected claim about one individual body or lens (not about a model). */
        public boolean body() { return rejected() && comparison.isEmpty(); }
    }

    // Markers that replace mentions; private-use characters cannot occur in model text.
    private static final String R = "\uE001", P = "\uE002";
    private static final String KIND = "(?:cameras?|bod(?:y|ies)|lens(?:es)?|models?)\\b(?!'s|\\s*(?:range|ranges|coverage|focal|settings?|data|metadata|names?))";
    private static final String THE = "(?:(?:the|your|its|this|that)\\s+)?(?:approved\\s+)?(?:plann?ed\\s+|plan's\\s+)?";
    private static final String PLAN_ZH = "(?:批准)?(?:计划|方案)?(?:中)?的?";
    /** At most one word between the article and a mention ("the planned name Sony Alpha 7 IV", "the planned body P"). */
    private static final String ONE_WORD = "(?:[\\p{L}\\p{N}']+\\s+)?";
    /** Phrases that claim the recorded equipment is not the planned one. */
    private static final List<Pattern> DIFFERENCE = List.of(
        Pattern.compile("(?iu)\\b(?:differ\\w*|different|distinct|mismatch\\w*|deviat\\w*)\\s+(?:\\w+\\s+){0,3}?(?:from|to|than|with)\\s+" + THE + "(?:" + ONE_WORD + P + "|" + KIND + ")"),
        Pattern.compile("(?iu)\\b(?:instead of|rather than|in place of)\\s+" + THE + "(?:" + ONE_WORD + P + "|" + KIND + ")"),
        Pattern.compile("(?iu)\\bnot\\s+(?:one of\\s+)?" + THE + P),
        Pattern.compile("(?iu)\\bnot\\s+(?:one of\\s+)?(?:the|your)\\s+(?:approved\\s+)?plann?ed\\s+" + KIND),
        Pattern.compile("(?iu)" + R + "[^.;。；]{0,80}?\\b(?:is|was|were|are)\\s+(?:a\\s+|an\\s+)?(?:different|another|other|distinct|separate)\\s+(?:camera|lens|body|model|unit)"),
        Pattern.compile("(?iu)\\b(?:a\\s+)?(?:different|another|other|separate|distinct)\\s+(?:camera|lens|body|model)s?\\s+(?:than|from)\\s+(?:the\\s+)?(?:plann?ed|plan\\b|" + P + ")"),
        Pattern.compile("(?iu)" + P + "[^.;。；]{0,60}?\\b(?:was|were)\\s+(?:not used|replaced|swapped|switched|substituted)"),
        Pattern.compile("(?iu)\\b(?:switched|swapped|changed)\\s+(?:\\w+\\s+){0,3}?to\\s+(?:the\\s+)?" + R),
        Pattern.compile("(?iu)" + R + "[^.;。；]{0,80}?\\b(?:does|did|do)(?: not|n't) match\\s+" + THE + "(?:" + P + "|" + KIND + ")"),
        Pattern.compile("(?u)与\\s*" + PLAN_ZH + "\\s*(?:" + P + "|相机|机身|镜头)\\s*(?:不同|不一致|不符)"),
        Pattern.compile("(?u)(?:而非|而不是|并非|不是|不同于|有别于)\\s*" + PLAN_ZH + "\\s*" + P),
        Pattern.compile("(?u)" + R + "\\s*[^。；;]{0,30}?(?:是|为)\\s*(?:另一|不同)(?:的)?(?:台|款|部|只|支)?(?:相机|机身|镜头|型号)"));
    /** Phrases that claim the recorded equipment is the planned model. */
    private static final List<Pattern> SAMENESS = List.of(
        Pattern.compile("(?iu)" + R + "[^.;。；]{0,60}?\\b(?:matche[sd]|matching|corresponds? to|is the same (?:camera |lens )?model as|same (?:camera |lens )?model as|identical to|is the planned|was the planned)\\s+" + THE + P),
        Pattern.compile("(?iu)" + R + "\\s*[(,—-]?\\s*(?:i\\.e\\.,?\\s*|that is,?\\s*)?(?:the\\s+)?plann?ed\\s+" + P),
        Pattern.compile("(?iu)" + P + "[^.;。；]{0,80}?\\b(?:recorded|appears|appeared|listed|shows? up|logged)\\s+(?:in\\s+(?:the\\s+)?(?:EXIF|metadata)\\s+)?as\\s+" + R),
        Pattern.compile("(?u)" + R + "\\s*(?:即|就是|即为|正是)\\s*" + PLAN_ZH + "\\s*" + P),
        Pattern.compile("(?u)" + P + "[^。；;]{0,30}?记录为\\s*" + R));
    /** Wording that relates the names, not the models ("recorded under another name"). */
    private static final Pattern NAME_LEVEL = Pattern.compile("(?iu)\\b(?:names?|named|naming|label(?:s|led|ed)?|spell(?:ed|ing|s)?|written as|"
        + "under (?:the|this|that|a|another|a different) name)\\b|名称|名字|命名|写法");
    /** Wording that states a model difference outright, which the name-level reading cannot excuse. */
    private static final Pattern MODEL_DIFFERENCE = Pattern.compile("(?iu)\\b(?:different|another|other|separate) (?:camera|lens|body|model|unit)\\b"
        + "|\\bnot (?:the|your) (?:approved )?planned (?:camera|lens|body|model)\\b|不同(?:的)?(?:相机|机身|镜头|型号)|另一(?:台|款|只|支|部)");
    /** Wording that says the two names are the same model, which keeps a statement about differing names. */
    private static final Pattern ALIAS = Pattern.compile("(?iu)\\b(?:same (?:camera |lens )?model|aliases?|another name for|other name for|"
        + "different names? (?:for|of) the same)\\b|同一型号|相同型号|同款|别名");
    private static final Pattern BODY = Pattern.compile("(?iu)\\b(?:same|identical|exact|individual|physical)\\s+(?:physical\\s+)?(?:camera\\s+|lens\\s+)?(?:bod(?:y|ies)|units?|copy|copies|device)\\b"
        + "|\\bsame physical (?:camera|lens)\\b|\\bserial(?: numbers?)?\\b|同一台|同一部|同一机身|同一只镜头|序列号");
    private static final Pattern UNCERTAIN = Pattern.compile("(?iu)\\b(?:cannot|can't|can not|could not|couldn't|unknown|unclear|not known|"
        + "(?:does|do|did)(?: not|n't) (?:show|identify|tell|reveal|record|say|indicate|prove|confirm)|no way|impossible|whether|uncertain)\\b"
        + "|无法|不能|未知|不清楚|是否|不确定");

    /** The verdict for one statement; NONE when it relates no recorded and planned equipment. */
    public static Finding check(String statement, Evidence evidence) {
        if (statement == null || statement.isBlank() || evidence == null) return Finding.NONE;
        String text = statement.strip();
        boolean uncertain = UNCERTAIN.matcher(text).find();
        if (BODY.matcher(text).find() && !uncertain) return new Finding(Verdict.UNSUPPORTED, Optional.empty(), "", "", Optional.empty());
        Finding worst = Finding.NONE;
        for (Kind kind : Kind.values()) {
            var finding = check(kind, text, evidence.of(kind), uncertain);
            if (finding.verdict().ordinal() > worst.verdict().ordinal()) worst = finding;
        }
        return worst;
    }

    private static Finding check(Kind kind, String text, Names names, boolean uncertain) {
        if (names.planned().isEmpty() || names.recorded().isEmpty()) return Finding.NONE;
        var recorded = new LinkedHashSet<String>(); var planned = new LinkedHashSet<String>();
        String marked = mark(kind, text, names, recorded, planned);
        boolean difference = DIFFERENCE.stream().anyMatch(p -> p.matcher(marked).find());
        boolean sameness = !difference && !uncertain && SAMENESS.stream().anyMatch(p -> p.matcher(marked).find());
        if (!difference && !sameness) return Finding.NONE;
        // A generic phrase ("a different camera than planned") relates every recorded name to every planned one.
        var left = recorded.isEmpty() ? names.recorded() : List.copyOf(recorded);
        var right = planned.isEmpty() ? names.planned() : List.copyOf(planned);
        if (recorded.isEmpty() && planned.isEmpty() && !Pattern.compile("(?iu)" + (kind == Kind.CAMERA ? "\\b(?:cameras?|bod(?:y|ies))\\b|相机|机身" : "\\blens(?:es)?\\b|镜头")).matcher(text).find())
            return Finding.NONE;
        Finding worst = new Finding(Verdict.SUPPORTED, Optional.of(kind), "", "", Optional.empty());
        for (String r : left) for (String p : right) {
            var comparison = EquipmentIdentity.compare(kind, r, p);
            Verdict verdict = difference ? differenceVerdict(comparison, text, uncertain) : samenessVerdict(comparison);
            if (verdict.ordinal() > worst.verdict().ordinal()) worst = new Finding(verdict, Optional.of(kind), r, p, Optional.of(comparison));
        }
        return worst;
    }
    private static Verdict differenceVerdict(Comparison comparison, String text, boolean uncertain) {
        boolean namesOnly = NAME_LEVEL.matcher(text).find() && !MODEL_DIFFERENCE.matcher(text).find();
        return switch (comparison.relation()) {
            case DIFFERENT_MODEL -> Verdict.SUPPORTED;
            case SAME_MODEL -> ALIAS.matcher(text).find() || namesOnly ? Verdict.SUPPORTED : Verdict.CONTRADICTED;
            case UNKNOWN -> uncertain || namesOnly ? Verdict.SUPPORTED : Verdict.UNSUPPORTED;
        };
    }
    private static Verdict samenessVerdict(Comparison comparison) {
        return switch (comparison.relation()) {
            case SAME_MODEL -> Verdict.SUPPORTED;
            case DIFFERENT_MODEL -> Verdict.CONTRADICTED;
            case UNKNOWN -> Verdict.UNSUPPORTED;
        };
    }

    /** One name a spelling can denote: whether it is a planned name and whether the spelling is the name's own. */
    private record Candidate(String name, boolean planned, boolean own) { }
    private static final Pattern AFTER_PLANNED = Pattern.compile("(?isu).*(?:\\bplann?ed\\s+|计划(?:中)?的?\\s*)$");
    private static final Pattern AFTER_RECORDED = Pattern.compile("(?isu).*(?:\\b(?:recorded|actual|used)\\s+|(?:记录(?:的|为)?|实际(?:使用)?的?)\\s*)$");

    /**
     * The statement with every mention of a recorded name replaced by R and of a planned name by P, longest spelling
     * first. A spelling that denotes several names (a catalog alias of both, or one name both planned and recorded)
     * goes to a planned name right after "planned", otherwise to the name whose own spelling it is, recorded first.
     */
    private static String mark(Kind kind, String text, Names names, Set<String> recorded, Set<String> planned) {
        var candidates = new LinkedHashMap<List<String>, List<Candidate>>();
        for (boolean isPlanned : List.of(false, true))
            for (String name : isPlanned ? names.planned() : names.recorded()) {
                var own = EquipmentIdentity.strippedTokens(name);
                for (var tokens : EquipmentIdentity.spellings(kind, name))
                    candidates.computeIfAbsent(tokens, key -> new ArrayList<>()).add(new Candidate(name, isPlanned, tokens.equals(own)));
            }
        var spellings = new ArrayList<>(candidates.keySet());
        spellings.sort(Comparator.comparingInt((List<String> tokens) -> tokens.stream().mapToInt(String::length).sum()).reversed());
        String result = text;
        for (var tokens : spellings) {
            var choices = candidates.get(tokens);
            Matcher m = pattern(tokens).matcher(result);
            var out = new StringBuilder(); int last = 0;
            while (m.find()) {
                String before = result.substring(Math.max(0, m.start() - 16), m.start());
                boolean afterPlanned = AFTER_PLANNED.matcher(before).matches(), afterRecorded = AFTER_RECORDED.matcher(before).matches();
                var chosen = choices.stream().filter(c -> afterPlanned ? c.planned() : !afterRecorded || !c.planned())
                    .sorted(Comparator.comparing((Candidate c) -> !c.own()).thenComparing(c -> afterPlanned != c.planned()))
                    .findFirst().orElse(choices.getFirst());
                out.append(result, last, m.start()).append(' ').append(chosen.planned() ? P : R).append(' ');
                (chosen.planned() ? planned : recorded).add(chosen.name());
                last = m.end();
            }
            if (last > 0) { out.append(result.substring(last)); result = out.toString(); }
        }
        return result.replaceAll("\\s+", " ");
    }
    /**
     * A spelling's tokens as a pattern over raw text: an optional leading manufacturer ("Sony", "NIKON CORPORATION"),
     * optional separators between tokens, and "alpha" also written α or a.
     */
    private static Pattern pattern(List<String> tokens) {
        var body = new StringBuilder("(?<![\\p{L}\\p{N}])(?:" + EquipmentIdentity.MAKER_PREFIX + ")?");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) body.append("[\\s\\-_./]*");
            String token = tokens.get(i);
            body.append(token.equals("alpha") ? "(?:alpha|α|a)" : Pattern.quote(token));
        }
        body.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(body.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A saved review statement that the identity check would reject today (shown with a warning, never rewritten). */
    public record Conflict(String section, String statement, Finding finding) { }

    /**
     * Saved reviews predate this check and are immutable history: their statements are audited against the review's own
     * frozen statistics and the plan revision it was compared with, and conflicts are reported for display only.
     */
    public static List<Conflict> audit(PostShootReview review, PhotographySession session) {
        if (review == null || session == null) return List.of();
        var plan = session.plans().stream().filter(p -> p.id().equals(review.comparedPlanId()) && p.revision() == review.comparedRevision()).findFirst();
        var evidence = Evidence.of(review.statistics(), plan);
        var conflicts = new ArrayList<Conflict>();
        var sections = new LinkedHashMap<String, List<String>>();
        sections.put("summary", review.summary().isBlank() ? List.of() : List.of(review.summary()));
        sections.put("technical", review.technicalObservations()); sections.put("equipment", review.equipmentObservations());
        sections.put("exposure", review.exposureObservations()); sections.put("lessons", review.lessons()); sections.put("improvements", review.improvements());
        sections.forEach((section, items) -> items.forEach(item -> {
            var finding = check(item, evidence);
            if (finding.rejected()) conflicts.add(new Conflict(section, item, finding));
        }));
        return conflicts;
    }
}
