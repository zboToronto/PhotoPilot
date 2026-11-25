# Stage2OptRe1 remediation patch (2026-11-23)

Input: the 2026-11-23 engineering quality review `Project/Stage2OptRe1.pdf` and `Project/Stage2OptRe1_evidence/` (outside Git, read only; audited baseline `1714b83`). The working tree already carried the uncommitted 2026-11-23 execution-feedback, session-workflow and capability-report work; this patch was applied on top of it and changed nothing else. Nothing was committed or pushed.

Environment for every result below: Windows 11, bundled JDK 21.0.13, Maven Wrapper 3.9.9 offline (`-o`, copied cache), an isolated scratch copy of `Stage2` without `target`, `.tools` and `.env`, fresh `PHOTOPILOT_DATA_DIR`/`PHOTOPILOT_CONFIG_DIR`, no API key, `PHOTOPILOT_LIVE_GEMINI=0`. AI and provider responses come only from the loopback test doubles (`FakeGemini`, `FakeEnvironmentProviders`); no live Gemini, Open-Meteo, sunrise-sunset, Wikipedia or Lensfun request was made.

## Results

| Run | Command | Result |
| --- | --- | --- |
| Baseline (unmodified tree) | `mvnw.cmd -B -o clean verify` | 772 run, 0 failures, 0 errors, 6 skipped |
| Final #1 | `mvnw.cmd -B -o clean verify` | **812 run, 0 failures, 0 errors, 6 skipped**, BUILD SUCCESS |
| Final #2 | `mvnw.cmd -B -o clean verify` | **812 run, 0 failures, 0 errors, 6 skipped**, BUILD SUCCESS |
| Final #3 | `mvnw.cmd -B -o clean package javafx:run -Djavafx.args=--smoke-test` | **812 run, 0 failures, 0 errors, 6 skipped**; JAR built; `GUI_SMOKE_OK` |

The six skips are the existing opt-in cases: four live-Gemini smoke tests and two private-photo sample checks. 40 tests are new (10 classes); five existing test files changed (below).

## Findings and fixes

### PP-01 Home "Shot on" for a scheduled date — fixed and verified

