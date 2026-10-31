# PhotoPilot project memory

Last reviewed: 2026-10-31 (America/Toronto). This is the current context index; [FEATURE_STATUS.md](FEATURE_STATUS.md) owns per-feature status. Confirm against source before development. Design contracts below are requirements, not claims that their behavior works.

## Evidence and repository layout

- Git root: `ProjectGit`; Maven application: `Stage2/`. Repository Stage 1 baseline: [report](../Stage1/Stage1PPP.pdf), [class diagram](../Stage1/PhotoPilot-ClassDiagram.uxf), [use cases](../Stage1/PhotoPilot-UseCaseDiagram.uxf), and `Stage1/PhotoPilot-SD01.uxf` through `SD05.uxf`.
- External course evidence, relative to this file: [Stage 2 instructions](../../Project/Project-Stage2-Instruction.pdf), [project instructions](../../Project/Project-Instruction.pdf), [Stage 1 instructions](../../Project/Stage1/stage1.pdf), [Word report](../../Project/Stage1/Stage1.docx). These stay outside Git and may be absent on another checkout; identify missing evidence instead of inventing requirements. `Section8.docx` is a traceability extract, not a replacement report.
- Existing detailed evidence remains in [source verification](../Stage2/docs/source-verification.md), [feature mapping](../Stage2/docs/feature-mapping.md), [class inventory](../Stage2/docs/class-inventory.md), [implementation decisions](../Stage2/docs/additional-decisions.md), [verification history](../Stage2/docs/verification.md), and [GUI conventions](../Stage2/docs/gui-redesign.md).
- Current source, tests and actual observations establish implementation status. Report/UML establish intended design. Older verification documents describe their dated snapshots, including the earlier four-test scaffold; they are not current completion claims. Do not copy stale absolute paths from historical notes.

## Confirmed structure and design contracts

| Area | Confirmed decision and source |
| --- | --- |
| Runtime | `Stage2/pom.xml`: Java 21, JavaFX 21.0.6, JUnit 5.11.4; Maven wrapper. GUI is JavaFX. |
| Composition | `config/ApplicationBootstrap` constructs shared services, repositories, calculator and agent per application, registers four tools, and owns HttpClient cleanup. GUI and CLI use equivalent wiring; separate processes do not share in-memory instances. Constructors do not execute external requests. |
| Facade | `application/PhotoPilotFacade` delegates to shared services. Preserve one business implementation for GUI and CLI. |
| Strategy | `GearMatchingService` uses `LoadoutRankingStrategy`, with `PortabilityRanking` and `CoverageRanking`; configuration chooses portability or coverage. Deterministic compatibility must precede ranking; returned candidate IDs must remain valid. |
| Adapter | `GeminiAdapter`, `EnvironmentApiAdapter`, `EquipmentCatalogAdapter`, `ExifLibraryAdapter` implement project ports. External failures must be explicit Result errors, never fabricated facts. Provider behavior remains unimplemented. |
| Command | `PlanCommand`, three concrete revision commands, `PlanEditor` and `ShootPlan` model revisions. ShootPlan and commands are co-located to preserve package-private mutation access. Generate/change returns a validated preview; approval must not reapply the command. |
| State | `PhotographySession` uses Draft, Planned, Completed and Reviewed state classes. Explicit events, approved-plan/review prerequisites, state adjacency and historical-plan immutability are enforced by F01; F10/F11 producer validation/approval workflows remain scaffolded. |
| Agent boundary | Bounded model/tool/evidence loop, allowlisted read-only tools, schema checks followed by deterministic domain validation. No autonomous save, inventory mutation or approval. Inventory persistence, calculations and EXIF statistics bypass the LLM. |
| Confirmation | F03 confirms requirements; F06 confirms a capability-only profile. F10 rechecks current state, constraints, session binding and revision before atomic save. Failed/cancelled operations preserve the persisted base. |
| Privacy and evidence | Order-image bytes cross the AI boundary only as user-approved SanitizedImage; privacy helper and image type share a package. Device location requires permission with manual fallback. EXIF review must disclose missing data and cannot invent measurements or aesthetic scores. |

