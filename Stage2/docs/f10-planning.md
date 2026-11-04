# F10 AI Shoot Plan Generation and Interactive Modification

Stage 1: F10, UC10 (generate and approve), UC11 (revise and approve), included UC14, sequence SD04. Implemented 2026-11-03.

## Status

Implemented; GUI and CLI verified with offline fakes (21 new tests) and one live run against real Gemini and the F09 providers (see Verification). F10 is reported as IMPLEMENTED by `FeatureCatalog`.

## Dependency audit (before implementation)

| Dependency | Actual state | How F10 consumes it |
| --- | --- | --- |
| F01 sessions | Verified. Draft → Planned already required an approved, non-provisional plan; `appendApprovedPlan` was a stub. | `appendApprovedPlan` implemented (Result copy); `apply(PLAN_APPROVED)` unchanged. |
| F03 requirements | Verified; `PlanningService.requirement(id)` returns confirmed requirements only. | Mandatory precondition; fixed constraints are derived from it. |
| F05 inventory | Verified. | Loadout availability re-checked at preview and approval; F05 removal warnings already scan saved plan loadouts. |
| F06 profile | Only the manual confirmation path exists (entered in F07); AI derivation (`analyzeNeeds`, `deriveProfile`) is still unfinished. | Reviewed profile is a mandatory precondition; hard needs become fixed constraints. F10 does not derive profiles. |
| F07 matching / UC14 | Verified; `compatibleCandidates`, `evaluate`, `CompatibilityValidator.assess/availability`. | The F07 selection (if still current) and the best verified candidates meeting the requirement limits are offered under labels. F07's AI ranking is not re-run (no duplicate ranking, no extra model call). |
| F08 calculator | Verified; `CalculationTool` strict JSON adapter. | Only tool permitted to the F10 agent; all plan calculations are recomputed by `TechnicalCalculator`. |
| F09 environment | Verified; assessments are not persisted. | Latest successful assessment per session is kept in memory by `PlanningService` (same run only); stale forecast groups are withheld. `EnvironmentTool` is **not** permitted to F10 because it needs coordinates, which never go to Gemini. |
| Agent / registry | `run` was single-step; `ToolRegistry.execute/schemas`, `ToolCall.validateAgainst`, `AgentContext.appendToolResult`, `HistoryRetriever.contextFor` were stubs. | Implemented (below). `GearMatchingTool` and `SpecificationTool` remain scaffolded and are never offered. |
| Command / validator | `ShootPlan` receiver methods, commands, `PlanEditor`, `PlanValidator` were stubs. | Implemented. |

## UC10 workflow (SD04)

`PhotoPilotFacade.planning().generatePreview(id)` (Stage 1 `generate(id)` / `generatePlan(id)` return the approvable plan or the blocking reasons):

1. Reload the session; require an editable state, confirmed F03 requirements, a reviewed F06 profile and a readable F05 inventory. If an approved plan already exists for the same confirmed constraints, generation is refused (use UC11); if the confirmed requirement changed since, the new proposal becomes the next revision of that plan (a *regeneration*).
2. `PlanAssembly.offers`: the F07 selection (if still owned, unchanged and UC14-usable), then verified F07 candidates that meet the requirement's camera-count, lens-change and weight limits, ordered fully suitable → fewest mandatory gaps → lighter (at most 6). Labels `L1…` and gear labels `G1…`; identifiers stay local.
3. `PlanAssembly.timing`: F09 assessment for the plan date with verified zone and availability (stale forecast/sun groups withheld and labelled), otherwise the confirmed start time + duration, otherwise no timing (schedule must be empty).
4. Evidence JSON: requirement (location as label), fixed constraints, capability needs, candidates with recorded specs, default mounts and baseline field-of-view results, timing, environment evidence ids, related history.
5. `PhotoPilotAgent.runWithTools` with `PlanGeneration.SCHEMA`; permitted tools: `calculation`.
6. `PlanAssembly.plan` converts the untrusted reply: unknown candidate/gear labels, invalid mounts and unreadable times become blocking problems; equipment named in the checklist is left out (reported); gear labels in text are replaced with names; calculations = baseline field of view + every successful tool call, recomputed by F08; session information and fixed constraints are copied from the requirement, never from AI.
7. `PlanValidator.assess` (below) produces violations, missing information, unverified assumptions and limitations. The preview is approvable only when valid and non-provisional (e.g. a plan without a verified loadout is provisional and cannot be approved).
8. The preview is registered (by object identity) for that session; nothing is saved.

## Agent and tool orchestration