- **Reproduced.** Source: `HomeDashboard.afterCard` passed `session.date()` to `home.after.date` = "Shot on {0}" / "拍摄于 {0}". The updated `HomeDashboardGuiTest` fails on the unmodified code with "拍摄于 2026-11-01".
- **Root cause.** The session date is the scheduled date; no field records when photos were taken or when the session was marked completed, and the label asserted a shooting date.
- **Fix.** Shared `application.SessionDates`: "Scheduled {date}", or "Photos dated {span}" from the saved EXIF capture span (camera clock), plus "· scheduled {date}" when they differ. Home, the Photography Sessions editor and CLI `session open`/`list` use it. Early completion stays allowed. No stored field was added, so existing data renders unchanged. [Details](home-dashboard.md#session-dates-stage2optre1-pp-01).
- **Tests.** `SessionDatesTest` (5): early completion, on-day, rescheduling, no EXIF, multi-day, partial timestamps, CLI, stored bytes, Chinese. `SessionDatesGuiTest` (2): the audit's 2026-12-12 session completed on 2026-11-23, Home after an import, Chinese, the editor. `HomeDashboardGuiTest` updated.

### PP-02 CLI help said aliases only report availability — fixed and verified

- **Reproduced.** The real baseline CLI `help` printed "other F01-F11: availability"; F01 lists sessions and F06 prints the needs commands and the capability summary.
- **Root cause.** `route()` expanded the aliases inline while the help was a separate hand-written line.
- **Fix.** `ui.cli.CliCommandCatalog` is the one list of what F01–F11 run. `route()` expands through it, and `help` prints it together with the command groups, options and exit codes (English and Chinese). Names, aliases and exit codes are unchanged. [README](../README.md#cli-commands).
- **Tests.** `CliHelpTest` (5): help content in English and Chinese; every alias (three spellings) produces exactly the output and exit code of its commands; every group routes; unknown and malformed input exits 2 without an internal error; a real child JVM covers redirected input with a failure, EOF, batch arguments, `exit` and an invalid `--lang`.

### PP-03 Saved review called one camera model two different cameras — fixed and verified

- **Reproduced.** The statement from G17/C64 with correct figures passes the figure check alone (`EquipmentClaimsTest` keeps it with identity checks off). The updated `StatisticsCalculatorTest` and `F11WorkflowTest` fail on the unmodified code, where "SONY ILCE-7M4" is never merged into "A7 IV".
- **Root cause.** The deterministic comparison matched names by tokens only and reported catalog aliases as an unmatched plus an extra camera. `ReviewEvidence.check` validated figures but not equipment-identity assertions.
- **Fix.** `service.EquipmentIdentity` decides SAME / DIFFERENT / UNKNOWN model with catalog-backed aliases and no fuzzy matching, and never asserts a body. `StatisticsCalculator`, the review evidence (`equipmentIdentity`, prompt rule 9) and the F04 duplicate review use it. `service.EquipmentClaims` removes unsupported or contradicted identity claims before a draft is shown or saved, with its own note. Saved reviews are shown unchanged, with a note under each statement the check now rejects (GUI and CLI through `ReviewPresentation`). [Details](f11-review.md#equipment-identity-stage2optre1-pp-03).
- **Tests.** `EquipmentIdentityTest` (7), `EquipmentClaimsTest` (10), `ReviewIdentityWorkflowTest` (2), `ReviewIdentityGuiTest` (1); `StatisticsCalculatorTest`, `F11WorkflowTest` and `ReviewGuiTest` updated.

### QA-01 DropdownGuiTest failed on a normal desktop — test infrastructure fixed

- **Reproduced.** `everyThemeModeAndCustomColourRestylesTheOpenListAndTheKeyboardStillWorks` alone on the current tree failed 3 of 6 runs: twice "the pointer highlights the option" and once "the open list has keyboard focus". An instrumented copy failed 3 of 6 more.
- **Diagnosis** (scratch diagnostics, not committed):
  - A list opened while another window is the OS-active window never gets keyboard focus. That is correct JavaFX/OS behaviour, and Windows sometimes does not make the test JVM the active window.
  - The real pointer was moved by another input source during the runs (positions between steps jumped across the screen), so it rested on, or left, the list under test.
  - Undisturbed, a real click opened the list in about 15 ms and hover followed in ≤ 1 ms. No production defect was found: `DropdownSkin` keeps the standard skin's input handling.
- **Fix (test only, no assertion removed).**
  - The test window is made active, by a real click on a verified non-interactive spot only when raising it did not work.
  - The pointer rests off the window unless it is the input under test.
  - Robot steps wait (at most 5 s) for the observable result, the open list's placement, the CSS pass after a theme change, and the frosted backdrop.
  - The selected cell is looked up among rendered cells only.
  - A timeout names the desktop state (window active, pointer position, and whether another source moved it).
- **After.** The class passed 21 of 22 consecutive runs (10 + 12) and is part of both full runs.
- **Residual (unresolved).** One run failed at "selection uses the theme's soft tint": the selected visible option of the open list had a transparent fill after the Sage accent was applied. It persisted through a 5 s wait, and no CSS lookup warning was logged. It did not recur in 12 instrumented runs, so its cause is not established. It is recorded as an intermittent open item, not as fixed.

### C16 Manual coordinates refused for an unverifiable time zone — improved and verified

- **Classification.** This is an intended safety constraint combined with a provider limitation, not an application defect. For bare coordinates the only zone source is Open-Meteo's `timezone=auto` answer; a lookup match supplies its own zone as a fallback.
- **Improvement.** An optional regional IANA zone can be entered (GUI field, CLI `zone=`).
  - Offsets, UTC/GMT and `Etc/` ids are refused.
  - The zone must be within 3.5 h of the coordinates' mean solar time.
  - It is labelled as user-entered, and a provider-verified zone still wins.
  - The computer's zone or UTC is never substituted.
  - The refusal message names both ways forward. [Details](f09-environment.md#entered-time-zone-stage2optre1-c16).
- **Tests.** `ManualTimeZoneTest` (6), with the weather provider returning 503.

### C31 "Storage is busy" reported as a configuration error — improved and verified

- **Classification.** Normal cross-process contention; the data guarantees were already correct (C43 succeeded on a serial retry).
- **Improvement.** The CLI start-up message now says that another PhotoPilot window or command is writing, that nothing was changed, and to retry (`repository.StorageBusy`, `cli.storageBusy`). Exit code 2 is unchanged.
- **Tests.** `StorageContentionCliTest`: a real child JVM against a held store lock (accurate message, nothing written), then a successful retry.

### X04 Mini AI history and profile isolation — verified with controlled replies

- **Existing coverage.** Six-exchange eviction is checked at request construction (`MiniAiServiceTest`).
- **New test.** `MiniAiProfileIsolationGuiTest` uses the real controller and window with held loopback replies. A pending request blocks the profile switch, and the reply reaches the profile that asked. Profile B shows no answer and its first request carries no earlier exchange; switching back does not bring B's exchange to A.

### DOC-01 Feature-to-video index — partly addressed, open

The [submission index](../submission/README.md#demonstration-index-status) now lists the eleven local recordings by feature (`F01.mp4`–`F11.mp4`, recorded 2026-11-16, before this patch). The Drive folder's contents, per-file links, timestamps and anonymous access were not verified, and no link was invented.

## Traceability (Stage2OptRe1 item → change → test → status)

| Item | Change | Regression evidence | Final status |
| --- | --- | --- | --- |
| PP-01 Home date semantics | `SessionDates`; Home, Sessions editor, CLI | `SessionDatesTest`, `SessionDatesGuiTest`, `HomeDashboardGuiTest` (fails on baseline) | FIXED AND VERIFIED |
| PP-02 CLI help | `CliCommandCatalog`; `route()`/`help()` | `CliHelpTest` incl. real process; baseline help output compared | FIXED AND VERIFIED |
| PP-03 camera alias in reviews | `EquipmentIdentity`, `EquipmentClaims`, comparison, evidence, legacy note, F04 duplicates | `EquipmentIdentityTest`, `EquipmentClaimsTest`, `ReviewIdentityWorkflowTest`, `ReviewIdentityGuiTest`; updated tests fail on baseline | FIXED AND VERIFIED (application level; a live model was not called) |
| QA-01 dropdown test failures (click, hover, focus) | DropdownGuiTest infrastructure | 21/22 class runs, full runs | FIXED AND VERIFIED (test infrastructure; no product defect found) |
| QA-01 residual: transparent selected tint, once | none | not reproduced in 12 instrumented runs | UNRESOLVED (intermittent, cause unknown) |
| IN-01 HTTP 503 / provider availability | none (excluded) | fixture-based flows below | EXCLUDED — EXTERNAL PROVIDER AVAILABILITY |
| DOC-01 video index | local recordings table | n/a | UNRESOLVED (needs links and access check) |
| C16 manual coordinates | entered IANA zone | `ManualTimeZoneTest` | FIXED AND VERIFIED (usability; the safety rule kept) |
| C31 busy store message | `StorageBusy`, `cli.storageBusy` | `StorageContentionCliTest` | FIXED AND VERIFIED (message; behaviour was correct) |
| Device location unsupported | none | existing F09 tests | DOCUMENTED INTENTIONAL LIMITATION |
| X01 live model tool calls | none | `AgentToolLoopTest` (fixture tool loop) | EXCLUDED — EXTERNAL PROVIDER AVAILABILITY (live) / fixture path verified |
| X02 second platform (macOS/Linux GUI, ExifTool) | none | source check: no hard-coded Windows paths; bundled ExifTool refuses explicitly off Windows | NOT VERIFIED — ENVIRONMENT (Windows-only ExifTool is a DOCUMENTED INTENTIONAL LIMITATION) |
| X03 real source verification, conflicts, duplicate merge | F04 duplicate review gains catalog aliases | `GearImportWorkflowTest` (mocked Wikipedia/Lensfun) | NOT VERIFIED (real services) / fixture path verified |
| X04 Mini AI eviction and isolation | none | `MiniAiServiceTest`, `MiniAiProfileIsolationGuiTest` | VERIFIED — NO DEFECT FOUND (controlled replies) |
| X05 other RAW brands, large folders | none | `FolderScanLimitTest`, `RawMetadataTest` (Sony fixtures) | NOT VERIFIED — FIXTURE LIMITATION (no other brands' RAW samples) |
| X06 outbound request fields | identity evidence adds model names only | F11/F09/F04 request-content assertions (no file names, paths, GPS, coordinates, images) | VERIFIED locally (request construction only; no server-side capture) |
| F10 plans outside the window / stale F09 evidence (historical risk) | none | `F10WorkflowTest`, `PlanValidatorTest`, `EnvironmentEvidenceTest`, `PlanApprovalBoundaryTest` | VERIFIED — NO DEFECT FOUND (fixtures) |

Per feature (audit GUI/CLI result → now), with the fixture-based workflow tests as evidence. The provider-dependent positive paths stay excluded:

| Feature | Audit GUI / CLI | Final status |
| --- | --- | --- |
| F01 sessions | PARTIAL / PASS | VERIFIED — NO DEFECT FOUND: `SessionGuiTest` (CRUD, confirmed delete, stale edits from another process), `SessionCliTest`; dates fixed under PP-01 |
| F02 discovery | BLOCKED / BLOCKED | EXCLUDED (live) — fixture path verified: `F02WorkflowTest`, `DiscoveryHandoffTest` (atomic handoff, refused ideas create no session) |
| F03 requirements | PARTIAL / PARTIAL | VERIFIED — NO DEFECT FOUND (fixtures): `RequirementWorkflowTest` (clarification, corrections, cancellation, nothing invented), `RequirementGuiTest` |
| F04 import | PARTIAL / PARTIAL | VERIFIED — NO DEFECT FOUND (fixtures): `GearImportWorkflowTest`, privacy tests, `GearImportGuiTest`; live recognition EXCLUDED |
| F05 inventory | PARTIAL / PASS | VERIFIED — NO DEFECT FOUND: `InventoryGuiTest`, `InventoryCliTest`, `InventoryTest` |
| F06 capabilities | PARTIAL / PARTIAL | VERIFIED — NO DEFECT FOUND (fixtures): `F06WorkflowTest` (replacement, requirement changes keep stale profiles out of F07) |
| F07 matching | PASS / PASS | VERIFIED — NO DEFECT FOUND |
| F08 calculators | PASS / PASS | VERIFIED — NO DEFECT FOUND |
| F09 environment | PARTIAL / PARTIAL | C16 improved; device location a DOCUMENTED INTENTIONAL LIMITATION; live providers not called |
| F10 planning | BLOCKED / PARTIAL | EXCLUDED (live) — fixture lifecycle verified: `F10WorkflowTest`, `PlanApprovalBoundaryTest`, `PlanRevisionConsistencyTest` |
| F11 review | PARTIAL / PARTIAL | PP-03 fixed; statistics and persistence verified (`F11WorkflowTest`, `ExifExtractionTest`) |
| Home / Mini AI / Profiles / Shell / Storage | PARTIAL or PASS | PP-01, X04, PP-02 and C31 above; profiles: `ProfileGuiTest` (rename, protected deletion, switch with pending work and drafts) |

## Changed tests (disclosed)

No test was deleted, disabled or relaxed.

- `HomeDashboardGuiTest`: the After card expects `session.dates.scheduled` instead of "Shot on".
- `F11WorkflowTest`, `StatisticsCalculatorTest`, `ReviewGuiTest`: they asserted the old "never merge A7 IV and ILCE-7M4" outcome. They now assert the catalog match and the alias line; `StatisticsCalculatorTest` also asserts the body line and the lens difference.
- `DropdownGuiTest`: infrastructure only (above); every assertion is kept, with a bounded wait before the time-dependent ones.

## Limits

- **External providers:** no live AI or provider request was made in this patch (IN-01 excluded).
- **Platform:** only Windows 11 was run.
- **Hands-on GUI:** nobody used the app by hand. The GUI evidence is real-window JavaFX tests and their snapshots, plus the packaged smoke launch. The desktop-control tool here cannot grant input to a `java.exe` window.
- **UML:** not updated; the classes are listed in [design changes](stage2-design-changes.md#for-the-stage-2-uml-and-report).
- **Open items:** the QA-01 residual and DOC-01 remain open.

## Final verification

- **Full suite.** Three consecutive full runs on fresh isolated copies (2026-11-23/24) gave 812 / 0 / 0 / 6 each, `DropdownGuiTest` included. Packaging built the JAR, and the packaged GUI smoke launch passed (splash, user selection, Home, all 11 features, minimum-size layout).
- **Targeted repetition.** Before the full runs the non-GUI tests passed on their own (713 run, 0 failures). The new and affected GUI classes passed together (15 tests).
- **Before/after on the unmodified code.** The updated `HomeDashboardGuiTest`, `StatisticsCalculatorTest` and `F11WorkflowTest` fail there exactly at the defects, and the baseline CLI `help` prints the stale availability line.
- **Real processes across interfaces.** A scratch seeder (outside the repository) wrote the audit's two cases with the repository API into an isolated store: a 2026-12-12 session completed early, and a legacy review with the alias claim. Separate CLI JVMs then read them:
  - `session open` shows `2026-12-12 | Completed` with no photo dates and no "Shot on";
  - `review open` prints the stored statement unchanged with the note, in English and Chinese;
  - exit code 0, and the store is byte-identical after the reads.
- **GUI on the same kinds of records.** `SessionDatesGuiTest` and `ReviewIdentityGuiTest` use the real workspace and controller in a real window. Their snapshots (`home-after-dates-en.png`, `f11-legacy-identity-note.png`) were inspected.
- **Not done.** No hands-on use of the app, no live provider, no macOS/Linux run, and no commit.