Five pattern structures exist; their business operations are not yet five completed operational patterns. JsonInventoryRepository and JsonSessionRepository have tested atomic persistence. F01 now exercises Facade and State; this does not establish five completed operational patterns.

## UI baseline to preserve

`PhotoPilotGUI` owns lifecycle and callbacks; `PhotoPilotWorkspace` owns shell/navigation; `FeaturePane` owns feature content and feedback; `FeaturePresentation` maps stable F01-F11 identifiers to presentation labels. Reuse `src/main/resources/com/photopilot/ui/gui/photopilot.css` for visual styles.

- Workspace group: F01, F02, F03, F10. Equipment: F04-F07. Photography Tools: F08, F09, F11.
- Preserve bounded content width, wrapping text, independent navigation/content scrolling, selected-item behavior, keyboard navigation and focus styles. Existing defaults are 1120 x 760 with minimum 880 x 600.
- New forms/results belong in the existing feature content flow. A catalog marker must not enable an unconnected workflow. F01 connects SessionPane, F05 InventoryPane and F08 CalculatorPane inside FeaturePane; other workflow buttons remain disabled and Check status reports availability.
- JavaFX changes run on the UI thread. Follow the existing background Task callback pattern for slow work, stale-navigation completion checks and shutdown cancellation.

## Known gaps and outstanding work

Current cross-cutting UI/CLI behavior includes bilingual localization; see [implementation and verification](../Stage2/docs/localization.md). Future features must use semantic bundle keys and descriptor-preserving Result errors, share the bootstrap localization instance, and detach UI subscriptions. Do not translate command identifiers, JSON evidence, user values or existing conversation content.

These are confirmed gaps, not permission to implement or redesign them.

| Issue | Evidence / required resolution |
| --- | --- |
| F02-F04, F06-F07 and F09-F11 remain scaffolded | F01 sessions, F05 inventory and F08 calculators are implemented and verified; see feature status. No real provider integration has been verified. |
| F02 select/save/dismiss not exposed through Facade | DiscoveryService declares them, but Facade has no forwarding/accessor route. |
| F07 selected-loadout persistence missing | SD03 has no application selection/attachment operation. Strategy configuration exists at bootstrap; user-facing selection wiring remains unspecified. |
| Generic AI decoding unresolved | `Class<T>` cannot describe list element schemas; PlanCommand requires an explicit concrete subtype allowlist. |
| Approval and ownership invariants pending | Deep copy, immutable approved revisions, atomic aggregate persistence, multiplicities and stale/foreign revision rejection are design requirements, not established runtime behavior. |
| Integration choices pending | Concrete device-location adapter, equipment/weather providers and EXIF library remain unspecified; bootstrap has unconfigured endpoints and no active device provider. |
| Verification limits | F01/F05/F08 have business and GUI/CLI acceptance tests. Live AI/provider results and submission demonstration videos are not established. macOS/Linux rendering and other display scales remain unverified. |

The structural audit currently rejects any HTTP send call as a scaffold safeguard. When authorized integration work starts, revise that obsolete scaffold assumption deliberately and document why; do not treat it as a permanent prohibition on required LLM integration.

## F01 implementation (2026-10-31)

