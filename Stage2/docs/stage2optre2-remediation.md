# Stage2OptRe2 deterministic bug remediation (2026-11-24)

Input: the second independent execution and design conformity review `Project/Stage2OptRe2.pdf` (sections 12–16) and `Project/Stage2OptRe2_evidence/` (outside Git, read only), with Stage2Review2 issues #01–#05 and the RE2 risk tracking. Scope: the twelve authorized tasks below; feature freeze. Nothing was committed or pushed.

## A. Baseline

| Item | Value |
| --- | --- |
| Git HEAD at start | `1714b83d0715454e62e14781e96f4fa8ca5216bb` (`main`; read from `.git/HEAD`, Git is not on PATH) |
| Working tree at start | The large uncommitted 2026-11-23/24 work (execution feedback, session workflow, capability report, F08/F09 changes). `Stage2/docs`, `src`, `submission` (577 files) were byte-identical, by SHA-256, to the reviewer's `build/source-after.txt`, so the reviewed tree is the starting tree. A start manifest was kept to identify every file changed here (section E). |
| OS / tools | Windows 11 Home 10.0.22631; bundled JDK 21.0.13 (Microsoft); Maven Wrapper 3.9.9 offline (`-o`, copied `.mvn/repository`). |
| Isolation | Builds and tests ran in scratch copies of `Stage2` (with `third-party`, without `target`, `.tools`, `.env`), fresh `PHOTOPILOT_DATA_DIR`/`PHOTOPILOT_CONFIG_DIR`, no API key, `PHOTOPILOT_LIVE_GEMINI=0`, `JAVA_TOOL_OPTIONS` cleared. AI and provider answers come only from the loopback doubles (`FakeGemini`, `FakeEnvironmentProviders`); no live Gemini, weather, sun, Wikipedia or Lensfun request was made. The user's real profile and their running PhotoPilot GUI were not touched. |
| Evidence read | `Stage2OptRe2_evidence/scripts/audit_data.py` (defects D01–D04, X items, regression queue), `write_report.py` (sections 12–16), `cli/C18.json`, `C19.json`, `C42.json`, `C49.json`, `scripts/TimingAudit.java` (the C49 loopback harness), `build/repeat.log`, `dropdown-isolated.log`. Logs of this remediation (baseline and final suite, C49 runs, F04 fresh-process runs, probes, smoke) are kept outside Git in `EECS3311/tmp/Stage2OptRe2-remediation-evidence/`. The PDF itself could not be rendered here (no PDF renderer); its text was read from the generator sources it was built from. |
| Environmental limits | Shared desktop (the user's own PhotoPilot GUI was open; real-window GUI tests share the screen); Windows Defender on-access scanning active; no macOS/Linux; no live providers (out of scope). |

Baseline full run of the unmodified tree in this environment: `mvnw.cmd -B -o clean verify` → **877 run, 0 failures, 0 errors, 6 skipped** (`DropdownGuiTest` 5/5 passed in that run). The RE2 clean rerun had 877/869/2/0/6 with the two failures in `DropdownGuiTest`.

## B. Remediation matrix

| Task | Priority | Initial finding | Reproduction result | Root cause | Changed files | Regression tests | Final status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01 F10 hard schedule constraint (D04/C49) | P1 | Confirmed defect | Reproduced: the reviewer's own harness, real external CLI JVM, unmodified copy: "Validation passed", revision 1 approved, Planned, exit 0. Fixed copy: "Validation failed … outside the confirmed shooting time (2026-11-27 14:00–15:00 America/Toronto)", not approvable, exit 2, no plan in the JSON store. | `PlanValidator.schedule` checked windows only against the F09 availability (13:00–17:00) once F09 evidence applied; the confirmed F03 start time was not a bound. | `PlanValidator`, `PlanAssembly`, bundles | `PlanShootingTimeTest` (16), `PlanApprovalGuiTest#guiRefusesAWindowBeforeTheConfirmedStartTimeLikeTheCli` | FIXED AND VERIFIED |
| 02 F07/F10 stale profile (Review2 #03) | P2 | Potential risk | Confirmed: on the unmodified code `StaleProfileConsistencyTest` fails (F10 reports ready and regenerates with a profile F07 rejects as stale). | F07 used `usableProfile` (profile must still fit the confirmed requirement); F10 `planningInputs`/`planInputs` and `PlanValidator` only checked `reviewed`. | `PlanningService`, `PlanValidator` | `StaleProfileConsistencyTest` (2) | FIXED AND VERIFIED |
| 03 F11 aggregate EXIF limits (X02/Review2 #04) | P2 | Potential risk | Confirmed: the unmodified adapter accepts three individually valid 20,000-entry exports as one 60,000-record scan (no aggregate bound; 2000 × 20,000 = 40 M possible). | Limits were per file (size, entries) and per selection (2000 files) only. | `ExifLibraryAdapter`, bundles | `AggregateExifLimitTest` (5) | FIXED AND VERIFIED |
| 04 ExifTool cache integrity | P2 | Potential risk | Confirmed by code and test: `complete()` checked marker, presence and size only, so a same-length corrupted cached `perl.exe` or module would be executed. | Size-only validation of the published cache. | `ExifToolRuntime` | `ExifToolRuntimeTest` +4 | FIXED AND VERIFIED |
| 05 F11 Chinese quantifiers, multi-session (Review2 #05) | P2 | Potential risk | Confirmed: on the unmodified code 6 of 7 behaviour tests fail; e.g. "本次共导入了五张照片" (7 imported), "本次使用了两台相机" (1 camera), "Five photos were imported", "本次导入了六张照片" (6 = the earlier session) were all kept; a statement mixing two earlier sessions' figures was kept. | The claim scanner read Arabic digits only; 台/支/款 etc. were not count units; history figures were matched per figure across all related sessions. | `ReviewClaims` | `ReviewNumeralClaimsTest` (8), `ReviewIdentityWorkflowTest` +1 | FIXED AND VERIFIED |
| 06 F07 CLI empty range (D01/C18) | P3 | Confirmed defect | Reproduced: `match details 1` / `match select 1` with zero candidates printed "1 to 0" (new test fails on the unmodified code). | `MatchCommands.candidate` used the range template for an empty list. | `MatchCommands`, bundles | `MatchCandidateRangeCliTest` (2) | FIXED AND VERIFIED |
| 07 F04 "Checking {0}" (D02/G10/G18) | P3 | Confirmed defect | Confirmed from source and evidence G18: queued stages are rendered from `progress.stage.<key>` without arguments. The same class of defect existed for queued `env.sun` ("Retrieving sun times ({0})") and `match.rank` ("Ranking {0} candidates"). | Pending stage rows used the active, parameterized template. | `OperationStage`, `OperationProgressView`, bundles | `ProgressPlaceholderGuiTest` (2) | FIXED AND VERIFIED |
| 08 QA-01 dropdown reliability | P3 | Intermittent | RE2 failures: (1) "pointer moved by another input source" (environmental, shared desktop); (2) hover not reached with the window active. Not reproduced here: 5/5 in the baseline full run and 5/5 isolated after the change (section D). New deterministic state check: no transparent selected or hovered option in any preset or custom hue at 0/40/62/100 % opacity. Found: selected-option text drops to 4.01–4.49:1 on the most see-through settings (section F). | Test: a just-opened list can miss the first OS move event. Production: no rendering defect found in the hover/selected/focus rules. | `DropdownGuiTest` (test infrastructure) | `DropdownThemeStatesTest` (1), `DropdownGuiTest` hardened | ENVIRONMENTAL ISSUE (hover/pointer); readability gap at ≤ 40 % opacity: UNRESOLVED |
| 09 F10 historical evidence freshness (Review2 #02) | P3 | Design risk | Confirmed presentation ambiguity: saved revisions showed model-written window conditions with no hint they are approval-time evidence; a revision keeping the schedule did not say so. | Accepted design (unchanged windows keep their approval evidence) without a label. | `PlanPresentation`, bundles | `PlanEvidencePresentationTest` (2) | FIXED AND VERIFIED |
| 10 Slow disk on the FX thread | P3 | Potential risk | Investigated: heavy I/O (image open/sanitize, avatar preparation, EXIF scans, feature saves) already runs on `UiOperations`/`OperationRunner` background lanes. Appearance and language preference writes run synchronously on the FX thread (lock, temp file, fsync, atomic replace): measured median 5.1 ms (p95 6.2 ms, max 13.7 ms) and 1.9 ms on this disk, below one frame. | — | none | none new (existing `UiOperationsThreadingTest`, `ExecutionFeedbackGuiTest`) | NOT REPRODUCED (no meaningful blocking measured); residual risk documented |
| 11 F04 valid image unreadable (C19) | P3 | Anomaly | Not reproduced: three fresh real-CLI JVMs (damaged → valid in one process, repeated loads, spaces and non-ASCII paths, missing, unsupported) all behaved correctly; original unchanged; no temp folder left. C19 failed both files with `import.error.unreadable` (an `IOException`/`SecurityException` before the content check), C42 seven minutes later gave the correct content error for the damaged file and loaded the valid one, with identical commands and paths. | Environmental (file access failure at C19 time; files written ~1 min earlier, on-access scanning active); no decoder state is kept between loads. | none | `ImportLoadSequenceCliTest` (1) | NOT REPRODUCED |
| 12 JavaFX CSS lookup warnings | P3 | Risk | All 51 `CssStyleHelper` warnings of the baseline suite came from `ProfileGuiTest`; captured stack: `ProfileGuiTest.ready()` → `workspace.applyCss()` while the main window was hidden behind the user chooser (`showing=false`). After the change: 0 in that class (section D). | Test harness forced a CSS pass on a hidden window; production calls `applyCss` only from visible pages. No application CSS defect. | `ProfileGuiTest` (test infrastructure) | `ProfileGuiTest` (unchanged assertions) | ENVIRONMENTAL ISSUE (test-induced); no product defect |

## C. Technical explanation of the fixes

### TASK 01 — F03 start time and duration bound every F10 window

- **Before.** With an applicable F09 assessment, `PlanAssembly.timing` used its availability (13:00–17:00) and `PlanValidator.schedule` validated new windows only through `FeasibilityValidator.checkTiming` against it, plus the total-duration check. The confirmed 14:00 start was never compared, so 13:10–13:45 passed preview and approval (C49, two profiles).
- **Expected.** Windows lie in the intersection of the confirmed shooting time (F03 start for the confirmed duration) and the availability, compared as instants after zone normalisation.
- **Change.** `PlanValidator.ShootingTime` (from the confirmed requirement: start, optional end = start + duration). In `schedule`, every structurally valid window, fresh or carried from the approved base, must lie inside it before any other timing check (`plan.violation.requirementTime`, or `plan.violation.beforeStart` without a duration). `ShootingTime.within(availability)` gives the permitted interval; `PlanAssembly.evidence` sends it to the model as `timing.windowsFrom/windowsUntil` with one added sentence in prompt rule 7. The validator stays authoritative regardless of the model.
- **Semantics kept.** Arrival is preparation lead time: it may precede the F03 start but stays inside the availability (F09 availability; with requirement-only timing the availability starts at the confirmed start, as before). No start time → no position bound (the duration still limits the total). Ambiguous or nonexistent local times from the model are still rejected by `FeasibilityValidator.parseProposed`. Disjoint F09 evidence is still rejected by `EnvironmentEvidence` (status WINDOW) and timing falls back to the requirement.
- **Same rule everywhere.** `assess` is used by generation, preview, revision and approval (approval revalidates against the current requirement), so GUI and CLI share it. A window carried unchanged from a base approved before this fix is now reported when the requirement it is validated against forbids it; the stored revision is not changed.
- **Evidence.** C49 harness on both copies (section D); `PlanShootingTimeTest`: exact C49, wider/narrower/partial/identical/disjoint availability, inclusive boundaries and ±1 min, longer than the duration, before the start, start without duration, no timing, arrival rules, cross-midnight, New York vs Toronto zone, DST fall-back and spring-forward by instants, ambiguous/gap model times, a carried legacy window, approval after re-confirmation, a rejected revision preserving revision 1, partial and disjoint F09, the evidence JSON, CLI in English and Chinese, store bytes and fresh-instance readback; the GUI test shows the blocked preview with approval disabled and nothing written.

### TASK 02 — one profile-validity rule for F07 and F10

- `PlanningService.planningInputs` (generate, revise, approve) now requires `usableProfile(session)`: a reviewed profile that still validates against the confirmed requirement; otherwise the failure is `needs.error.stale` plus the conflict (already mapped to Gear Requirements in `NextSteps`). `planInputs` shows the same blockers (the GUI disables Generate; `plan show` lists them). `PlanValidator.assess` reports the same as missing input (defence in depth).
- Nothing is deleted: the stale profile and earlier approved revisions stay stored; after the profile is confirmed again, matching works and a regeneration becomes the next revision. The workflow milestones are unchanged (02 *Needs review*, 03 blocked, 05 not completed; the existing `SessionWorkflowServiceTest` pins milestone 05 for this case and still passes).

### TASK 03 — aggregate record bound for one scan

- `ExifLibraryAdapter.MAX_RECORDS = 50_000` (2.5 exports at the per-file maximum; one maximal export always fits), configurable through a new constructor for tests. When the records of a scan exceed it, the whole scan returns `review.error.tooManyRecords`; nothing partial is returned, so `ReviewService` saves nothing and earlier statistics stay byte-identical. Peak memory is bounded by the limit plus one file's records. The 2000-file and per-file limits, folder rules (no subfolders) and per-file cancellation are unchanged. No aggregate byte limit was added: bytes are read one file at a time and released, so total size only affects duration, which the existing per-file progress and cancellation cover.

### TASK 04 — cached ExifTool files verified by the bundled manifest

- `complete()` now also compares every cached file's SHA-256 with the manifest inside the application (the cache cannot vouch for itself). A damaged copy goes through the existing repair path (fresh staging extraction, verification, atomic publish, damaged copy moved aside); when the bundle cannot repair it, `resolve` fails as UNAVAILABLE and the damaged runtime is never run. The check runs once per runtime instance; repeated resolves reuse the command (100 resolves: 1 ms). Measured: steady verification 54 ms; the first read after a fresh extraction 2.4 s, which includes on-access virus scanning of the newly written executables. The in-place development layout (classes on disk) still checks presence and size, with full hashes checked by the build's tests.

### TASK 05 — counts in words and one earlier session per statement

- `ReviewClaims.numerals` rewrites, for validation only, counted quantities written in words: Chinese numerals before a counting word (张 幅 帧 台 部 支 只 颗 款 场 次 种 个) and English number words before count nouns. Idioms are left alone (上一次, 第二张, 这一张, 两次/两场 "both", 一个/一种/一次); a quantity that cannot be read exactly ("三四张", "five-six photos") makes the statement unverified (removed). The text shown and saved is the model's original.
- New count units: 台/部 count camera bodies, 支/只/颗 lenses, 款/个/种 what follows them; a number of sessions (场, "N 次拍摄", "sessions", "shoots") has no fact and stays unsupported.
- History figures of one statement must be supported by one and the same related session; "last time / 上次 / 上一次" must be the latest one by its date. Plural wording ("in past sessions") keeps the per-figure check.
- Saved reviews are not re-validated or rewritten (only new drafts are checked); `ReviewIdentityWorkflowTest` adds a saved Chinese count shown unchanged with the store byte-identical. All 25 existing `ReviewClaimsTest` cases and the PP-03 identity tests pass unchanged.

### TASK 06 — empty candidate list

- `MatchCommands.candidate` answers an empty list with `match.error.noCompatible` ("No compatible gear combinations are available. Adjust your requirements or inventory and run matching again." / "没有可用的兼容器材组合。请调整拍摄需求或器材库存后重新运行匹配。"), exit 2, no write. Non-empty lists keep "from 1 to N". Matching, ranking and the strict mount-label rule are unchanged (the zero-candidate fixture uses non-matching mount labels).

### TASK 07 — queued progress labels

- `OperationStage.PARAMETERIZED` names the planned stages whose active label needs an argument (`import.source`, `env.sun`, `match.rank`); `OperationStage.queued(key)` gives their queued labels (`progress.queued.*`: "Verifying equipment specifications" / "核验器材规格", "Retrieving sun times" / "获取日出日落时间", "Ranking candidates" / "为候选组合排序"). `OperationProgressView` uses it for pending rows and the "Then:" line. Active rows keep the argument ("Checking Wikipedia", "Checking Lensfun", in order). A catalogue test fails if a future planned stage uses a placeholder template without a queued label.

### TASK 09 — approval-time evidence labelled

- `PlanPresentation.section(…, "schedule")` adds, for approved revisions with windows, "Conditions are as recorded when this revision was approved, not a current forecast." / "条件为批准此修订时记录的内容，并非当前预报。"; `comparison` adds "Schedule kept from revision N with the evidence it was approved with; not re-checked against a new forecast." when a revision keeps the schedule. GUI and CLI both render through these methods. The retrieval timestamp line already stored in each revision's limitations is kept; no stored record changes; no provider request is made; changed windows still need current evidence.

## D. Test execution

| Run | Command (isolated copy) | Result |
| --- | --- | --- |
| Baseline, unmodified | `mvnw.cmd -B -o clean verify` | 877 run, 0 failures, 0 errors, 6 skipped |
| C49 harness, unmodified | `TimingAudit` (reviewer's harness) → real CLI JVM | Validation passed; "Plan revision 1 approved and saved … (state: Planned)"; child exit 0 |
| C49 harness, fixed | same | Validation failed (outside the confirmed shooting time); approval refused; "No approved plan revision found."; Draft; child exit 2; `sessions.json` has 0 plans |
| Fail-before evidence | new tests on the unmodified copy | `StaleProfileConsistencyTest` 2/2 fail; `ReviewNumeralClaimsTest` 6/7 behaviour tests fail; `MatchCandidateRangeCliTest` zero-candidate case fails; aggregate probe accepts 60,000 records |
| F04 fresh processes | real CLI JVM ×3, stdin batches | all expected outcomes; original SHA-256 unchanged |
| Isolated GUI | `-Dtest=DropdownGuiTest,…` | `DropdownGuiTest` 5/5 |
| Final full | `mvnw.cmd -B -o clean verify` | **922 run, 0 failures, 0 errors, 6 skipped** (877 + 45 new), JAR built, BUILD SUCCESS |
| Dropdown repeats | `-Dtest=DropdownGuiTest` ×3 after the change (plus once in the full run) | 5/5 each time |
| GUI smoke | `mvnw.cmd -B -o package javafx:run -DskipTests -Djavafx.args=--smoke-test` | `GUI_SMOKE_OK` |

New tests: 45 test methods (`PlanShootingTimeTest` 16, `StaleProfileConsistencyTest` 2, `AggregateExifLimitTest` 5, `ExifToolRuntimeTest` +4, `ReviewNumeralClaimsTest` 8, `ReviewIdentityWorkflowTest` +1, `MatchCandidateRangeCliTest` 2, `ProgressPlaceholderGuiTest` 2, `DropdownThemeStatesTest` 1, `PlanEvidencePresentationTest` 2, `ImportLoadSequenceCliTest` 1, `PlanApprovalGuiTest` +1). Changed existing tests (infrastructure only, no assertion removed or relaxed): `DropdownGuiTest` (hover helper re-issues one pointer move after 1.5 s; same assertion), `ProfileGuiTest` (`ready()` skips the explicit CSS pass while the window is hidden).

CSS warnings: baseline full run 51 `CssStyleHelper` warnings, all in `ProfileGuiTest`; after the change `ProfileGuiTest` alone: 0; final full run: **0**. The six skips are the existing opt-in cases (four live-Gemini smoke tests, two private-photo samples); no skip was added.

## E. Architecture and design impact

- No new layer, dependency, provider, model or persistence change; no stored format changed; all validation stays in the shared services and validators used by GUI and CLI through `PhotoPilotFacade`.
- Patterns unchanged: Strategy (ranking), Adapter (`ExifLibraryAdapter`, `ExifToolRuntime`), Command/State (plan revisions, session lifecycle), Observer (progress). `PlanValidator` remains the deterministic F10 gate; `ReviewClaims` remains the F11 claim gate.
- Traceability: F10 (UC10/UC11/UC14) validation rules, F06/F07 consistency, F11 (UC12/UC13) limits and claims, F04 feedback, F05 shutter tool runtime. Feature docs updated: [f10](f10-planning.md), [f11](f11-review.md), [f05](f05-inventory.md), [execution feedback](execution-feedback.md).
- Files changed in `Stage2` (from the start manifest): main `PlanPresentation`, `ExifLibraryAdapter`, `ExifToolRuntime`, `OperationStage`, `PlanAssembly`, `PlanningService`, `ReviewClaims`, `MatchCommands`, `OperationProgressView`, `PlanValidator`, the three message bundles (identical keys; `messages` = `messages_en` byte for byte; CRLF kept); tests as listed in D; docs as listed here.

## F. Remaining risks

- **Dropdown selected-text contrast at very high transparency (UNRESOLVED, product).** At the default 62 % opacity and above, selected-option text keeps ≥ 4.5:1 in every preset and custom hue. At 0 % (all accents) and 40 % (some dark custom hues) the worst background sample gives 4.01–4.49:1. Fixing it means retuning `-pp-selected-soft`/`-pp-accent-text` (visual identity), so it was left for a design decision; `DropdownThemeStatesTest` guards the current floor (≥ 4.0) and the AA level at default opacity. *Later the same day: fixed without retuning the palette, see section G.2.*
- **Theme initialization order (latent).** `Theme.Accent` constants call `Theme` statics and `Theme.DEFAULT` needs `Accent.BLUE`: touching `Accent` before `Theme` in a fresh JVM fails `Theme`'s initialization. The application and tests always initialize `Theme` first; the new test does so explicitly. Not changed (outside the twelve tasks). *Later the same day: fixed at its source, see section G.1.*
- **Preference writes on the FX thread (TASK 10).** Appearance and language writes (lock + fsync) are synchronous; ~2–6 ms here, but a slow or network configuration directory, or a lock held by another PhotoPilot process, would stall the UI for that time. Moving them needs ordering, failure feedback and profile-switch flush guarantees; not done during the freeze.
- **QA-01.** Real-pointer tests still depend on an undisturbed desktop; the RE2 hover miss was not reproduced, so the pointer re-issue is a mitigation, not a proven fix. The historical "transparent selected tint" was not reproduced; the deterministic check shows no transparent state.
- **C19.** The I/O-level failure was not reproduced; its environmental cause is inferred, not proven.
- **F11 claims.** The numeral reading is deliberately narrow (counting words and number words); approximate or descriptive wording stays outside it, and plural history claims keep the per-figure check.
- **External providers (out of scope).** Gemini 503/429/timeouts (IN-01) and live provider runs were not exercised; all results use loopback doubles. No macOS/Linux, no hands-on pointer session.

## G. Final GUI Stabilization — Theme Initialization and Dropdown Accessibility

Follow-up patch the same day (2026-11-24) for the two GUI items left open in section F. Feature freeze; nothing committed or pushed. Start state: the tree after sections A–F (HEAD `1714b83`, uncommitted). The scratch copy's `src` was identical by SHA-256 to the repository's, and a start manifest of 589 files (`Stage2/src`, `docs`, `submission`, `ProjectGit/docs`) was kept. Baseline: the final run of section D, 922 run / 0 / 0 / 6 skipped. Same isolation as section A: scratch copies, fresh data and config directories, no API key, no live provider, and the user's running PhotoPilot left untouched. A separate untouched copy (`gui-before`) served for fail-before runs.

| Issue | Root cause | Implementation | Regression evidence | Final status |
| --- | --- | --- | --- | --- |
| Theme initialization cycle | `Theme.<clinit>` needs `Accent.BLUE` (`DEFAULT`), while every `Accent` constant called the static `Theme.readable()`. The same pattern ran through `Family`: `custom()` → `Theme.luminance()` → `Theme.<clinit>` → `spectrum()` → `Family.custom()` again. | New stateless `ColorContrast`. `Accent` and `Family` use it, never `Theme`'s statics. `Theme`'s helpers delegate to it, unchanged. | `ThemeInitializationOrderTest`, 15 tests in fresh child JVMs. Unmodified code: 3 pass, 12 fail (NPE, "Recursive update", class-initialization deadlock, failed startup). Fixed code: 15/15 pass, and every order gives the original palette hash. | FIXED AND VERIFIED |
| Dropdown contrast | The open list paints its text on a translucent surface over the frosted window content, with tint, sheen and shadow in between. The theme's text colours were never checked against that composite (selected, secondary and F09 estimate text). | `DropdownContrast` gives the readable colours for the measured frost range: it keeps theme colours that read, otherwise moves them minimally towards black or white, and as a last resort protects the list's surface locally. `DropdownSkin` applies them to this open list only. | `DropdownContrastTest` (8, model over 4,312 configurations plus an oracle on the actual frost), `DropdownContrastGuiTest` (5, rendered pixels, profile switch in a fresh JVM), `DropdownThemeStatesTest` (kept floors plus painted colours) | FIXED AND VERIFIED (Windows) |

### G.1 Theme static initialization

**Exact cycle (before).** `Theme.<clinit>`, line 17, runs `DEFAULT = new Theme(Accent.BLUE, …)` and so needs `Accent`. `Accent.<init>`, line 64, ran `readable(...)`, a static method of `Theme`, so it needed `Theme`.

- **Order A (Theme first)** worked: inside `Theme`'s own initialization the JVM lets the same thread call `Theme.readable`, which uses no static state. The application happens to start this way, through `ThemeManager`'s field initializers.
- **Order B (Accent first)** failed. `Accent.<clinit>` runs `Accent(BLUE)`, which calls `Theme.readable`, which starts `Theme.<clinit>`. That runs `new Theme(Accent.BLUE, …)` while `BLUE` is still `null`, the compact constructor's `requireNonNull` throws an NPE, and the JVM reports `ExceptionInInitializerError`. From then on both classes are unusable in that JVM ("NoClassDefFoundError: Could not initialize class …Theme$Accent").
- **`Family.custom(h)` first** also failed. `ConcurrentHashMap.computeIfAbsent` calls `generate`, then `ofLuminance`, then `Theme.luminance`, which starts `Theme.<clinit>`. `HUE_SPECTRUM = spectrum()` then calls `Family.custom(150)`, and 150 shares hash bin 6 with the default custom hue 262. The result is `IllegalStateException: Recursive update`.
- **Concurrent first access** from six threads deadlocked: one thread held `Theme`'s initialization lock and waited for `Accent`'s, while another held `Accent`'s and waited for `Theme`'s.

**Previous workaround.** `DropdownThemeStatesTest` touched `Theme.DEFAULT` before `Accent`, and section F recorded the cycle as latent. The workaround is removed.

**Correction.** `ColorContrast` (package-private, stateless, depending only on JavaFX `Color`) holds the WCAG luminance, contrast and `readable` arithmetic, moved verbatim with the same 0.03928 threshold, plus source-over compositing. The `Accent` constants and `Family.ofLuminance` call it. `Theme.luminance`, `contrast` and `readable` keep their signatures and delegate to it. Nothing else in `Theme`, `ThemeManager`, the presets, the tokens or the preference keys changed. Callers need no initialization order.

**Dependencies.**
- Before: `Theme` → `Accent` → `Theme` (a cycle), and `Theme` → `Family` → `Theme` (a cycle through `luminance`).
- After: `Theme` → {`Accent`, `Family`, `ColorContrast`}; `Accent` → {`Family`, `ColorContrast`}; `Family` → `ColorContrast`; `ColorContrast` → nothing. `ThemeManager` → `Theme` (one way). The graph is acyclic.

**Evidence.** `ThemeInitializationOrderTest` starts one child JVM per scenario, each with isolated data and config directories. It covers:
- first access through `Theme`, `Accent` (a constant, `values()`, `parse`), `Family.custom`, `Mode`, `Theme.custom`, and `ThemeManager` before and after `Accent`;
- 26 repeated accesses;
- concurrent first access, in three separate JVMs;
- saved custom and legacy "lavender" preferences read after `Accent` was touched first (the file is not rewritten);
- normal application startup in `--smoke-test` mode, in both orders;
- the shared test JVM itself.

After its first access, each child builds the complete palette: every accent and generated family, the default theme, every preset and ten custom hues in every mode at 0/40/62/100 %, and the hue spectrum. It prints the palette's SHA-256 for comparison with a child run in the application's own order.

Results:
- **Unmodified code** (`gui-before`): 15 run, 10 failures and 2 errors. Only the Theme-first orders pass (`theme-then-accent`, `custom-first`, `manager-first`). The stack traces above came from these runs; the concurrent run printed "class initialization deadlock: 6 threads never finished".
- **Fixed code:** 15/15. Every order, including GUI startup after `Accent`, gives palette `8555d892c021a4db2f4b82b5fff28813041a83a57b87e8d24fa3e5ece46cc2a7`, the hash the unmodified code produces in the application's order. Theme values are therefore unchanged.

### G.2 Dropdown text contrast

**What lies behind the text.** In the popup, from the bottom up:
1. The frost: `DropdownSkin` takes a snapshot of the window content under the list, box-averages it to quarter size and applies a Gaussian blur with sigma 10. It is opaque, and is drawn only while the Transparency is below 99 %.
2. The list's `DropShadow(-pp-shadow, 22, 0.3, 0, 7)`, which shows through the translucent surface.
3. The list surface `-pp-surface-2`: white at `lerp(.22, 1, o)` in Light, the dark tone at `lerp(.40, 1, o)` in Dark.
4. The glass sheen `-pp-glass-sheen`: white, up to 0.40 (Light) or 0.07 (Dark) at the top.
5. The option tint: none, `-pp-hover`, or `-pp-selected-soft` (the accent at 0.15 or 0.20). Keyboard focus has no look of its own; it moves the selection.

The text colours painted inside the list are every `-fx-text-fill` that photopilot.css applies there: `-pp-text`, `-pp-text-muted` (empty message, custom-cell second lines), `-pp-accent-text` (selected option), and `-env-warn` (the F09 amber second line of an AI-estimated place).

**Root cause.** These colours were tuned and tested for panels over the wave background, using 8 background samples, without the sheen or shadow, and only for selected and hovered option text. The list is more transparent than an input field and sits over arbitrary window content. At high transparency the selected, secondary and amber text fell below 4.5:1, and over unusual content (dark photographs, accent buttons) any text could.

**Method.**
- WCAG 2 relative luminance after the sRGB-to-linear conversion (threshold 0.03928, as `Theme`), and the ratio `(L_lighter + 0.05) / (L_darker + 0.05)`.
- Layers are composited source-over per sRGB channel, as JavaFX paints them. Never against a translucent token on its own.
- The frost varies, so the check takes its per-channel darkest and lightest values. Compositing and luminance only increase with each channel, so every option state's background luminance lies between two composites: one with the shadow at full strength and no sheen, one with the sheen at full strength and no shadow.
- Every bound is widened by one 8-bit level of channel and surface opacity, because JavaFX paints in 8 bits.
- At runtime `DropdownSkin` measures this range on the actual frost image under the list after every frost pass. That covers opening, resizing, theme, accent, opacity, mode and profile changes, and reopening. An opaque list (≥ 99 %) is checked over any backdrop.

**Implementation, following the requested strategy.** `DropdownContrast.resolve` handles each text colour in turn:
1. It computes the composited background range.
2. It checks the theme's colour against that range.
3. It keeps the colour unchanged when it reads at 4.5:1.
4. Otherwise it moves the colour along the line towards black (Light) or white (Dark), to the first 8-bit colour that reaches 4.5:1. The hue is kept.
5. Only if even black or white cannot reach 4.5:1 does it raise this list's own surface opacity, in 0.001 steps, to the least value at which every colour can.

`DropdownSkin` writes only the values that differ, as looked-up colours in the list's inline style, after the control's font. Nothing global changes: no theme token, no token sheet, no Transparency setting (0–100 % stay available, default 62 %), no other surface. Unchanged: the hover and selection tints, the frost, placement, mouse and keyboard handling, empty state, presets, custom hues and saved preferences. No new token and no new dependency.

**Before and after.**

| Measurement | Theme colours (before) | Painted (after) |
| --- | --- | --- |
| Earlier method (`DropdownThemeStatesTest`: 8 background samples, no sheen or shadow) | selected text weakest 4.01; 513 samples below 4.5, all below 62 % | selected 4.81, hover 7.13 |
| Full model, 4,312 configurations (5 presets and 72 custom hues; Light, Dark, Follow System Light and Dark; Transparency 0, 1, 10, 25, 40, 50, 61, 62, 63, 75, 90, 98, 99, 100) | option, secondary and selected text weakest 2.95 (Peach Dark 0 %); 2,108 configurations below 4.5 (308 each at 0 % and 1 %, 154 at 62 %, 2 at 90 %, none at 98–100 %). F09 amber alone: weakest 2.64, below 4.5 in 3,346 configurations including opaque ones | weakest 4.50 (by construction); no surface protection needed over the theme background |
| Reported configurations (full model) | Cool Blue Light 0 % 4.14; Cool Blue Dark 0 % 3.42; 24 dark custom hues at 40 % 3.91–4.08 | 4.51; 4.51; 4.50–4.53 |
| Independent oracle: the actual blurred frost of an 800 × 500 window, every pixel, shadow and sheen each at 0, ½ and full, all states, 100 themes | 3.48 | 4.56 |
| Rendered pixels, F01 status filter (`DropdownContrastGuiTest`, 11 configurations) | — | weakest 5.07–6.19 per configuration |
| Rendered: mid-grey area under Cool Blue Light 0 % | selected text 3.73 on the same pixels | 5.89 (accent and secondary text moved, no protection) |
| Rendered: black area under Cool Blue Light 0 % / white area under Sage Dark 0 % | — | list surface 0.220 → 0.484 / 0.400 → 0.785 (this list only); weakest 5.03 / 6.07 |
| Rendered: F09 estimate line (`:estimated` cell inside `.env-page`) | Light 0 % 3.26, 62 % 3.63, 100 % 3.93; Teal Dark 0 % 5.08 | 5.00, 4.70, 4.59; 6.90 |

Every rendered background sample lay within the range the check computed, to within 0.012 of luminance. **Worst case after the change:** 4.50 in the model (the target, with the paint margin) and 4.59 on rendered pixels (the F09 estimate line, Light, 100 %).

**Intentional visual differences** (only inside an open list, and only where a colour fell short):
- Secondary and selected text are slightly darker in Light and lighter in Dark at high transparency. Example: Cool Blue Dark 0 % selected text, theme `rgba(169,188,245)`, becomes `rgba(176,194,246)` on the real F01 list, and `rgba(204,216,249)` against the model's worst-case background.
- The F09 amber estimate line is a darker amber in Light at every transparency (`rgba(166,98,0)` → about `rgba(125–151,74–89,0)`). It was 3.9–4.8:1 even on an opaque list.
- Over an atypical backdrop such as a photograph, the list's surface becomes more opaque and the text near black or white.

Kept as before:
- **Default appearance** (Cool Blue, Follow System on a light or unknown system, 62 %): option, secondary and selected text are exactly unchanged. On a dark system the sheen over the top rows calls for a slightly lighter secondary and selected text.
- **Opaque lists:** the theme's colours. Two of 1,848 checked colours (616 opaque configurations × 3) move by one 8-bit level, a paint-precision nudge that is not visible.

**Other states.**
- *Closed control:* `-pp-text` on `-pp-input` reaches ≥ 4.5 in every configuration of the model. On rendered pixels (Cool Blue Dark 0 %) it reads 14.14:1.
- *Disabled:* unchanged. JavaFX's 40 % opacity applies to a disabled control and to a selected disabled option. PhotoPilot has no disabled options. The same rendered control disabled reads 3.26:1, clearly fainter than enabled.
- *Keyboard focus:* rendered ≥ 4.5 after arrow keys in the open list.
- *Chinese UI:* the list keeps the Chinese UI font with the overrides after it.
- *Profile switch:* in a fresh JVM, with a real preference store and two users (Cool Blue Light 0 %, and custom hue 60 Dark 40 %), each switch restores that user's appearance and its dropdown reads ≥ 5.40.

### G.3 Changes

- **Main:**
  - `ui/gui/Theme.java`: `Accent` and `Family` use `ColorContrast`; the static helpers delegate.
  - `ui/gui/ColorContrast.java` (new).
  - `ui/gui/DropdownContrast.java` (new).
  - `ui/gui/DropdownSkin.java`: measures the frost range, applies the overrides, adds `OPAQUE = 99` and keeps the font style.
  - No CSS, bundle, token or preference change.
- **Tests (new):**
  - `ThemeInitializationOrderTest` (15).
  - `DropdownContrastTest` (8).
  - `DropdownContrastGuiTest` (5, including one child JVM).
- **Tests (changed):** `DropdownThemeStatesTest`. The initialization-order workaround is removed. Its earlier assertions on the theme's own colours are kept unchanged (selected ≥ 4.5 at ≥ 62 % and ≥ 4.0 below; hover ≥ 4.5), and assertions on the painted colours (≥ 4.5 everywhere) are added. No other test changed. `DropdownGuiTest` passes unchanged.
- **Independent review.** Four reviewers covered the initialization fix, the mathematics, the skin integration and the tests; each finding then went to an adversarial verifier.
  - The initialization fix had no findings.
  - Confirmed and fixed: the F09 amber estimate line was not covered. It is now `Role.WARN`. A test checks its two values against photopilot.css's `-env-warn` ladder, and another fails if any open-list text colour is not covered.
  - Hardening adopted from findings the verifiers rejected as non-failing: the paint-precision margin; the restored theme-colour floors; opaque lists checked over any backdrop; the independent frost-pixel oracle; the reproduction count made strict (all 26 reported configurations); a rendered case where the theme's colour fails and the fix matters (the mid-grey area); and the removal of an assertion that could not fail.

### G.4 Verification

All runs used the bundled JDK 21.0.13 and the Maven Wrapper (`-B -o`) in isolated copies, with the environment of section A.

| Run | Command | Result |
| --- | --- | --- |
| Fail-before, initialization | `mvnw.cmd -B -o -Dtest=ThemeInitializationOrderTest test` on the unmodified copy | 15 run, 10 failures, 2 errors (only the Theme-first orders pass); NPE / "Recursive update" / deadlock / startup failure as in G.1 |
| Fixed, initialization | same, patched copy | 15/15; every order gives palette `8555d892…cc2a7`; `GUI_SMOKE_OK` in both startup orders |
| Model | `-Dtest=DropdownContrastTest,DropdownThemeStatesTest` | 9/9; numbers as in G.2 |
| Rendered GUI | `-Dtest=DropdownContrastGuiTest,DropdownGuiTest`, run 3 times in isolation (plus once in each full run) | 5/5 and 5/5 every time; screenshots and popup renders kept |
| Full suite (first, before the review fixes) | `mvnw.cmd -B -o clean verify` | 948 run, 0 failures, 0 errors, 6 skipped |
| **Full suite (final)** | `mvnw.cmd -B -o clean verify` | **950 run, 944 passed, 0 failures, 0 errors, 6 skipped** (922 + 28 new); JAR built; BUILD SUCCESS |
| GUI smoke (packaged) | `mvnw.cmd -B -o package javafx:run -DskipTests -Djavafx.args=--smoke-test` | `GUI_SMOKE_OK` |
| Runtime logs | the final full run and the smoke log | `CssStyleHelper` lookup warnings: **0**; `ExceptionInInitializerError` / `NoClassDefFoundError`: 0 |

The six skips are the existing opt-in cases: four live-Gemini smoke tests and two private-photo samples. Logs, popup renders and screen captures are kept outside Git in `EECS3311/tmp/Stage2OptRe2-remediation-evidence/gui-stabilization/`. The scratch diagnostic probes used for the breakdown were never placed in the repository.

### G.5 Remaining limitations

- **Platforms.** The checks ran on Windows 11 (Direct3D pipeline) only. The model assumes JavaFX's sRGB source-over blending, which the rendered samples confirmed here; macOS and Linux were not run.
- **First frame.** Each opening paints the theme's colours for one pulse, until the frost and its measurement are done. That pass existed before this patch.
- **Content changes.** As before, a backdrop that changes under an open list is not re-measured until the list reopens or the appearance changes.
- **Hover.** Hover on real pixels is covered by `DropdownGuiTest`'s real-pointer check of the tint and by the model. The new rendered tests park the pointer outside the window, so no hovered option is in their pixel samples.
- **Protection.** In the rare protection case the text ends near black or white, because the least change of opacity was preferred over keeping the accent hue. The selection remains visible through its tint.
- **F09 amber.** Outside dropdowns (F09 page cards and chips), `-env-warn` is unchanged and below 4.5:1 on light surfaces. It is outside this patch's scope (dropdowns); recorded as a follow-up.
- **Duplicated constant.** The amber's two values exist in photopilot.css and in `DropdownContrast` (a test keeps them equal).
- **Raw-token assertions.** `DropdownGuiTest` still compares the selected text with the raw `-pp-accent-text` at 40 % on the F01 page. This holds because that page's measured backdrop needs no accent override there. A layout change that does need one would require comparing with the skin's applied colour, as `DropdownContrastGuiTest` does.
