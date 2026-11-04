# F04 AI Gear Recognition and Automatic Import (UC04 / SD02)

Status (2026-11-03): **implemented and verified** through GUI and CLI with offline fakes, plus one live CLI smoke run against real Gemini, Wikipedia and Lensfun. Traceability: F04 → UC04 → SD02 → F05 (UC05 resulting entry).

## Workflow

```text
GUI GearImportPane (Equipment > AI Gear Import, or "Import from image (AI)" in Gear Inventory)
CLI ImportCommands  (import ..., F04)
  │  ImagePrivacyBoundary.open(path, kind)        validate by content, size, dimensions; decode locally
  │  ImageReview.redact/crop/undo                 user regions in original-image pixels
  │  ImagePrivacyBoundary.sanitize(review)        crop → black fill → downscale ≤ 2048 px → PNG re-encode (no metadata) → app temp copy
  │  (user reviews the copy itself)
  │  ImagePrivacyBoundary.approveRedacted(copy, true)   only an unchanged app-created copy → SanitizedImage
  ▼
PhotoPilotFacade.recognize(image) → InventoryService.recognize(image)
  → GearRecognitionService.recognize(image) → PhotoPilotAgent.run(TASK, context+image, GearRecognition) → LanguageAwareLLMClient → GeminiAdapter (inlineData)
  → GearRecognitionService.verify(draft) → EquipmentSpecificationProvider.search → EquipmentCatalogAdapter (Wikipedia API + Lensfun)
  ▼  ImagePrivacyBoundary.discard(image)          bytes zeroed, copy deleted (success or failure)
GearImportDraft (review: candidates, fields with status/source, conflicts, notices)
  │  select(i) / withEdits(map) / InventoryService.verify(draft) / InventoryService.importDuplicates(type, brand, model)
  ▼
InventoryService.confirm(draft, Decision) → GearRecognitionService.confirm(draft) (validated Equipment)
  → F05 InventoryService.add(item, duplicateAccepted)  or  F05 update(existing with quantity + n, expected)
  → InventoryRepository.transact → JsonInventoryRepository (atomic save)
```

`GearImportPresentation` is the shared GUI/CLI text. Message keys: `import.*`, `ai.error.imageNotApproved`.

## Privacy and temporary data

- Selecting or loading never uploads. `open` accepts PNG/JPEG detected from the file signature (extension ignored), rejects missing, non-regular, empty, truncated/corrupt, oversized (default 20 MB) and out-of-range images (32 px minimum edge, 16,000 px maximum edge, default 50 MP), reading dimensions before decoding. Limits: `PHOTOPILOT_IMPORT_MAX_MB`, `PHOTOPILOT_IMPORT_MAX_MEGAPIXELS` (1-100).
- Redaction replaces pixels with solid black at full resolution **before** downscaling; the copy is encoded from pixels only, so EXIF/text metadata of the source is not carried over (tested with a PNG `tEXt` owner chunk). The GUI preview of the copy decodes the written file, i.e. exactly the bytes approval would send. The CLI prints the copy's path so the user can open it.
- `approveRedacted` requires explicit confirmation, accepts only copies this boundary wrote, and compares a SHA-256 of the copy; a changed or foreign file (including the original) is refused. Purchase screenshots show an explicit personal-information warning in both interfaces. GUI and CLI enforce the same steps; the CLI offers real coordinate-based redaction/crop rather than relying on a checkbox.
- After recognition (success or failure) the approved bytes are zeroed and the copy deleted; after success, cancellation, completion or user switch the decoded original is released too. Only application-created copies are ever deleted; the user's file is only read. Image bytes are never logged or placed in error messages. A failed recognition keeps the review so the user can retry or switch to manual entry.

## Gemini recognition

`GearRecognitionService.TASK` instructs Gemini to report only what is visible (visible specifications only when printed or engraved; never from memory), to list at most four candidates with distinguishing details when ambiguous, to give a model code only when visible, never to transcribe personal information and never to claim verification. The response schema is `GearRecognition.SCHEMA`; output is strict-JSON decoded by the existing agent and then bounded: categories limited to CAMERA/LENS (OTHER → "enter manually"), text cleaned and length-limited, visible values kept only for keys of the category with plausible ranges, duplicates dropped. `readable=false` or no usable candidate fails with a request for a clearer image. No confidence scores are produced (the provider gives none). Tool calls are rejected by `PhotoPilotAgent.run`. Model output never writes anything.