- F01/UC01/SD01 is verified through `PhotoPilotFacade.sessions()` and retained `createSession(title,date)`. SessionService create/save/open/search/transition now work; overloads expose optional location/notes, date/state filtering, revision-aware update and confirmed delete. SessionForm shares strict ISO date/UUID/input parsing between GUI and CLI. No category, mandatory time/coordinates, cancellation, separate gear inventory or AI work was added.
- PhotographySession retains its original aggregate fields, exposes context getters and returns copies from edit/apply. States are Draft -> Planned (approved non-provisional plan), Planned -> Completed (explicit shoot event), Completed -> Reviewed (saved review). Wrong/skipped/reverse events fail. Repository saves also enforce adjacency and immutable historical plans. F10/F11 production/approval remains unavailable: a new bare Draft cannot be manually promoted. Tests of later transitions use controlled linked fixtures and do not establish F10/F11 implementation.
- `PHOTOPILOT_DATA_DIR/sessions/sessions.json` (default `data/sessions/sessions.json`) stores UTF-8 schemaVersion 1 and complete session/linked aggregates. SessionJson handles ISO temporal values, Optionals, allowlisted states and F08 Infinity strings. Per-record SHA-256 revisions, cross-process lock, flushed temp file and atomic replacement protect saves/deletes; no non-atomic fallback. Strict parsing rejects corrupt/unknown/oversized (20 MiB)/incomplete data rather than silently replacing it. An invalid aggregate blocks writes and F05 reference checks. No automatic recovery/migration is claimed.
- GUI SessionPane reuses FeaturePane, workspace navigation, shared CSS, feedback/confirmation cards, background Tasks and FxLocalizer. Drafts survive navigation/language changes; replacement/close requires discard confirmation; failures preserve input. CLI `session` supports create/list/open/view/restore/edit/transition/delete; F01 aliases list. Both share services/storage and observe external changes on refresh. showSession(id) now opens the real pane.
- F05 integration scans actual plan/loadout GearSnapshots and preserves copied history after confirmed gear removal. No standalone session equipment selector was added because Stage1 places selection in F07/F10. Read-only ShootPlan/Loadout accessors and immutable restored snapshot collections support this boundary. F08 formulas/services are unchanged; linked calculation results round-trip including infinity. HistoryRetriever.retrieve now reads real sessions; contextFor/agent execution remain scaffolded.
- Original design -> create/update overloads, manual-location coordinatesPresent flag, revision-aware repository insert/delete and typed codec -> resolve the documented optional-field gap and provide reliable persistent CRUD -> F01/UC01/SD01 with F05 historical-reference integration, original Facade/State preserved. Detailed changes and updated class/sequence diagrams: [F01 evidence](../Stage2/docs/f01-sessions.md). Stage1 and AGENTS.md unchanged; no new dependencies.
- Actual verification: baseline 71/71; final `./mvnw.cmd -B -o test package javafx:run '-Djavafx.args=--smoke-test'` passed **95/95** (24 added), zero failures/errors/skips, packaged JAR and GUI_SMOKE_OK. All 115 main/19 test sources recompiled; not a Maven clean build. Real JavaFX CRUD/restart/draft/conflict/lifecycle tests and separate CLI JVM UTF-8 restarts passed; F05/F08/localization regressions passed. Wide/narrow rendered JavaFX scenes inspected; source/UML audit retains 87 types/267 fields/184 operations/20 original hashes. Initial sandbox cache-access, fixture compilation and EXIF numeric-map decoding issues were fixed before final success. Windows only; no live providers, power-loss simulation, other platforms/scales or submission video.

## F05 implementation (2026-10-26)

