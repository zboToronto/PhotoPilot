# PhotoPilot project memory

Last reviewed: 2026-10-26 (America/Toronto). This is the current context index; [FEATURE_STATUS.md](FEATURE_STATUS.md) owns per-feature status. Confirm against source before development. Design contracts below are requirements, not claims that their behavior works.

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
| State | `PhotographySession` uses Draft, Planned, Completed and Reviewed state classes. Explicit events drive transitions; approval/save constraints are required but not implemented. |
| Agent boundary | Bounded model/tool/evidence loop, allowlisted read-only tools, schema checks followed by deterministic domain validation. No autonomous save, inventory mutation or approval. Inventory persistence, calculations and EXIF statistics bypass the LLM. |
| Confirmation | F03 confirms requirements; F06 confirms a capability-only profile. F10 rechecks current state, constraints, session binding and revision before atomic save. Failed/cancelled operations preserve the persisted base. |
| Privacy and evidence | Order-image bytes cross the AI boundary only as user-approved SanitizedImage; privacy helper and image type share a package. Device location requires permission with manual fallback. EXIF review must disclose missing data and cannot invent measurements or aesthetic scores. |

Five pattern structures exist; their business operations are not yet five completed operational patterns. JSON repository names similarly do not prove persistence.

## UI baseline to preserve

`PhotoPilotGUI` owns lifecycle and callbacks; `PhotoPilotWorkspace` owns shell/navigation; `FeaturePane` owns feature content and feedback; `FeaturePresentation` maps stable F01-F11 identifiers to presentation labels. Reuse `src/main/resources/com/photopilot/ui/gui/photopilot.css` for visual styles.

- Workspace group: F01, F02, F03, F10. Equipment: F04-F07. Photography Tools: F08, F09, F11.
- Preserve bounded content width, wrapping text, independent navigation/content scrolling, selected-item behavior, keyboard navigation and focus styles. Existing defaults are 1120 x 760 with minimum 880 x 600.
- New forms/results belong in the existing feature content flow. A catalog marker must not enable an unconnected workflow. Currently the workflow button is disabled and Check status only reports availability.
- JavaFX changes run on the UI thread. Follow the existing background Task callback pattern for slow work, stale-navigation completion checks and shutdown cancellation.

## Known gaps and outstanding work

These are confirmed gaps, not permission to implement or redesign them.

| Issue | Evidence / required resolution |
| --- | --- |
| All F01-F11 business workflows are scaffolded | FeatureCatalog, service/repository placeholders and GUI disabled workflow; see feature status for scope. No real provider integration has been verified. |
| F01 optional location/notes mutation route missing | SD01 and source-verification gap table. Resolve a narrow application API when implementing the feature and record the design change. |
| F02 select/save/dismiss not exposed through Facade | DiscoveryService declares them, but Facade has no forwarding/accessor route. |
| F07 selected-loadout persistence missing | SD03 has no application selection/attachment operation. Strategy configuration exists at bootstrap; user-facing selection wiring remains unspecified. |
| Generic AI decoding unresolved | `Class<T>` cannot describe list element schemas; PlanCommand requires an explicit concrete subtype allowlist. |
| Approval and ownership invariants pending | Deep copy, immutable approved revisions, atomic aggregate persistence, multiplicities and stale/foreign revision rejection are design requirements, not established runtime behavior. |
| Integration choices pending | Concrete device-location adapter, equipment/weather providers and EXIF library remain unspecified; bootstrap has unconfigured endpoints and no active device provider. |
| Verification limits | No business acceptance tests, live AI/provider results or feature demonstration videos established by this setup. macOS/Linux rendering and other display scales are not verified here. |

The structural audit currently rejects any HTTP send call as a scaffold safeguard. When authorized integration work starts, revise that obsolete scaffold assumption deliberately and document why; do not treat it as a permanent prohibition on required LLM integration.

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

## Maintenance and discoverability

Root AGENTS.md explicitly routes task start/end to these files. No nested AGENTS.md or AGENTS.override.md was found in this repository during setup. Run future PhotoPilot chats with working directory ProjectGit or Stage2; parent EECS3311 chats must explicitly read the repository instructions. Verified placement and links establish discovery eligibility, not proof of a future session's injected context. Official behavior: [Codex AGENTS.md discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

Setup verification: all three files decode as UTF-8, all Markdown local links resolve, F01-F11 appear exactly once in the status table, and Git ignores none of these files. `git diff --check` passed (existing Windows line-ending warnings only). A before/after SHA-256 comparison of tracked files found only AGENTS.md changed by this setup; implementation files and pre-existing edits were preserved.

At task end, update affected status/issue entries and add only significant, dated history with evidence. Remove or supersede stale claims; retain historical outcomes as historical. Proposed decisions remain proposals until accepted and implemented as applicable. Keep secrets and unnecessary user data out of all memory.