* Protocol: the response schema is an envelope `{"step":"tool"|"final","tool":{"name","arguments"},"result":{task schema}}`, built by the agent from the task schema and the per-task permitted tool names. It works with Gemini structured output (`responseJsonSchema`) and is provider-neutral; provider-native function calling is not used.
* Each request carries the task rules, the permitted tool input schemas and the step budget. A tool reply is selected (`selectTool`), its name must be registered **and** permitted (otherwise the run stops: `ai.error.unauthorizedTool`), arguments are checked by `ToolCall.validateAgainst` (strict single JSON object, no duplicate keys, required/enum/type/additionalProperties/min/max) and then by the tool's own strict parser.
* Results go back as labelled data on a **fresh copy** of the context (`withToolResult`); failures go back as explicit failures, never as results. Identical repeated requests are not re-executed. More than 2 consecutive failing requests stop the run (`ai.error.toolFailures`); at most `maxSteps` (8) model requests (`ai.error.stepLimit`).
* `HistoryRetriever.contextFor(id)` adds up to 3 related sessions with approved plans (subject word match): subject, date, state, planned equipment names and top shot priorities only.
* Tools are fixed application code (F08 calculator); nothing can reach the filesystem, processes or a shell. The model never saves, approves or mutates data.

## Deterministic validation (`PlanValidator`)

`assess(plan, req, context)` / `validate(plan, req)`:

* Fixed constraints must equal `constraintsOf(current requirement, current profile)` (subject, goals, camera count, lens changes, weight limit, duration, portability, gear and fixed constraints, mandatory needs).
* UC14 on the loadout: violations and unverified relationships block; missing battery/storage is a limitation. Every item must still be owned and unchanged with sufficient quantity.
* Camera count = confirmed count; no lens changes ⇒ lenses ≤ bodies; weight limit must be verifiable and met; mandatory capability needs must be MET (unknown blocks), optional ones become limitations.
* Settings: allowlisted names; per-camera keys must name a body in the loadout; numeric settings parsed (single value or range): shutter 1/64000–3600 s, ISO 25–819200, exposure compensation ±10 EV, aperture 0.7–128 and never wider than the recorded maximum aperture of the relevant mounted lens, focal length within the mounted lens range. Descriptive values (e.g. "Auto") are unverified, not guessed.
* Calculations: each is recomputed by F08 from its stored input and must match.
* Schedule: structure, no overlap, total ≤ confirmed duration; new windows need a verified zone and availability; with F09 evidence they go through `FeasibilityValidator.checkTiming` (availability, arrival, light vs sun data, evidence ids, unsupported measurements, past); without it, availability/zone checks and the declared light is unverified. Windows carried unchanged from the approved base are not re-checked against today's evidence.
* Text bounds; checklist items naming equipment block; equipment categories mentioned elsewhere but not carried are unverified assumptions.

`validateChange(base, next)`: approved base, same id, revision + 1, fixed constraints preserved (except a regeneration), only sections declared by the applied commands changed, UC14 re-run on a changed loadout, calculations recomputed, no-op revisions rejected.

## Approval and session state

`approve(id, plan)`: the plan must be the exact preview issued for this session and not already approved (foreign/rejected/saved previews fail); the session is reloaded; binding: a new plan needs no saved plan, a revision must be latest saved revision + 1 (otherwise `plan.error.stale`); full revalidation against current requirement, profile, inventory and evidence; `validateChange` for revisions; `approvedCopy()` (provisional plans cannot be approved); `appendApprovedPlan`; `apply(PLAN_APPROVED)` only while Draft (Planned has no second transition); one atomic `SessionRepository.save` with the optimistic revision check. Success is returned only after the save; any failure leaves the stored file byte-identical.

## UC11 Command-based revision

`revisePreview(id, text)` → evidence includes `currentPlan` → `runWithTools` with `PlanChangeInterpretation.SCHEMA` → `clarify` returns the questions, `conflict` returns the explanation (nothing changes) → `PlanAssembly.commands` builds commands only for the named sections: `ReplaceLoadoutCommand` (verified candidate + mounts; settings bound to removed bodies are dropped and recorded), `ReviseScheduleCommand`, `ReviseStrategyCommand` (any subset of settings, strategy, shot priorities + trade-offs, preparation, checklist) → `PlanEditor.preview(base, commands)` = `copyForRevision()` → `applyTo(draft)` → `validateChange` → explanations/limitations refreshed → full `assess`. The comparison lists changed and unchanged decisions with before/after content. Approval saves the already validated draft; commands are never re-applied.

## Persistence and history

