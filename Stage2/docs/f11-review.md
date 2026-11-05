# F11 AI Post-Shoot Analysis and Review

Stage 1: F11 / UC12 (import metadata, view shooting statistics) / UC13 (obtain and save a post-shoot review) / SD05. Patterns: Facade (`PhotoPilotFacade.analyze/review/reviews`), Adapter (`ExifLibraryAdapter` over metadata-extractor; `GeminiAdapter`), State (`CompletedState` → `ReviewedState` on `REVIEW_SAVED`).

## Status

Implemented and verified offline through GUI and CLI (2026-11-03), plus one opt-in live Gemini review run. UC12 never calls the model; UC13 drafts stay in memory until the user explicitly saves them.

## Dependency audit (before implementation)

| Dependency | Finding | Use in F11 |
| --- | --- | --- |
| F01 session persistence | Working: strict atomic `JsonSessionRepository` with revisions, state adjacency and historical-plan immutability checks. | Statistics and reviews are saved in the session aggregate with one atomic `save`. |
| Session lifecycle | Working `PlannedState → CompletedState` (F01 `SHOOT_COMPLETED`) and `CompletedState → ReviewedState` (needs a saved review). `attachStatistics`/`appendReview` threw `UnsupportedOperationException`. | Implemented both as Result copies; the State pattern performs the transition. |
| F10 approved plans | Working: `PlanningService.latest(session.plans())`; approved plans carry loadout snapshots, settings, strategy, priorities and windows. | Compared against; never modified. |
| Metadata extraction | Scaffold only: `ExifLibraryAdapter`, `MetadataExtractor`, `ExifRecord.validate` unimplemented; no EXIF library in the build. | Implemented with metadata-extractor 2.19.0 (Apache-2.0) and a Gson ExifTool-JSON reader. |
| Statistics/review models | `ExifStatistics` (histograms, missing counts) and `PostShootReview` existed with persistence codecs but no producer. | Reused; additive fields with decode defaults. |
| Gemini adapter / agent | Working structured output and `runWithTools` (history via `HistoryRetriever.contextFor`). | Reused unchanged: a bounded run with no permitted tools. |

## UC12 workflow (SD05, no AI)

GUI `ReviewPane` / CLI `review analyze` → `ReviewService.analysis(id, files, replace)` (Stage 1 `analyze(id, files)` kept) → `SessionRepository.find` (Completed only) → `MetadataExtractor.scan` (`ExifLibraryAdapter`) → `ExifRecord.normalized/validate` → `StatisticsCalculator.summarize` → `PhotographySession.attachStatistics` → `SessionRepository.save`.

- **Inputs:** JPEG (detected by the `FF D8 FF` signature, not the extension) through `JpegMetadataReader` with only the `ExifReader` segment reader (IFD0 make/model, EXIF sub-IFD); ExifTool JSON (`exiftool -json`, formatted or `-n`) with `SourceFile` entries; a folder contributes its own `.jpg/.jpeg/.json` files (not subfolders). Limits: 2000 files, JPEG 100 MB, JSON 20 MB / 20 000 entries.
- **Per-file outcomes:** read, read with warnings, no EXIF (still a photo with missing metadata), unsupported (detected type named, e.g. a PNG renamed `.jpg`), missing, unreadable, too large, corrupted, invalid structure (JSON that is not an ExifTool export), duplicate (same path or identical SHA-256 content, counted once). One failure never discards other files.
- **Fields:** camera = make + model (model alone when it already starts with the make or its first word: "Canon EOS R5", "NIKON Z 9"); lens = LensMake + LensModel; focal length (mm, actual, never 35 mm equivalent), exposure time (s, full precision, e.g. 1/2500 s), f-number, ISO (PhotographicSensitivity; 65535 means "65535 or more" and is used only via ISOSpeed), exposure compensation (EV), DateTimeOriginal with OffsetTimeOriginal. A make without a model is not a camera; placeholders like `----` are missing. No APEX derivation, no lens from file names, no GPS, maker notes, serial numbers or thumbnails.
- **Validation:** absent values are *missing*; present but unreadable/impossible values (zero denominators, focal ≤ 0 or > 5000 mm, f/0.1, ISO 0, impossible dates, |EV| > 15) are *invalid*, listed and excluded, never clamped. A record without any camera, lens or exposure field is imported but unusable.
- **Statistics:** histograms per recorded value; denominator of each field = photos that recorded it; missing counts include invalid ones (also counted separately); min/max/median/mean derived exactly from histograms; capture span on the camera clock with the UTC offset only when every timestamp recorded the same one. No quality or success score exists anywhere.
- **Persistence policy:** saved at once when valid (`SAVED`); the same files again → `UNCHANGED` (fingerprint + identical evidence, nothing rewritten); different statistics → `REPLACE_REQUIRED` (shown, not saved) until the user explicitly replaces them (`REPLACED`); no usable metadata → `NO_USABLE_DATA` (never overwrites, even with replace); write failure → `SAVE_FAILED` (storage byte-identical). Reviewed sessions keep their statistics (the evidence of the saved review).

