# Execution feedback, next-step guidance and persistence status (2026-11-23)

Cross-cutting optimisation of F01–F11 and the supporting features. It adds no business feature and changes no
business rule: every operation still goes through `PhotoPilotFacade` and the same services, and the GUI and CLI
present the same events. Input: the Stage2OptRe1 review (`Project/Stage2OptRe1.pdf` and its evidence folder, outside
Git), in which most AI workflows ended in HTTP 503 or timeouts after a silent wait.

## Problems found (before)

- **One worker per window.** `UiOperations` ran every GUI task, local or remote, on one thread in submission order.
  While F10 waited 20–60 s for Gemini, a refresh on any other page queued behind it: measured **3,932.8 ms** for an
  F01 refresh during a 4 s Gemini hold (harness below).
- **No stage information.** Pages showed one static sentence ("Generating the shoot plan…") until the result or the
  error arrived; no elapsed time, no indication of which provider was being waited on, no persistence state.
- **No retry policy anywhere.** A single HTTP 503 ended F02–F11 requests at once, although 503/429 cost no tokens.
- **No cancellation.** A pending Gemini request could only be waited out (up to timeout + 15 s grace).
- **"Busy" between lanes.** `AtomicFiles.locked` used a non-blocking OS lock; any second writer of the same process
  failed with *storage busy*, which is why all work had to share one thread.
- **No next-step guidance** beyond a few CLI hints; prerequisites were stated as errors only.

Measured and *not* changed: reading the session store costs **~3 ms per `search("")` with 61 sessions** and
**~1.9 ms per inventory read** (both before and after). A read cache would save a few milliseconds per page at the
price of stale-data and shared-mutable-object risks across GUI and CLI processes, so none was introduced.

## Architecture (Observer)

```
GUI page / CLI command ──start──▶ OperationContext (subject) ──events──▶ OperationProgressView (FX thread)
        │                              ▲   ▲   ▲                         CliProgress printer (stderr)
        └──run(work) on a worker ──────┘   │   │
             Progress.stage/waiting/validating/retrying/count/write  ◀── services, agent, adapters, repositories
```

| Class | Package | Responsibility |
| --- | --- | --- |
| `OperationStatus`, `PersistenceStatus`, `ErrorKind` | `progress` | Status vocabulary; error class derived from the existing localized failure keys. |
| `OperationStage`, `NextStep`, `ExecutionProgressEvent` | `progress` | Immutable event data: stages actually entered, pending declared stages, elapsed time, attempt, counts, persistence, error class, next step. Keys and arguments only — never prompts, model text, keys or file contents. A pending stage whose active label takes an argument (`OperationStage.PARAMETERIZED`: `import.source`, `env.sun`, `match.rank`) is shown with its own `progress.queued.<key>` label (Stage2OptRe2 D02: "Checking {0}"). |
| `ExecutionProgressListener` | `progress` | Observer interface. |
| `OperationContext` | `progress` | Subject: one operation, its declared plan, thread binding, cancellation, outcome. |
| `Progress` | `progress` | Ambient publication API for code running inside an operation; a no-op outside one. |
| `OperationKind` | `application` | Catalogue of the 23 tracked operations (feature, title, declared stages, background lane, cancellable, result kind); `ENV_RECORD` and `advancesSession()` (the milestone saves) came with the [session workflow](session-workflow.md). |
| `NextSteps` | `application` | Deterministic guidance before (prerequisites), after success, after failure/cancel; shared by GUI and CLI. |
| `RetryingLLMClient` | `integration` | Decorator: bounded retry of HTTP 5xx/429 with doubling waits, cancel-aware. |
| `OperationRunner`, `OperationProgressView`, `NextStepHint`, `NavigationRequest` | `ui.gui` | Page runner, shared progress component, prerequisite line, next-step navigation event (feature pages plus the SHOOT, SESSION, REPORT and SHUTTER targets, one target-to-title resolver). |
| `CliProgress` | `ui.cli` | CLI presentation of the same events on the progress stream. |

Publication points are real execution points: `GeminiAdapter` just before the request is sent (`ai.wait`), the agent
(`agent.history`, `agent.tool <name>`, `ai.check`), the environment, geocoding and catalogue adapters before each
provider request, `ExifLibraryAdapter` per file read (real counts), `JsonSessionRepository`/`JsonInventoryRepository`
around every write (`Progress.write`), and a few stage calls in the services (for example F10: `plan.inputs`,
`plan.offers`, `plan.environment`, `plan.validate`, `plan.ready`, `plan.save`). No timer, animation or percentage
drives a stage; the GUI clock only redraws the real elapsed time every 100 ms while an operation runs.