- F05/UC05/SD02 is operational through `PhotoPilotFacade.inventory()` and the retained `saveGear(item)` route. `InventoryService.list(query, type)`, add/update/save overloads, duplicates, affectedSessions and remove perform deterministic shared work. F04 recognition/confirmation and F06 analysis are still unavailable.
- The five existing Equipment subtypes now expose immutable typed values; Equipment.validate and EquipmentType field definitions reject missing required details, invalid quantities, nonfinite/invalid dimensions and reversed focal ranges. EquipmentData explicitly maps typed values to storage; EquipmentForm maps GUI/CLI fields and invalidates evidence only when relevant values change. Unknown optional booleans remain distinct from false. GearSnapshot copies historical values/evidence.
- JsonInventoryRepository stores UTF-8 `{schemaVersion: 1, items: [...]}` at `PHOTOPILOT_DATA_DIR/inventory.json` (default `data/inventory.json`, relative to working directory). `transact` loads fresh data under a sidecar OS file lock, validates, flushes a same-directory temporary file and atomically replaces the inventory. No non-atomic fallback. Corrupt/unsupported/oversized files are preserved and returned as failures. Full-store save uses SHA-256 revisions; UI edits compare the original item to reject stale overwrites. Atomic move and advisory locking require filesystem support.
- InventoryPane reuses FeaturePane's content flow, labels, primary/secondary buttons, feedback cards and shared CSS. Forms wrap from two columns to one, tables use the existing palette, and asynchronous Task callbacks reject stale-navigation results. Navigation and window sizing remain unchanged. InventoryCommands supports quoted `gear` commands and F05 list alias; GUI and CLI share the same service and data root. Refresh/reopening F05 observes changes from another process.
- F06/F07 integration: read `GearInventory.items()` and typed getters/specifications; preserve unknown values and verification metadata. F06 itself was not implemented.
- Updated by F01 (2026-10-31): `JsonSessionRepository.referencingGear` scans actual saved plan/loadout GearSnapshots. Affected-session warnings and confirmed removal are tested against real storage; historical snapshots remain unchanged after live inventory deletion. Corrupt/unknown/unreadable stores still block deletion even after confirmation.
- Latest tests: Windows/JDK 21 `./mvnw.cmd -B -o test` passed **36/36**, including JavaFX desktop acceptance. Earlier in this implementation `clean test` passed 34/34; two additional safety tests then passed with the full suite. Graphical smoke and native visual inspection passed. Baseline was freshly verified at 6/6. Structural audit passed unchanged 87 UML types / 267 fields / 184 operations / 20 source hashes; its old blanket business-unimplemented footer is historical and is not F05 status.
- [F05 implementation evidence](../Stage2/docs/f05-inventory.md) owns detailed changed-file inventory, schema, Original design -> Implemented change -> Reason -> Impact decisions, tests, commands, limits and usage. Gson 2.11.0 is the only added direct runtime dependency. Stage1 artifacts and other feature workflows were preserved.

## Stage 2 delivery and traceability

Course Stage 2 instructions require accounting for every Stage 1 feature, at least ten meaningful working features, GUI and CLI access, at least five meaningful implemented patterns, LLM integration and meaningful agent behavior. Scaffolds, mockups and hard-coded responses do not qualify.

At least **five meaningful implementation/test code commits per period**, in Toronto time: October 20-26, October 27-November 2, November 3-9, 2026. Push during the relevant period; surplus commits do not transfer between periods. Empty, report-only, formatting-only and artificially split trivial commits do not qualify. This documentation setup does not count as a code commit. Never fabricate activity. Commit/push only under AGENTS.md authorization; this schedule does not grant automatic upload permission. This setup does not certify compliance with commit counts or remote publication.

Maintain Stage 1 feature -> use case -> implemented classes/methods -> patterns -> tests -> running demonstration traceability. Use the existing feature mapping rather than duplicating its complete class lists. Record changes as **Original design -> Implemented change -> Reason -> Impact**, including affected features, use cases and patterns. Significant deviations require updated diagrams. Preserve AI tool/model and representative collaboration records without secrets.

Final submission is due November 10, 2026: runnable source, reproducible README/build/configuration, safe example configuration, implementation-report PDF, weekly commit summary, and accessible feature-indexed video demonstrations collectively showing GUI and CLI. Full deterministic/KUMA validation belongs to Stage 3; Stage 2 still needs working features and basic verification. None of these outstanding deliverables is claimed complete here.

## Development history and actual verification

| Date / evidence | Confirmed result |
| --- | --- |
| Existing commit `9c3e45f` | Java architecture and GUI/CLI scaffold; detailed historical verification remains in Stage2/docs/verification.md. |
| Existing commit `bde01cb` | Minimalist JavaFX feature workspace; historical GUI/CLI and six-test evidence is in Stage2/docs/gui-redesign.md. Desktop inspection described there was not repeated in this setup. |
| 2026-10-26 memory setup | Reviewed existing instructions, source, tests, report, UXF diagrams and course specifications; merged AGENTS.md and created the two root docs. No features implemented or commits/pushes made. Existing uncommitted edits were retained. |
| 2026-10-26 fresh test run | From Stage2, local JDK 21 and existing Maven caches: `./mvnw.cmd -B -o test` -> BUILD SUCCESS, 6 tests, 0 failures/errors/skips. Compiler reported classes up to date; this was not a clean rebuild. Four BootstrapTest tests and two FeaturePresentationTest tests verify bootstrap/CLI/status behavior, not business completion. |
| 2026-10-26 fresh source audit | Python `Stage2/tools/verify_sources.py` -> PASS: 87 UML types, 267 fields, 184 operations, 83 relationships recorded, 20 original source hashes unchanged. This checks structure, not runtime invariants. |
| 2026-10-26 publication preparation | User authorized committing/pushing only the memory setup. Scope: new AGENTS.md memory sections and these two docs; pre-existing relocation/launcher instructions and all other local edits are excluded. Documentation checks use the staged content; the six-test result above is the setup run, not a new publication-time run. Feature status is unchanged. |