## External specification verification

`EquipmentCatalogAdapter` (existing Stage 1 Adapter, previously pointed at `unconfigured.invalid`) now queries two free, key-less, documented sources and returns at most one matching product per source as `SpecificationEvidence` (source, link, product identity, stated values, retrieval time):

| Source | Access | Used fields | Licence / attribution |
| --- | --- | --- | --- |
| Wikipedia | MediaWiki Action API: `list=search`, then `action=parse&prop=wikitext|revid&section=0` for up to four results | Camera infobox: lens mount, sensor size, megapixels, burst fps, battery model codes, card formats, weight. Lens infobox: mount, focal range, maximum aperture (and long-end aperture of variable zooms), filter thread, stabilization, autofocus drive, weight | CC BY-SA; evidence links to the exact revision (`index.php?oldid=`) |
| Lensfun database | Raw XML data files `mil-/slr-/compact-/rf-<brand>.xml` (24 h in-memory cache, 404 = no file) | Product identity and mount label | CC BY-SA 3.0; evidence links to the data file |

Identity matching is exact over normalized tokens (`GearNames`): brand removed, punctuation/case/order ignored, "α"/"a7" read as "alpha", so "GM" never matches "GM II" and "EF" never matches "EF-S". Wikipedia pages also match through a bold or infobox model code (e.g. `ILCE-7M4`). Unparseable infobox values are omitted, never guessed. Requests carry an identifying `User-Agent` per Wikimedia API etiquette and no credentials. Endpoints: `PHOTOPILOT_SPEC_WIKI_ENDPOINT`, `PHOTOPILOT_SPEC_LENSFUN_ENDPOINT`. One unavailable source is reported as a notice; both unavailable keeps the draft as *unavailable/unverified*.

### Provenance rules (`GearImportDraft`)

The draft keeps image/user values ("base") and evidence separately and derives each field:

| Situation | Effective value | Status |
| --- | --- | --- |
| Sources agree, no image reading or the reading agrees | evidence value (Lensfun mount label preferred for consistent F07 matching) | `EXTERNALLY_VERIFIED` (source, link) |
| Sources disagree, or the image reading disagrees with the sources | blank, conflict listed with every claim | `UNKNOWN_OR_CONFLICTING` |
| Only the image reading | reading | `AI_PROPOSED` |
| User entry equal to agreeing evidence | entry | `EXTERNALLY_VERIFIED` |
| Any other user entry (including choosing between conflicting sources) | entry | `USER_PROVIDED` |
| Nothing | blank | `UNKNOWN_OR_CONFLICTING` |

Numbers compare up to decimal formatting (±0.005). Brand/model are verified when a catalogue identified the product. Changing brand, model or category discards the evidence; values shown for the previous product stay as user-provided with an explicit notice. On import only `EXTERNALLY_VERIFIED` fields become F05 `verifiedFields` with `sources` and `verifiedAt` (latest retrieval time).

## Review, confirmation and F05 integration

- Multiple candidates require an explicit choice (`select`), which re-runs verification; any field, the quantity and the category can be corrected.
- Deterministic validation reuses `EquipmentData.toEquipment`/`EquipmentType.validate` (required fields, numbers, focal range, prime aperture rule). Conflicting required values stay blank and block import; conflicting optional values are saved as unknown.
- Required values and the model identity that are not externally verified must be explicitly accepted (`Decision.unverifiedAccepted`; GUI checkbox / `--accept-unverified`).
- Duplicates: F05's rule (same type, brand, model) plus spelling variants of the same model; distinct variants are not reported and nothing is merged. The user chooses *add separately* (F05 `add(item, true)`) or *increase the quantity* of a listed record (F05 `update(..., expected)`, keeping that record's specifications and evidence; stale choices are rejected by F05). Without a decision F05 reports the duplicate and nothing is saved.
- The imported item uses the draft's UUID; a confirmed draft is marked and a second confirmation is refused (`import.error.alreadyImported`). Persistence failures are returned as failures and the draft stays unconfirmed. Saved equipment appears in Gear Inventory and to F06/F07/F10 through the existing repository; F01 historical snapshots are untouched.