## UC13 workflow (SD05)

`ReviewService.draft(id)` (Stage 1 `review(id)` kept) → `inputs(id)` names every missing prerequisite without calling Gemini (Completed state, usable saved statistics, latest approved plan, confirmed requirement; empty goals fall back to the confirmed subject with an explicit note) → `StatisticsCalculator.differences/compare(stats, plan)` → `ReviewEvidence.json` → `PhotoPilotAgent.runWithTools(task, context, ReviewInterpretation.class, Set.of())` (history added by `HistoryRetriever.contextFor`) → `LanguageAwareLLMClient` → `GeminiAdapter` (`responseJsonSchema`) → `ReviewEvidence.check` → `PostShootReview` DRAFT registered by object identity.

- **Plan versus actual (deterministic):** planned cameras/lenses matched to recorded names by exact normalized tokens (`GearNames.sameModel`; "ILCE-7M4" is never merged into "A7 IV"), extra recorded equipment, photos without camera/lens, photos outside every planned lens's focal range, each numeric plan setting (focal length, shutter, aperture, ISO, compensation) parsed by the same rules F10 validated (`PlanValidator.settingNumbers`) with inside/below/above counts, camera-bound settings flagged, capture span versus planned windows (only with a confirmed offset). A scope line states what EXIF cannot show.
- **Evidence sent:** aggregate statistics with denominators, plan gear names/settings/strategy/priorities/schedule times, subject and goals, comparison and limit lines. Never: file names, paths, notes, location, coordinates, GPS, serials, identifiers or images.
- **Claim checks:** statements claiming to have seen the photos, giving scores/ratings, or (in summary/observations) judging sharpness, blur, noise, composition or artistic success are removed; observations must cite only numbers present in the evidence (rounding allowed). Removed statements are counted in the draft notes and limitations. If nothing supported remains, no review is produced (`review.error.unsupported`). Malformed output → `review.error.malformed`; provider errors keep their `ai.error.*` messages; statistics stay usable.
- **Saving:** `save(id, review)` requires an issued draft of this session, reloads the session, rejects stale statistics or a changed latest plan, `appendReview(savedCopy)` → `apply(REVIEW_SAVED)` → one atomic save. A failed write leaves the persisted session Completed and the same draft can be retried; `discard` makes a draft unsaveable.

## GUI and CLI

