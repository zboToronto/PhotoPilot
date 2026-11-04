package com.photopilot.command;

import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.Loadout;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 73 (zero-based): {F10; Command receiver; immutable after approval}
 * Structured shoot plan: the validated owned-gear loadout with camera/lens assignments, camera settings, strategy,
 * timed schedule windows, preparation, non-equipment checklist items, ordered shot priorities, the confirmed fixed
 * constraints it was built against and the deterministic calculation evidence. A plan id with increasing revision
 * numbers is one plan's history; approved revisions are never changed. Revisions are prepared on
 * {@link #copyForRevision()} drafts, which only the package's commands may change.
 */
public class ShootPlan {
    /** Plan decisions a revision may change only when a command declares it (fixed constraints never). */
    public static final List<String> DECISIONS = List.of("loadout", "settings", "strategy", "schedule", "preparation", "checklist",
        "shotPriorities", "tradeOffs", "equipmentNotes", "sessionInfo", "fixedConstraints");
    /** Explanations and evidence regenerated with every proposal; they record, but are not, plan decisions. */
    public static final List<String> EXPLANATIONS = List.of("summary", "limitations", "calculations");
    /** Setting names the planner may propose; numeric ones are deterministically checked. */
    public static final List<String> SETTINGS = List.of("exposureMode", "shutterSpeed", "aperture", "iso", "exposureCompensation",
        "focalLength", "autofocusMode", "afArea", "driveMode", "stabilization", "meteringMode", "whiteBalance", "fileFormat");
    /** Strategy aspects in display order. */
    public static final List<String> STRATEGY = List.of("approach", "technical", "autofocus", "exposure", "composition");
    /** Session information keys in display order (derived from the confirmed requirement, never from AI). */
    public static final List<String> SESSION_INFO = List.of("subject", "goals", "location", "date", "time", "duration", "constraints");

    private UUID id;
    private int revision;
    private boolean approved;
    private List<String> provisionalReasons;
    private Optional<Loadout> loadout;
    private Map<String, String> settings;
    private List<ShootingWindow> windows;
    private List<String> checklist;
    private List<String> preparation;
    private List<String> shotPriorities;
    private Map<String, String> fixedConstraints;
    private List<CalculationResult> calculations;
    private String summary;
    private Map<String, String> sessionInfo;
    private Map<String, String> strategy;
    private List<String> equipmentNotes;
    private List<String> tradeOffs;
    private List<String> limitations;
    private String changeSummary;
    /** Draft-only: sections changed by commands since copyForRevision(); never persisted. */
    private transient Set<String> revisedSections = new LinkedHashSet<>();
    /** Draft-only: a complete regeneration after the confirmed requirement changed (not a command revision). */
    private transient boolean regeneration;

    public ShootPlan(UUID id, int revision, boolean approved, List<String> provisionalReasons, Optional<Loadout> loadout, Map<String, String> settings, List<ShootingWindow> windows, List<String> checklist, List<String> preparation, List<String> shotPriorities, Map<String, String> fixedConstraints, List<CalculationResult> calculations) {
        this(id, revision, approved, provisionalReasons, loadout, settings, windows, checklist, preparation, shotPriorities, fixedConstraints,
            calculations, "", Map.of(), Map.of(), List.of(), List.of(), List.of(), "");
    }

    /** Full F10 plan; the 12-value constructor above is the original Stage 1 form (its extra sections are empty). */
    public ShootPlan(UUID id, int revision, boolean approved, List<String> provisionalReasons, Optional<Loadout> loadout, Map<String, String> settings,
            List<ShootingWindow> windows, List<String> checklist, List<String> preparation, List<String> shotPriorities, Map<String, String> fixedConstraints,
            List<CalculationResult> calculations, String summary, Map<String, String> sessionInfo, Map<String, String> strategy,
            List<String> equipmentNotes, List<String> tradeOffs, List<String> limitations, String changeSummary) {
        this.id = Objects.requireNonNull(id, "id");
        this.revision = revision;
        this.approved = approved;
        this.provisionalReasons = List.copyOf(provisionalReasons);
        this.loadout = Objects.requireNonNull(loadout, "loadout");
        this.settings = Map.copyOf(settings);
        this.windows = List.copyOf(windows);
        this.checklist = List.copyOf(checklist);
        this.preparation = List.copyOf(preparation);
        this.shotPriorities = List.copyOf(shotPriorities);
        this.fixedConstraints = Map.copyOf(fixedConstraints);
        this.calculations = List.copyOf(calculations);
        this.summary = Objects.requireNonNull(summary, "summary");
        this.sessionInfo = Map.copyOf(sessionInfo);
        this.strategy = Map.copyOf(strategy);
        this.equipmentNotes = List.copyOf(equipmentNotes);
        this.tradeOffs = List.copyOf(tradeOffs);
        this.limitations = List.copyOf(limitations);
        this.changeSummary = Objects.requireNonNull(changeSummary, "changeSummary");
        if (revision < 1) throw new IllegalArgumentException("Plan revision must be positive.");
        if (approved && !this.provisionalReasons.isEmpty())
            throw new IllegalArgumentException("An approved plan cannot be provisional.");
    }

    /** Unapproved draft of the next revision; this plan (the saved base) is never changed. */
    public ShootPlan copyForRevision() {
        return new ShootPlan(id, revision + 1, false, provisionalReasons, loadout, settings, windows, checklist, preparation, shotPriorities,
            fixedConstraints, calculations, summary, sessionInfo, strategy, equipmentNotes, tradeOffs, limitations, "");
    }

    /** Approved copy of a validated preview (the caller has revalidated it); a provisional plan cannot be approved. */
    public Result<ShootPlan> approvedCopy() {
        if (!provisionalReasons.isEmpty()) return Result.failureKey("plan.error.provisional");
        return Result.success(new ShootPlan(id, revision, true, provisionalReasons, loadout, settings, windows, checklist, preparation, shotPriorities,
            fixedConstraints, calculations, summary, sessionInfo, strategy, equipmentNotes, tradeOffs, limitations, changeSummary));
    }

    /** Draft copy with regenerated explanations and evidence; decisions and the revised-section record are kept. */
    public ShootPlan withExplanation(String summary, String changeSummary, List<String> limitations, List<CalculationResult> calculations,
            List<String> provisionalReasons) {
        if (approved) throw new IllegalStateException("An approved plan is immutable.");
        var copy = new ShootPlan(id, revision, false, provisionalReasons, loadout, settings, windows, checklist, preparation, shotPriorities,
            fixedConstraints, calculations, summary, sessionInfo, strategy, equipmentNotes, tradeOffs, limitations, changeSummary);
        copy.revisedSections = new LinkedHashSet<>(revisedSections()); copy.regeneration = regeneration;
        return copy;
    }

    /** Draft copy marked as a full regeneration of this plan id after the confirmed requirement changed. */
    public ShootPlan asRegeneration() {
        if (approved) throw new IllegalStateException("An approved plan is immutable.");
        var copy = new ShootPlan(id, revision, false, provisionalReasons, loadout, settings, windows, checklist, preparation, shotPriorities,
            fixedConstraints, calculations, summary, sessionInfo, strategy, equipmentNotes, tradeOffs, limitations, changeSummary);
        copy.regeneration = true; return copy;
    }

    public UUID id() { return id; }
    public int revision() { return revision; }
    public boolean approved() { return approved && provisionalReasons.isEmpty(); }
    public List<String> provisionalReasons() { return provisionalReasons; }
    public Optional<Loadout> loadout() { return loadout; }
    public Map<String, String> settings() { return settings; }
    public List<ShootingWindow> windows() { return windows; }
    public List<String> checklist() { return checklist; }
    public List<String> preparation() { return preparation; }
    public List<String> shotPriorities() { return shotPriorities; }
    public Map<String, String> fixedConstraints() { return fixedConstraints; }
    public List<CalculationResult> calculations() { return List.copyOf(calculations); }
    public String summary() { return summary; }
    public Map<String, String> sessionInfo() { return sessionInfo; }
    public Map<String, String> strategy() { return strategy; }
    public List<String> equipmentNotes() { return equipmentNotes; }
    public List<String> tradeOffs() { return tradeOffs; }
    public List<String> limitations() { return limitations; }
    public String changeSummary() { return changeSummary; }
    public Set<String> revisedSections() { return revisedSections == null ? Set.of() : Set.copyOf(revisedSections); }
    public boolean regeneration() { return regeneration; }

    /** Settings key: the setting name, or {@code name@cameraSourceId} for one camera body of the loadout. */
    public static String settingKey(String setting, Optional<UUID> camera) { return camera.map(c -> setting + "@" + c).orElse(setting); }
    public static String settingName(String key) { int at = key.indexOf('@'); return at < 0 ? key : key.substring(0, at); }
    public static Optional<UUID> settingCamera(String key) {
        int at = key.indexOf('@');
        if (at < 0) return Optional.empty();
        try { return Optional.of(UUID.fromString(key.substring(at + 1))); } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    /** Comparable value of every section, so revisions can report changed and unchanged decisions. */
    public Map<String, Object> sections() {
        var values = new LinkedHashMap<String, Object>();
        values.put("loadout", loadout.map(l -> List.of(l.gear().stream().map(g -> g.sourceGearId() + ":" + g.selectedQuantity()).sorted().toList(),
            new TreeMap<>(l.cameraLensAssignments()).toString())).orElse(List.of()));
        values.put("settings", new TreeMap<>(settings));
        values.put("strategy", new TreeMap<>(strategy));
        values.put("schedule", windows.stream().map(w -> w.start() + "|" + w.end() + "|" + w.arrival() + "|" + w.conditions() + "|" + w.estimated()).toList());
        values.put("preparation", preparation); values.put("checklist", checklist); values.put("shotPriorities", shotPriorities);
        values.put("tradeOffs", tradeOffs); values.put("equipmentNotes", equipmentNotes);
        values.put("sessionInfo", new TreeMap<>(sessionInfo)); values.put("fixedConstraints", new TreeMap<>(fixedConstraints));
        values.put("summary", summary); values.put("limitations", limitations);
        values.put("calculations", calculations.stream().map(c -> c.input().operation() + new TreeMap<>(c.input().parameters()).toString()
            + new TreeMap<>(c.values()).toString()).toList());
        return values;
    }
    /** Sections whose content differs from {@code other}, in display order. */
    public List<String> differingSections(ShootPlan other) {
        var mine = sections(); var theirs = other.sections();
        return mine.keySet().stream().filter(key -> !Objects.equals(mine.get(key), theirs.get(key))).toList();
    }

    private void editable() { if (approved) throw new IllegalStateException("An approved plan is immutable."); }
    private void revised(String section) { if (revisedSections == null) revisedSections = new LinkedHashSet<>(); revisedSections.add(section); }

    /** Receiver operation of ReplaceLoadoutCommand; settings bound to bodies no longer carried are dropped and recorded. */
    void replaceLoadout(Loadout loadout) {
        editable();
        this.loadout = Optional.of(Objects.requireNonNull(loadout, "loadout"));
        revised("loadout");
        var cameras = new HashSet<UUID>();
        loadout.gear().stream().filter(g -> g.type().equals("CAMERA")).map(GearSnapshot::sourceGearId).forEach(cameras::add);
        var kept = new LinkedHashMap<String, String>();
        settings.forEach((key, value) -> { if (settingCamera(key).map(cameras::contains).orElse(true)) kept.put(key, value); });
        if (kept.size() != settings.size()) { settings = Map.copyOf(kept); revised("settings"); }
    }
    void reviseEquipmentNotes(List<String> notes) { editable(); equipmentNotes = List.copyOf(notes); revised("equipmentNotes"); }

    void reviseSchedule(List<ShootingWindow> windows) {
        editable();
        this.windows = List.copyOf(windows);
        revised("schedule");
    }

    void reviseStrategy(ReviseStrategyCommand change) {
        editable();
        var sections = change.sections();
        if (sections.contains("settings")) { settings = change.settings(); revised("settings"); }
        if (sections.contains("strategy")) { strategy = change.strategy(); revised("strategy"); }
        if (sections.contains("shotPriorities")) { shotPriorities = change.shotPriorities(); revised("shotPriorities"); }
        if (sections.contains("tradeOffs")) { tradeOffs = change.tradeOffs(); revised("tradeOffs"); }
        if (sections.contains("preparation")) { preparation = change.preparation(); revised("preparation"); }
        if (sections.contains("checklist")) { checklist = change.checklist(); revised("checklist"); }
    }
}