The v1 session schema keeps all plan revisions in `plans` (id + revision); earlier revisions are never modified (F01 already enforces historical-plan immutability for Completed/Reviewed). New persisted `ShootPlan` fields `summary`, `sessionInfo`, `strategy`, `equipmentNotes`, `tradeOffs`, `limitations`, `changeSummary` are filled with empty values when absent, so earlier files load unchanged. F09 window metadata stays transient; the window label is kept in `conditions`.

## GUI and CLI

GUI: **Workspace > Shoot Planning** (`PlanPane` inside the existing `FeaturePane`, same `feedback-box` cards, buttons, background Task lifecycle, FxLocalizer, bilingual). CLI: `plan show|generate|preview|compare|approve|reject|revise|list|open`, `F10` → help. Both call the same `PlanningService` through `PhotoPilotFacade` and render with `PlanPresentation`.

## Design changes (Original design → Implemented change → Reason → Impact)

* `PhotographySession.appendApprovedPlan(plan): void` → returns `Result<PhotographySession>` copy → same immutable-copy convention as `attachRequirement`/`attachProfile`; no in-memory state is published before the atomic save → F01/F10, State unchanged.
* `PhotoPilotAgent.run` single step → added `runWithTools(task, ctx, type, permittedTools)` returning `Run<T>` (output + tool exchanges + history count); `run` unchanged → F03/F07/F09 keep rejecting tool calls; SD04 loop implemented without a new framework.
* Native function calling → structured-output tool envelope; `GeminiAdapter` sends earlier tool exchanges as labelled data parts → works with `responseJsonSchema` and offline fakes.
* `ToolRegistry.execute(call)` → also `execute(call, permitted)`; `schemas()` omits scaffolded tools → per-task allowlist.
* `AgentContext.appendToolResult` implemented; added `withToolResult`, `withEvidence`, `withHistory` request-scoped copies → contexts already sent are never mutated.
* `ShootPlan` 12-field constructor kept; full constructor adds 7 persisted sections; draft-only transient `revisedSections`/`regeneration`; `approvedCopy`, `withExplanation`, `sections/differingSections`, setting-key helpers → structured plan instead of free LLM text; revision diffing.
* `ReviseStrategyCommand` original constructor kept; added a section-selective constructor; `ReplaceLoadoutCommand` optional equipment notes; `PlanEditor.preview(base, List<PlanCommand>)` → one request may change several sections; no new command subclasses.
* `PlanValidator(compat, calc)` kept; added constructor with `FeasibilityValidator`, `assess(…)` report, `Context`, `constraintsOf` → reuse F09 timing rules; full reports for the UI.
* `PlanningService.generate/proposeChange/approve` keep their signatures; added `generatePreview`, `revisePreview`, `planInputs`, `plans`, `reject`, `environmentFor` and an in-memory latest-F09-assessment map → previews with evidence for GUI/CLI; F09 results reach F10 without persisting them.
* `CalculationTool.input(json)` extracted (behaviour unchanged) → F10 recomputes tool evidence.
* `GEMINI_TIMEOUT_SECONDS` (5–300, default 30 unchanged) → larger F10 requests can be given more time.
* Home text "Coming later"/"Shoot planning is not available yet" and two F01 help texts updated to the real availability; layout unchanged.

## Tests (offline; no network)

`AgentToolLoopTest` (6), `PlanValidatorTest` (7), `F10WorkflowTest` (6), `PlanCliTest` (1), `PlanGuiTest` (1); `BootstrapTest`/`FeaturePresentationTest` status expectations updated; `FakeGemini.replyWith` added. `LiveGeminiPlanSmokeTest` runs only with `PHOTOPILOT_LIVE_GEMINI=1`.

Coverage of the requested scenarios: valid generation (F10Workflow), missing requirement/profile (F10Workflow), F07 loadouts offered/selected (F10Workflow, Validator), incompatible/unverified/unowned gear (Validator, revision UC14), missing and stale F09 evidence (F10Workflow), F08 tool use and recomputation (AgentToolLoop, F10Workflow, Validator), invalid numbers (Validator), valid/malformed Gemini output and provider errors (AgentToolLoop, F10Workflow), unauthorized/invalid/repeated tools and step limit (AgentToolLoop, F10Workflow), unapproved preview, approval saves, rejection does not save, Draft→Planned and invalid transitions (F10Workflow, Validator), successful revision preserving unaffected fields, conflict/clarify rejection, cancellation, stale revision, version history, persistence failure (Windows read-only file), AI failure leaves storage unchanged, restore after restart, GUI/CLI sharing the same PlanningService (PlanCli, PlanGui), and regressions (full suite).

## Verification (2026-11-03, Windows / JDK 21)