GUI: **Photography Tools > Post-Shoot Analysis** (`ReviewPane`, reusing PlanPane's surface, cards, primary/secondary buttons, feedback box and background `Task` pattern; no new CSS). Cards: readiness, import (path field, Add, Browse… multi-select, selected file names, Replace checkbox when needed), import results, statistics sections, evidence limits, plan comparison, AI review (draft sections + Save/Discard), saved reviews. Both workers run off the FX thread with controls disabled and progress text.

CLI: `review help|show|analyze|stats|compare|generate|draft|save|discard|list|open`, alias `F11`. Both use `ReviewPresentation`, so identical files print identical statistics lines (tested).

## Design changes (Original design → Implemented change → Reason → Impact)

| Original design | Implemented change | Reason | Impact |
| --- | --- | --- | --- |
| `attachStatistics(stats)` / `appendReview(review)` return `void` (mutation) | Return `Result<PhotographySession>` copies | Same immutable-copy convention as F03/F06/F07/F10 (`attachRequirement`, `appendApprovedPlan`); invalid input reported, not thrown | SD05 sequence unchanged; caller saves atomically |
| `MetadataExtractor.extract(files)` only | Added `scan(files)` returning `MetadataScan` (records + per-file outcomes + content fingerprint); `extract` kept (warnings as text) | SD05 requires per-file errors that UI can localize; duplicate-import detection | Adapter pattern unchanged |
| `ExifRecord` 9 fields | Added exposure compensation, camera-clock time, recorded offset, invalid fields; `normalized()`; `equipmentName` | Spec fields; distinguish missing from invalid and zoned from unzoned times | Transient type; not persisted |
| `ExifStatistics` 10 fields | Additive invalid counts, compensation histogram, failed-file count, timed/zoned counts, capture span/offset, analysed time, source fingerprint; `validate()`, `summary()`, `sameEvidence()` | Spec statistics, honest denominators, repeated-import detection | v1 schema: older records decode with empty values (`SessionJson.withReviewAdditions`) |
| `PostShootReview` 8 fields | Additive optional session id, summary, technical/equipment/exposure observations, improvements, statistical references, status DRAFT/SAVED | Structured review instead of free text; ownership check | Older reviews: no recorded owner, empty sections, SAVED |
| `run("interpret verified shooting evidence", context, PostShootReview.class)` | `runWithTools(..., ReviewInterpretation.class, Set.of())` + deterministic conversion | Untrusted DTO like F03/F10; reuses the agent's history step | No tools are offered to the model |
| ReviewService analyze/review/save | Added `analysis`, `inputs`, `draft`, `discard`, `reviews` | Replace confirmation, readiness, identity-bound drafts, cancel, reopen | Stage 1 methods kept and delegate |
| PlanValidator setting parsing private | `settingNumbers(name, value)` public static accessor | Compare with exactly the numbers F10 validated | No F10 behaviour change |
| EXIF library unspecified | metadata-extractor 2.19.0 (+ xmpcore 6.1.11 transitive) | Mature read-only Java EXIF reader; no custom parser | One new runtime dependency |

## Tests (offline; no network)

`ExifExtractionTest` (8: real JPEG EXIF values and untouched original, ExifTool JSON formatted/numeric, unsupported/corrupt/empty/missing/no-EXIF/duplicates per file, folders, oversized export, missing camera/lens/focal/exposure, invalid values), `StatisticsCalculatorTest` (8: denominators, camera/lens counts, focal/aperture/ISO/shutter statistics, no usable data, invalid exclusion, partial records, plan comparison, no loadout/times/data), `F11WorkflowTest` (7: UC12 without Gemini incl. restart with no key and no network, persistence/restore, unchanged/replace/no-data/wrong state, grounded unsaved draft with privacy checks and no invented history, save → Reviewed → restart restore, discard/foreign drafts, unsupported claims removed, malformed/503 failures, missing prerequisites without model calls, injected and Windows read-only write failures with retry), `ReviewCliTest` (CLI workflow, shared service, GUI/CLI statistics consistency, Chinese output), `ReviewGuiTest` (real JavaFX controls, off-thread analysis, draft/discard/save, Chinese 880×600 render), plus updated `BootstrapTest`/`FeaturePresentationTest`. Opt-in `LiveGeminiReviewSmokeTest` (`PHOTOPILOT_LIVE_GEMINI=1`).

## Verification (2026-11-03, Windows / JDK 21)

- `./mvnw.cmd -B -o test package javafx:run '-Djavafx.args=--smoke-test'` with `PHOTOPILOT_LIVE_GEMINI=0`: **352 run, 0 failures/errors, 2 skipped** (the two opt-in live tests), all F01-F10 regression tests included, JAR and `GUI_SMOKE_OK` (incremental build, not clean). The baseline before F11 was the recorded 326 run / 1 skipped; it was not re-run separately. Log: `target/f11-full-verification.log`.
- Live: `PHOTOPILOT_LIVE_GEMINI=1 ./mvnw.cmd -B -o test -Dtest=LiveGeminiReviewSmokeTest` — plan prepared offline, then real Gemini (`gemini-3.5-flash-lite`, local key, never printed) interpreted the synthetic statistics in about 3 s; every statement cited evidence figures (none removed); saved only by the explicit save, Completed → Reviewed. Transcript: `target/f11-live-smoke.txt`.
- Rendered screens inspected: `target/f11-statistics-wide.png`, `target/f11-draft-wide.png`, `target/f11-saved-narrow-zh.png`.
- Not run: manual click-through of the live GUI with real photographs, native file chooser, structural UML audit (no Python), other OS/DPI.

## Demonstration (manual, about 6 minutes)

1. State **F11 AI Post-Shoot Analysis and Review** (UC12, UC13). Show a session with an approved F10 plan (F10 page) and mark it completed in Sessions & History (Planned → Completed).
2. Open **Post-Shoot Analysis**, choose the session; point at the readiness card (Completed, approved plan, goals, no statistics; AI review disabled).
3. Browse… and select several JPEG photos from the shoot plus one edited/exported JPEG without EXIF and one non-photo file (e.g. a PNG or text file). Show that only file names appear.
4. **Analyze metadata**: show per-file results (read / no EXIF / unsupported), saved statistics (cameras, lenses, focal length, shutter fastest/slowest/median, aperture, ISO, capture span) with "Based on N of M photos" and the evidence-limits card.
5. Show the **Plan versus recorded metadata** card: planned shutter range inside/faster/slower counts, unmatched camera names, lens coverage, scope sentence.
6. **Generate AI review**: read the summary/observations; show the notes card (history used, removed statements) and limitations; stress "Draft review (not saved)" and that the session is still Completed.
7. **Save review**: feedback "Review saved; the session is now Reviewed"; reopen the saved review below; show Sessions & History status Reviewed.
8. Restart the app (or switch pages) and reopen F11: statistics and saved review are restored; import is closed for the reviewed session.
9. Error/partial case: on another completed session analyse only the PNG/stripped JPEG → "No usable metadata; nothing was saved"; or disconnect the network and Generate → AI error while the statistics remain.
10. CLI equivalent: `review show <id>`, `review analyze <id> "photo.jpg" "export.json"`, `review compare <id>`, `review generate <id>`, `review save`, `review open <id>`.

Use `GEMINI_TIMEOUT_SECONDS=120` for live demos. Never show the `.env` file or the key. ExifTool exports for step 3 can be made with `exiftool -json -n *.JPG > export.json`.

## Known limitations

- Formats: JPEG and ExifTool JSON only; RAW, HEIC, TIFF and PNG metadata are reported as unsupported. Lens names live in the standard LensModel tag; bodies that store the lens only in maker notes show the lens as missing.
- Equipment names must match the F05 inventory names token for token; EXIF model codes (e.g. "ILCE-1") are reported as unmatched rather than guessed.
- Statistics combine all cameras; camera-bound plan settings are compared against all photos with a caveat. Capture-time comparison needs one recorded UTC offset for all timestamps.
- Claim checking is deterministic and English/Chinese keyword based; it removes unsupported figures and listed outcome words but cannot prove every sentence's meaning. Review text is stored in the response language; deterministic lines are stored in English like other persisted evidence.
- A reviewed session is read-only for F11 (one saved review per session); re-analysis is possible only while Completed.
- Related history comes from `HistoryRetriever` (other sessions with approved plans and a shared subject word). Since 2026-11-04 it also carries the latest saved reviews of those sessions: EXIF facts and saved advice, kept apart, bounded and labelled as background data (`HistoryMemoryTest`). `ReviewEvidence.check` accepts a figure about an earlier shoot only if it appears in that shoot's saved EXIF `facts`; figures that exist only in earlier AI advice (`pastAdvice`) or nowhere are removed (`ReviewHistoryCitationTest`). The check matches numbers, not attribution, so a history figure wrongly presented as this session's is not detected; prompt rule 6 asks the model to name earlier sessions explicitly.