## Design changes (Original design → Implemented change → Reason → Impact)

| Original design | Implemented change | Reason | Impact |
| --- | --- | --- | --- |
| `ImagePrivacyBoundary.approveRedacted(copy, confirmed)` / `discard(image)` only | Added `open`, `sanitize`, `discard(review)`, `discard(path)`, `owns`, `fromEnvironment` and the transient `ImageReview` in the same package | Stage 1 assumed a redacted copy exists; something must create it locally and verifiably | Stage 1 signatures kept; privacy helper and image type still share the package |
| `SanitizedImage` with private bytes, `clear()` | Accessors `approvedBytes()`, `mediaType()`, `userApproved()`, `cleared()`; the copy path is package-private | The adapter must read approved bytes; cleared/unapproved images must be detectable | Still only the boundary constructs it; never persisted |
| `GearImportDraft(candidates, selected, conflicts, confirmed)` with `correct`/`confirm` | Implemented with additive `Candidate`, `Field`, `Conflict`, `Identity`, `Decision`, base values + evidence, `select`, `withEdits`, `withEvidence`, `fields`, `problems`, `unverifiedCritical`, `markConfirmed` | Equipment objects cannot represent incomplete or conflicting drafts, and provenance per field is required | Stage 1 constructor/methods kept and working; transient |
| `EquipmentSpecificationProvider.lookup(model) : List<Equipment>` | Added default `search(type, brand, model, code) : List<SpecificationEvidence>` and model types `SpecificationEvidence`, `SpecificationStatus`, `GearNames`; `lookup` implemented on top | Evidence needs source, link and retrieval time without fabricating required Equipment fields | Adapter pattern unchanged; `SpecificationTool` stays scaffolded and is not offered to the model |
| `EquipmentCatalogAdapter(endpoint, http)` to an unspecified catalogue | Wikipedia API + Lensfun data files; additive constructor with Lensfun base, timeout and clock | Real, free, documented sources were needed; a hard-coded list is not verification | Two outbound hosts; configurable endpoints |
| `InventoryService.recognize/confirm(draft)` | Implemented; added `verify(draft)`, `importDuplicates(type, brand, model)`, `confirm(draft, Decision)` | SD02 needs re-verification after correction and explicit duplicate/unverified decisions | `confirm(draft)` keeps the strict behaviour (no implicit acceptance) |
| `GeminiAdapter` rejected images | Sends an approved `SanitizedImage` as `inlineData`; unapproved/released images fail before any request (`ai.error.imageNotApproved`) | UC04 image recognition | Text-only features unchanged |
| F04 route scaffolded | `GearImportPane` connected through `FeaturePane`/`PhotoPilotWorkspace`; Gear Inventory gains an *Import from image (AI)* entry; `ImportCommands` (`import`, `F04`); `FeatureCatalog` F04 implemented | GUI and CLI access | Scoped `.import-*` CSS only; no other page restyled |

## Verification (2026-11-03, Windows / JDK 21)