Windows test setup from Stage2 (use installed JDK 21 if the ignored portable tool is absent):

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk21/jdk-21.0.13.0.2+1').Path
$env:MAVEN_USER_HOME = Join-Path $PWD '.mvn/user-home'
./mvnw.cmd -B -o test
```

Use `test` without `-o` when dependencies legitimately need resolution. For GUI changes, additionally run `./mvnw.cmd -B javafx:run '-Djavafx.args=--smoke-test'` and inspect the real affected screens; for business changes, add relevant service and GUI/CLI acceptance checks. Record commands, scope, failures and limitations. Do not relabel previous runs as fresh results.

## Repository follow-up verification (2026-10-26)

The post-F05 Git follow-up includes the previously uncommitted repository-relocation path fixes, local launcher ignore rules/instructions, and removal of sidebar number decorations. Source-audit scripts resolve external course evidence from EECS3311; the original document hashes remain unchanged. This changes no feature scope. Local GUI/CLI launchers remain ignored and are not published.

Fresh `./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'` passed 36 tests with zero failures/errors/skips and printed GUI_SMOKE_OK. This reused compiled classes; it was not a clean rebuild. The structural audit passed all 87 UML types / 267 fields / 184 operations and 20 source hashes. Both Python tooling files passed syntax parsing; the one-time scaffold generator was not executed. F05 remains verified and the other ten features remain scaffolded.

## Maintenance and discoverability

Authorized localization publication verification (2026-10-27): user explicitly requested uploading this change to GitHub. Fetched origin and confirmed main matched origin/main before staging. Fresh `./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'` passed 71/71 with no failures/errors/skips and GUI_SMOKE_OK; classes were up to date, so this is not a clean-build claim. Structural audit again passed 87 types / 267 fields / 184 operations and 20 unchanged source hashes. Publication scope is localization source/resources/tests and its documentation; ignored launchers, preferences, data, caches and build outputs are excluded. The implementation-turn statement below that no commit/push occurred is historical; the authorized publication is a subsequent task.

Localization decision (2026-10-27): English-only presentation → `LocalizationService`, UTF-8 ResourceBundle resources, `LocalePreferenceRepository`, `FxLocalizer` Observer and model-port Decorator → immediate language changes without replacing views/business logic → F05/F08 presentation and F01–F11 status localized. GUI/CLI share `${user.home}/.photopilot/settings.properties` or `PHOTOPILOT_CONFIG_DIR`; language saving preserves unrelated properties and fails visibly. CLI `--lang` never writes preferences. No global JVM locale mutation. Existing Stage1 sources remain unchanged. [Updated implementation UML and change rationale](../Stage2/docs/localization-architecture.md).

Localization verification (2026-10-27): 53-test baseline passed; current **71/71** tests pass (18 added), with real JavaFX multiwindow switching/draft retention/thread/disposal checks, resource consistency, isolated preference failures, CLI integration and captured model-policy requests. GUI_SMOKE_OK, JAR packaging/resource inspection, independent UTF-8 CLI persistence/override runs, and structural audit (87 types / 267 fields / 184 methods / 20 source hashes) passed. Actual snapshots inspected for Chinese settings/editor/narrow results. Sandbox compilation needed existing-cache access; intermediate compiler/audit fixture issues and blank combo-box prompts were fixed. No clean rebuild, live model, macOS/Linux, screen-reader or video verification is claimed. No commit/push performed. Detailed evidence remains in [localization guide](../Stage2/docs/localization.md).

