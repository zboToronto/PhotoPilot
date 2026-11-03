# F03 Natural-Language Photography Requirement Parsing

Stage 1: F03 / UC03 / SD01 ("parse or clarify a brief; user explicitly confirms edited/manual requirements"). Status: **implemented; GUI and CLI verified with an offline Gemini stand-in, plus a live Gemini smoke test** (see Verification).

## Workflow

1. Select an existing **Draft or Planned** session (Completed/Reviewed sessions keep their historical requirements).
2. Describe the shoot in natural language and choose **Analyze with Gemini** (`requirement parse <id> <text>`).
3. PhotoPilot sends only that text, the current date and the time zone to Gemini with a JSON Schema; the reply is decoded strictly and converted into an **unconfirmed draft**. Nothing is saved.
4. Review the 14 fields. Blank means *not specified*; nothing is defaulted. Fields Gemini marked ambiguous/contradictory are flagged, unspecified optional details are listed, and live deterministic validation shows what must be fixed.
5. Answer clarification questions (**Send answers to Gemini** / `requirement clarify <answers>`): the original text, questions, answers and the current (possibly edited) draft are resubmitted and a new draft is returned. Or edit any field directly (`requirement set field=value`).
6. **Confirm and save** (`requirement confirm`) validates again and atomically attaches the confirmed requirement to the session. **Cancel** (`requirement cancel`) discards the draft without touching storage.
7. Reopening the session (GUI saved-requirements card, `requirement show <id>`, `PlanningService.requirement(id)`) restores the confirmed requirement for F06–F10. **Edit saved requirements** works on a copy; the stored version changes only on a new confirmation.

If Gemini is unavailable (no/placeholder key, network, timeout, 401/403, 429, 5xx, malformed or blocked output), the error is shown, the request text stays in place, and **Enter manually** / `requirement manual <id>` starts a manual draft from that text.

## Execution flow

```
RequirementPane / RequirementCommands
  -> PhotoPilotFacade.parse(id, text)
     -> PlanningService.parse(id, text)            find(id); reject missing or historical session
        -> RequirementService.parse(text, ctx)     prompt + evidence {today, timeZone} + RequirementExtraction.SCHEMA
           -> PhotoPilotAgent.run(TASK, ctx, RequirementExtraction.class)
              -> LanguageAwareLLMClient.complete   adds response-language policy
                 -> GeminiAdapter.complete         systemInstruction + responseMimeType/responseJsonSchema; x-goog-api-key header
              <- validateOutput: one strict JSON object bound to the DTO, else ai.error.malformedOutput
           <- toDraft: impossible values -> not specified + review flag; never guessed
  <- unconfirmed ShootRequirement draft
RequirementForm.apply(draft, edits)                shared GUI/CLI field parsing
PlanningService.clarify(id, draft, answers) -> RequirementService.clarify(draft, answers) -> agent (same path)
PlanningService.confirm(expectedSession, edited)
  -> RequirementService.confirm -> ShootRequirement.confirm -> validate()/problems()
  -> PhotographySession.attachRequirement(confirmed)   only confirmed+valid, only Draft/Planned
  -> SessionRepository.save(session)                   atomic; stale revision rejected; failure never reported as saved
```

## Structured output and domain model

`service/RequirementExtraction` is the DTO and provider-neutral JSON Schema: `subject, goals, date (YYYY-MM-DD), startTime (HH:MM), timeWindow, location, durationMinutes, cameraCount, lensChangesAllowed, maxWeightGrams, portability, gearConstraints, priorities[{name, rank}], fixedConstraints[{name, value}], clarificationQuestions, flaggedFields`; every value is nullable. The task prompt forbids inventing dates, times, locations, durations, camera counts or equipment, keeps descriptive timing ("around sunset") as text and sets contradictory fields to null with a question.

`ShootRequirement` keeps all Stage 1 fields. Changes:

- `duration`, `cameraCount`, `lensChangesAllowed` are `Optional` (empty = not specified). Their JSON is unchanged for specified values, so existing files decode identically.
- Additive v1 fields `date` (`Optional<LocalDate>`), `timeWindow` and `portability` (blank = not specified). `SessionJson.decode` fills exactly these three when a pre-F03 record omits them; all other strictness checks remain.
- `problems()` / `validate()` (required subject; text/list limits; time and date on the same day; duration 1 min–14 days; 1–10 cameras; weight 0–100 kg; priority ranks 1–10; non-empty constraint names/values), `confirm()` (validated copy, questions cleared), `asDraft()`, `unspecifiedFields()`, transient `reviewFields` (draft-only, never persisted).
- The original 14-argument constructor still works (all values counted as specified).

`application/RequirementForm` is the single GUI/CLI text format: lists separated by `;`, date `YYYY-MM-DD`, time `HH:MM` (needs a date; uses the system zone), duration `90`, `90m`, `3h`, `1.5h`, `2h 30m` or `PT3H`, weight `800`, `800g`, `1.5kg`, lens changes `allowed`/`avoid`/blank, priorities `name=rank; ...`, constraints `name=value; ...`.

## Design patterns and decisions