- Full suite: `./mvnw.cmd -B -o test package javafx:run '-Djavafx.args=--smoke-test'` → **326 run, 0 failures, 0 errors, 1 skipped** (the existing opt-in `LiveGeminiPlanSmokeTest`), JAR built, `GUI_SMOKE_OK` (all 11 features). Incremental build, not clean. Log: `Stage2/target/f04-verification.log` (ignored).
- 20 new offline tests: `ImagePrivacyBoundaryTest` (4: content validation, unsupported/corrupt/missing/empty/oversized/pixel limits/too small, pixel redaction + crop + downscale + metadata removal + original unchanged, explicit approval/foreign/tampered copy/cleanup), `GearImportWorkflowTest` (9: camera recognize → verify → confirm → reopen; lens with partial evidence and explicit acceptance; ambiguous candidates; unreadable/unsupported/malformed/timeout; no request without approval; conflicting sources and image-vs-source conflict; correction/provenance/validation; duplicates with quantity increase, stale choice and add-separately; persistence failure and catalogue outage), `EquipmentCatalogAdapterTest` (3: camera and lens parsing with provenance, GM vs GM II, variable-aperture zoom, not found, partial and full outage), `GeminiAdapterTest` (+1: inline image sent, released image refused locally), `ImportCliTest` (2: redacted screenshot through real bootstrap/CLI/storage, sent pixels black, copy released, original unchanged, reopen, duplicate decision; failures, ambiguous selection, cancel), `GearImportGuiTest` (1: real JavaFX controls from Gear Inventory through privacy review, approval, recognition, confirm, inventory, cancellation, minimum-size navigation, CLI reads the GUI import). `BootstrapTest`/`FeaturePresentationTest` expectations updated for F04.
- Live CLI smoke (scratch data directory, `GEMINI_TIMEOUT_SECONDS=120`, key from local `.env`, never printed): two synthetic product-label images with a fake "Ship to" block redacted. Gemini identified *Sony α7 IV* (code ILCE-7M4) and *Sony FE 24-70mm F2.8 GM* (code SEL2470GM); Wikipedia (revisions 1359416158 and 1310773267) and Lensfun identified both. The camera was imported with 10 externally verified fields (mount `Sony E` from Lensfun; sensor 35.9 × 23.9 mm, 33 MP, 10 fps, NP-FZ100, CFexpress Type A/SD/SDHC/SDXC, 658 g from Wikipedia); stabilization and AF modes stayed unknown. For the lens, Gemini's inference of a constant aperture at 70 mm stayed *Read by AI · unverified*.
- Rendered real screens inspected (English 1120 × 760, Chinese 880 × 600): Gear Inventory entry button, select, privacy review with redaction/crop overlay, sanitized copy approval, review/candidates/fields/sources, result. A double gap above the result card was found and fixed. Native file picker not exercised (tests set the path field).

## Demonstration script (Stage 2 video)

1. State the feature: "F04 AI Gear Recognition and Automatic Import (UC04, SD02); saves through F05." Start the GUI with `GEMINI_TIMEOUT_SECONDS=120`; do not show `.env`.
2. Gear Inventory → **Import from image (AI)**. Choose *Purchase screenshot*, browse to an order screenshot (use a mock-up or your own with private data you will black out).
3. Point out that nothing has been sent. Drag over name/address/order number, optionally crop to the product, **Create sanitized copy**; show the copy and the "metadata removed" note; tick approval; **Approve and recognize with Gemini**.
4. Show candidates, the identity line, each field's status and the *Sources* links (open the Wikipedia revision link). If ambiguous, choose the right candidate (**Use this model**).
5. Correct a value (e.g. quantity) and press Enter to show *Entered by you*; show the duplicate choice if the item already exists; **Confirm import**; **Open Gear Inventory** and show the new row; restart the app and show it again.
6. Error case: load a text file renamed `.png` (unsupported image), or **Cancel import** and show that the original file is still there and nothing was saved.
7. CLI parity: `import load "<path>" --kind=screenshot`, `import redact ...`, `import preview` (open the printed file), `import approve --confirm`, `import confirm`, `gear list`.

## Limits

- Coverage depends on the sources: Wikipedia has articles for popular bodies and many but not all lenses; Lensfun mainly provides identity and mount. Values without a source stay unverified/unknown. Mount labels differ between sources ("Sony E-mount" vs "Sony E"); comparison is normalized, and Lensfun's label is preferred so cameras and lenses match in F07, but labels entered manually elsewhere may still differ.
- Infobox parsing is field-specific; unusual formatting is omitted rather than guessed. Third-party lenses for several mounts list no single mount in Lensfun.
- Recognition quality depends on Gemini and the image; only PNG/JPEG input; one image per import; CAMERA and LENS only (other categories via manual F05 entry). GUI redaction is rectangle-based.
- Temporary copies live in the system temp directory (deleted on discard; `deleteOnExit` fallback if the process is killed). No live GUI run with real Gemini, other OS/DPI, screen readers or the source/UML audit were performed. No commit or push (the user handles Git).