Rules of the context: declared stages not reached are dropped (never shown as done); undeclared ones (tool calls,
retries, cached data, AI fallback) are inserted where they happen; a finished operation publishes nothing more; a
listener exception never breaks the work; `run` restores the previous binding and clears its own cancel interrupt
before the worker thread is reused.

## Persistence status

`Progress.write` brackets each repository write: SAVING before, then SAVED only when the repository returned success,
otherwise SAVE_FAILED, CONFLICT (`error.session.changed`, revision checks) or RETRY_REQUIRED (busy). A successful
operation that wrote nothing is labelled by its kind: UNSAVED_DRAFT (F03/F06/F10/F11 drafts, F04 recognition) or
RESULT_AVAILABLE. Atomic replacement, OS file locks, optimistic revisions, history rules, profile isolation and
recovery are unchanged. `AtomicFiles.locked` now first takes an in-process lock per store (bounded 10 s wait), so the
GUI's two lanes queue for each other; another process still gets *busy* at once (`PersistenceIntegrationTest`).

## Threading

`UiOperations` keeps its ordered single-thread local lane (`execute`, unchanged; `UiOperationsThreadingTest` still
holds) and adds a background lane of three daemon threads (`executeBackground`) for operations whose
`OperationKind.background()` is true: every Gemini/provider request, F11 metadata scans and the shutter-count check.
Both lanes count as busy, so profile switching and window closing still wait. Each page keeps its own busy flag and
control locking (duplicate clicks start nothing); `OperationRunner` delivers a result only to the operation that is
still current for that page and never after a cancel. The progress view is created and shown synchronously in the
click handler (measured **1.9 ms** click → visible), before any worker runs.

## Retry and external failures

`RetryingLLMClient` is the only retry layer for Gemini (wired in `ApplicationBootstrap` under the language decorator).
Retried: `ai.error.serviceUnavailable` (5xx) and `ai.error.rateLimited` (429). Not retried: timeouts (already a long
wait), network, credentials, request errors and malformed/blocked/empty replies. Default 2 retries (3 attempts), waits
1 s then 2 s; `GEMINI_RETRIES` (0–3) overrides; the `ai-test` diagnostic always sends exactly one request. Each wait is
published as `HTTP 503. Retry 1 of 2 in 1 s`; after the last attempt the provider's own failure is returned unchanged.
Existing manual/deterministic fallbacks (F03/F06 manual entry, F07 deterministic ranking, F09 facts-only) are kept and
now visible as stages (`match.fallback`, `env.factsOnly`). Test fixtures that queue exactly one reply per request set
`GEMINI_RETRIES=0` (`FakeGemini.environment`); retry behaviour has its own tests.

## Cancellation

Offered for background operations that only read and call providers (F02, F03 parse/clarify, F04 recognise/verify,
F06 analyse/clarify, F07 match, F09 lookup/assess, F10 generate/revise, F11 review draft, Mini AI). Cancel interrupts
the worker, which cancels the in-flight HTTP exchange (`BoundedHttp`) or the retry wait; the result is discarded, the
page is restored and shows *Cancelled before completion; nothing was saved.* A write that has started is never
interrupted (the control is withdrawn while SAVING); a cancelled operation never starts one. Not cancellable: local
saves (approve, confirm, select, import, environment record), F11 metadata analysis and the shutter check (they use the shared ExifTool
process, which an interrupt must not reach).

## Next-step guidance