* Baseline before changes: `./mvnw.cmd -B -o test` → 270/270.
* Focused F10: `./mvnw.cmd -B -o test '-Dtest=AgentToolLoopTest,PlanValidatorTest,F10WorkflowTest,PlanCliTest,PlanGuiTest'` → 21/21.
* Full: `./mvnw.cmd -B -o test package javafx:run '-Djavafx.args=--smoke-test'` → 292 run, 291 passed, 0 failures/errors, 1 skipped (the opt-in live test), JAR and `GUI_SMOKE_OK`. Incremental build, not Maven clean.
* GUI renders inspected: `target/f10-preview-wide.png`, `f10-revision-wide.png`, `f10-saved-narrow-zh.png` (880 × 600, Chinese).
* Live (`PHOTOPILOT_LIVE_GEMINI=1`, `gemini-3.5-flash-lite`, real Open-Meteo/sunrise-sunset.org, `GEMINI_TIMEOUT_SECONDS=120`, isolated temp data): run 1 produced a plan whose schedule filled the whole 5.5 h availability; `PlanValidator` blocked it (332 > 180 min) — the prompt/evidence were then tightened (`maxTotalWindowMinutes`, DOF tool rule, no labels in text). Run 2: one DEPTH_OF_FIELD tool call, validation passed, approved (Draft → Planned); the "lighter" request was answered as a conflict (the lightest loadout meeting the mandatory 100–600 mm need was already chosen); a "tripod" mention was flagged as unverified. Run 3 hit a transient Gemini HTTP 503 (reported, nothing saved). Run 4: generate + tool call + approve, "lighter" revision (equipment notes only) approved, "prioritize close-ups" revision (shot priorities + trade-offs only) approved, one-body request rejected as a conflict; history [1, 2, 3]. Transcript: `target/f10-live-smoke.txt` (ignored build output).

## Demonstration (manual, about 6 minutes)

Preparation: `.env` with `LLM_API_KEY`; optionally `$env:GEMINI_TIMEOUT_SECONDS='120'`. Use a demo user/profile, not real data.

1. Status: Home shows **Shoot Planning** as ready; `status` in the CLI lists F10 IMPLEMENTED.
2. F05: add the demo gear (two Sony E bodies, 200-600, 100-400, 70-300 lenses, NP-FZ100 batteries, SD cards).
3. F01: create "Airshow demo" for a date within the next week. F03: parse "Fast-moving aircraft at an airshow, about three hours, two camera bodies, avoid lens changes, telephoto and portability matter" and confirm.
4. F07: enter needs (focal length 100–600 mandatory, camera bodies 2, burst ≥ 10, lenses ≤ 2, weight ≤ 6000 optional), confirm, optionally Match My Gear and select a loadout.
5. F09: look up the airport, set the availability, assess (this is the timing evidence F10 uses).
6. F10 GUI: open Shoot Planning, show the inputs card, **Generate plan**; point out the progress message, the validation card, equipment (UC14), settings, schedule (F09 evidence ids), packing list, calculations and the tool-call line in **Evidence and tool use**.
7. **Approve and save**; show the session state Planned (F01) and the saved plan card. Restart the app and reopen to show it is restored.
8. Enter "Prioritize close-up aircraft shots; keep the same equipment and schedule." → **Preview change**; show the comparison (changed vs unchanged, fixed constraints preserved), then approve; show revision history.
9. Error handling: request "Use only one camera body and change lenses whenever needed." → conflict explained, nothing saved; optionally stop the network to show the provider error.
10. CLI: `plan show <id>`, `plan list <id>`, `plan open <id> 1`, `plan revise <id> "…"`, `plan compare`, `plan reject`.

## Known limitations

* F06 AI profile derivation is still unfinished; F10 needs a manually confirmed profile.
* F09 assessments are kept in memory only; F10 uses the latest assessment from the same application run (GUI or CLI process); otherwise timing falls back to the confirmed start time + duration or no schedule.
* Only the F08 calculation tool is permitted; environment and specification tools are not offered to the model.
* Settings are validated against recorded lens data (aperture, focal range) and physical bounds; camera-specific ISO/shutter limits are not recorded in F05 and are not checked.
* Free-text equipment mentions are detected by keywords (English/Chinese) and reported as unverified; they are not semantically understood.
* After a plan is approved, a new generation is only possible when the confirmed requirement changed; otherwise use revisions.
* Live Gemini latency varied from about 10 to 90 s per run; the default 30 s timeout may be too short for some F10 requests (`GEMINI_TIMEOUT_SECONDS`). Live quality depends on the model; validation blocks, but does not repair, non-compliant proposals.
* Live GUI run, macOS/Linux and other display scales were not verified.