- **Facade** (`PhotoPilotFacade.parse`, `planning()`), **Adapter** (`GeminiAdapter` alone knows Gemini's `systemInstruction`, `responseJsonSchema` and headers), **Decorator** (existing `LanguageAwareLLMClient`), **State** (`canEditPlan()` gates attachment) and the existing Repository/optimistic-revision persistence are reused. No new dependency, UI framework or global CSS.
- Original design → Implemented change → Reason → Impact:
  - `PhotographySession.attachRequirement` `void` → returns `Result<PhotographySession>` copy → matches F01's immutable copy + revision convention and reports rejections → F03 only.
  - `ShootRequirement` raw `Duration/int/boolean` → `Optional` + three additive fields → represent "not specified" without inventing values; Stage 1 lists date *or* time and portability separately → old JSON remains readable; structural UML audit may report the field-type change (not run: no Python).
  - `PlanningService` gains `clarify(id, draft, answers)`, `confirm(PhotographySession expected, edited)` and `requirement(id)` → SD01's clarification loop, stale-save protection and F06–F10 retrieval had no operation → `confirm(id, edited)` is retained and delegates.
  - `AgentContext` gains request-scoped `withTaskInstruction`/`withResponseSchema`; `PhotoPilotAgent.run` implements one structured step and rejects tool calls → F03 needs no tools; the F10 tool loop stays unimplemented.
  - `Result.failures(List<LocalizedMessage>)` → report every validation finding at once.
  - `GeminiAdapter` sends all user parts in one `contents` entry and adds labelled evidence → evidence must reach the model as data, not instructions.

## Verification

Offline tests use a loopback `HttpServer` (`requirement/FakeGemini`) behind the real `GeminiAdapter`; no test calls Google.

- `RequirementWorkflowTest` (10): detailed airshow request (two bodies, no lens changes, three hours, no invented date/location, nothing saved, request shape/no key in body); missing optional data; ambiguity flags + clarification + deterministic time/date contradiction; manual corrections and invalid values; confirmation, restart and retrieval; cancellation, stale session, replacement, historical sessions and storage failure; 503/429/401/placeholder key + manual fallback; malformed output; impossible model values; pre-F03 record loading.
- `RequirementCliTest` (2) and `RequirementGuiTest` (2): the same workflow through the real CLI and JavaFX controls, including off-FX-thread analysis, failure retention, manual entry, discard confirmation and restart restore; wide and narrow Chinese renders inspected.
- Updated expectations: `BootstrapTest` (F03 alias now works), `FeaturePresentationTest` (F03 implemented); workspace smoke navigation expects F03 implemented.

Results (Windows, JDK 21, 2026-11-03):

- `./mvnw.cmd -B -o test package javafx:run '-Djavafx.args=--smoke-test'`: **223/223** tests, zero failures/errors/skips, JAR built, `GUI_SMOKE_OK`. The first run failed only `BootstrapTest`'s outdated "F03 alias unavailable" expectation, which was updated. After the later location-rule prompt change, the focused 21 F03/bootstrap/resource tests passed again.
- **Live Gemini** (`gemini-3.5-flash-lite`, compiled CLI, scratch `PHOTOPILOT_DATA_DIR`, key loaded by the app from the ignored root `.env`): airshow request → draft with 2 bodies, avoid lens changes, 3h, "around sunset", no date, questions → `set` → `confirm` → `show`; and parse → `clarify` → `confirm` (date, location and goals filled from the answers). Output was checked for the key without printing it. The first run extracted "airshow" as the location; the prompt now forbids event/setting types as locations and the rerun left it unspecified with a question.
- Not performed: a live Gemini run through the desktop GUI (GUI tests use the real adapter against the loopback stand-in), other OS/DPI, source/UML audit (no Python).

Status is also recorded in [FEATURE_STATUS](../../docs/FEATURE_STATUS.md) and [project memory](../../docs/PROJECT_MEMORY.md).

## Limitations

- Location is a manual label (no coordinates/geocoding); times use the system time zone.
- Gemini requests run on the window's single worker queue, so other GUI operations wait up to the 30 s timeout.
- Only one structured model step; no automatic retry or repair of malformed output (the user retries).
- Clarification relies on the model to keep edited values; the user reviews the returned draft before confirming.
- Real-provider quality depends on the configured model; live checks are manual.

## Demonstration script (Stage 2 video, ~2 min)

1. GUI: Sessions & History → create "Airshow" (Draft). Open **Requirement Parsing**, select it.
2. Paste: *"I want to photograph aircraft at an airshow around sunset for three hours. I prefer carrying two camera bodies, minimizing weight, and avoiding lens changes."* → **Analyze with Gemini**; point out the progress text.
3. Show: Camera bodies 2, Lens changes *Avoid*, Duration 3h, Time window *around sunset*, Date/Location blank, "Not specified: Location", Gemini's questions; storage unchanged.
4. Type camera bodies `0` → Confirm → validation error. Fix to `2`; answer "November 14 at Lakeshore airfield" → **Send answers**; show date/location filled.
5. **Confirm and save** → success; switch to F01 and back → saved card restored. **Edit saved requirements** → Cancel → discard → unchanged.
6. CLI: `session list`, `requirement show <id>`; then `requirement parse <id2> ...`, `requirement set ...`, `requirement confirm`.
7. Failure path: set `GEMINI_MODEL` to an invalid model (or disconnect) → error, text kept → **Enter manually** → confirm.