Before execution: `NextStepHint` lines from the services' own blocker keys (F06 without a confirmed requirement, F07
without a usable profile, F10 `PlanInputs.blockers`, F11 `ReviewInputs.reviewBlockers`) with an *Open …* button.
After execution: `NextSteps.after(kind, result)` in the progress card and as the CLI `[NEXT]` line — the workflow
successor on success (F03 confirm → F06, F06 confirm → F07, F07 select → F09, F09 → F10, F10 approve → F01, F11 save →
F01, F04 import → F05), the manual alternative after a provider failure, *reopen and repeat* after a conflict.
After an operation that saved a session milestone (`OperationKind.advancesSession()`: requirement, profile, selection,
environment record, plan, statistics, review), `OperationRunner.guidance` and `CliProgress.run(kind, session, work)`
replace that successor with the session's next milestone from `SessionWorkflowService`, so the progress card, the CLI
`[NEXT]` line and the sidebar name the same step; a complete session keeps the fixed step. After a success the card's
button reads *Continue to …*, otherwise *Open …*. F01's session editor shows the workflow context line and a
*Continue to …* action for the recommended milestone ([next-step resolution](session-workflow.md#next-step-resolution)).
Navigation is a `NavigationRequest` event handled by the workspace's normal sidebar navigation; nothing is approved,
confirmed, saved or deleted by guidance.

## GUI and CLI presentation

GUI: the progress card sits inside each page's existing feedback card (F02, F03, F04, F06, F07, F09, F10, F11): title,
spinner (indeterminate), real clock, Cancel, stage rows (✓ done, ● active, ○ pending, ✕ failed — drawn as shapes in
theme tokens, so light/dark and accent colours apply), *Then: …*, attempt and file counts with a bar only for counted
work, persistence line, *Next: …* with *Continue to …* (success) or *Open …*. Home Mini AI keeps its status line and adds one small stage/next line.
F08 calculations stay synchronous and immediate (no indicator).

CLI: `PhotoPilotCLI.progress(stream)`; `main` uses standard error (flushed per line; `PHOTOPILOT_PROGRESS=0` turns it
off). Standard output, its text and the exit codes are byte-for-byte unchanged (`CliProgressTest`). Line format
(illustrative values, not a recorded run):

```
[F10] Loading requirements and profile
[F10] Checking owned gear
[F10] Waiting for Gemini
[F10] HTTP 503. Retry 1 of 2 in 1 s
[F10] Waiting for Gemini
[F10] Generating photography plan: Failed (34.8 s)
[NEXT] Retry; your inputs are unchanged.
```

All texts are in `messages*.properties` (English and Simplified Chinese, key-identical; `NextStepsTest` checks every
title, stage and guidance key of the catalogue in both languages).

## Verification (2026-11-23)

JDK 21, offline Maven cache, isolated copies, fresh data/config, no API key, loopback fakes only (no live provider).

| Measurement (same harness on baseline and optimized copies) | Before | After |
| --- | --- | --- |
| F01 refresh while F10 waits 4 s on Gemini | 3,932.8 ms | 31.0 ms |
| F10 click → progress visible (same FX pulse; `ExecutionFeedbackGuiTest`, three runs) | — (static text) | 1.0–1.9 ms |
| F10 cancel → page usable (same test, three runs) | not possible | 21–29 ms |
| `sessions.search("")`, 61 sessions | 3.0 ms | 2.9 ms |
| `inventory.list("")`, 12 items | 1.9 ms | 1.8 ms |

New tests: `OperationContextTest` (7), `RetryingLLMClientTest` (5), `NextStepsTest` (4), `PlanProgressTest` (3),
`InProcessWriterTest` (2), `CliProgressTest` (2), `ExecutionFeedbackGuiTest` (3, real JavaFX controls and lanes).
Full suite: baseline 683 run / 0 failures / 0 errors / 6 skipped; final sources `mvnw.cmd -B -o package javafx:run
-Djavafx.args=--smoke-test` 709 run / 0 / 0 / 6 skipped (the same six opt-in live/private cases), JAR built,
`GUI_SMOKE_OK`. A real CLI process against a loopback server answering 503 then a reply printed the stages, the retry
and `[NEXT]` on standard error and nothing extra on standard output. The F10 card was checked visually from offscreen
snapshots in light, dark and Chinese. [Project memory](../../docs/PROJECT_MEMORY.md).

## Limits

- Live Gemini behaviour (real 503 bursts, real latency) was not exercised; all provider scenarios use loopback fakes.
- Settings writes (language, debounced appearance) still run on the FX thread; they are small preference files.
- F11 metadata analysis and the shutter check are not cancellable (shared ExifTool process); they show real counts.
- Stage plans in `OperationKind` must be kept in step with the services' stage calls; `PlanProgressTest` checks F10
  and `NextStepsTest` checks that every declared stage is localized, but not every feature's order.
- Results of a cancelled request may still arrive at the provider (tokens spent); they are discarded locally.