AI localization boundary: `LanguageAwareLLMClient.complete` copies `AgentContext` with a default response-language instruction and delegates once. It yields to explicit user language requests and all tool/schema/safety contracts. The Gemini provider must serialize this separate instruction when implemented; `PhotoPilotAgent.run` and `GeminiAdapter.complete` remain unavailable. Do not claim working bilingual AI responses from passing policy tests.

Root AGENTS.md explicitly routes task start/end to these files. No nested AGENTS.md or AGENTS.override.md was found in this repository during setup. Run future PhotoPilot chats with working directory ProjectGit or Stage2; parent EECS3311 chats must explicitly read the repository instructions. Verified placement and links establish discovery eligibility, not proof of a future session's injected context. Official behavior: [Codex AGENTS.md discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

Setup verification: all three files decode as UTF-8, all Markdown local links resolve, F01-F11 appear exactly once in the status table, and Git ignores none of these files. `git diff --check` passed (existing Windows line-ending warnings only). A before/after SHA-256 comparison of tracked files found only AGENTS.md changed by this setup; implementation files and pre-existing edits were preserved.

At task end, update affected status/issue entries and add only significant, dated history with evidence. Remove or supersede stale claims; retain historical outcomes as historical. Proposed decisions remain proposals until accepted and implemented as applicable. Keep secrets and unnecessary user data out of all memory.

## F08 implementation (2026-10-26)

- F08/UC08/SD03 is verified complete through unchanged `PhotoPilotFacade.calculate`. TechnicalCalculator implements the six original methods; the UI splits exposure and ND into seven choices. CalculationInput/CalculationValidation own explicit schemas, units and ranges; immutable CalculationResult retains values/units/assumptions/input; CalculationForm shares field definitions, fraction parsing, formatting and inventory prefill.
- Formulas: setting EV=log2(N²/t), normalized EV100=EV-log2(ISO/100); ND=t*2^stops (factor and OD supported); H=f²/(N*c)+f, h0=H-f, near=s*h0/(h0+s-f), far=s*h0/(h0-s+f), Infinity for s>=H. FOV=2 atan(dimension/(2f)); storage=shots*MB/1000 GB; cards and rated-shot batteries round up. Optical lengths mm internally, subject input m, distance output m; explicit CoC. Double precision and six-decimal half-up display with scientific notation for extremes; BigDecimal capacity ceilings avoid binary-roundoff overcounts.
- CalculatorPane reuses FeaturePane, shared inventory form CSS and existing navigation/resizing/feedback. CalculationCommands supplies `calc help`, seven commands and F08 alias. GUI/CLI share Facade/service. Optional F05 read-only prefill copies known sensor/prime-lens/ND/card/rated-shot values; no inferred zoom focal length, taking aperture, CoC or mAh-to-shots conversion. Manual input always works. F05 implementation code is unchanged.
- Existing Facade pattern retained; strict bounded CalculationTool adapter now returns deterministic JSON evidence, with explicit Infinity strings. No new pattern or LLM math. F10 agent registry/orchestration and result/session persistence remain scaffolded. Provider/agent integration is not claimed.
- Original design -> concrete per-operation schemas and rated-shot battery estimates -> reproducible math using available F05 data -> original six-operation architecture preserved. Prompt EV100 ambiguity resolved with two explicit labels. All decisions, changed-file inventory, formulas, assumptions, examples and traceability are in [F08 implementation evidence](../Stage2/docs/f08-calculators.md).
- Actual verification: baseline 36/36; Stage A 44/44 + GUI_SMOKE_OK, committed/pushed as `c4ae938`; Stage B final `./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'` passed 53/53 and GUI_SMOKE_OK (101 main / 10 test sources compiled), including F05 regression and real GUI controls for all seven choices. Separate real CLI process passed all seven and invalid aperture. Native exposure form/result and rendered wide/narrow screens inspected. Structural audit passed 87 types / 267 fields / 184 operations / 20 original source hashes.
- Verification limits: Maven clean was blocked by an occupied native cache DLL; generated classes/test-classes were explicitly removed and all sources recompiled successfully while preserving the cache. Dependency-cache access required elevated runs. Windows only; other platforms/scales, submission video and F10 orchestration remain unverified. Optical/capacity results are estimates with explicit limitations, not device calibration.
