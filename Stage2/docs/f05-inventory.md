# F05 gear inventory implementation and verification

Verified on 2026-10-26, America/Toronto, Windows with JDK 21. F05 is implemented. *Current status (2026-11-04): all of F01–F11 are implemented; statements below about F01, F04 and F06 being unavailable describe the state on 2026-10-26 and are marked historical. Current evidence: [feature status](../../docs/FEATURE_STATUS.md).*

## Usage and scope

The existing **Equipment > Gear Inventory** page supports adding, browsing, searching, filtering by type, editing and removing Camera, Lens, Filter, Battery and StorageMedia records. Add/edit forms contain every planning specification in those existing domain classes. Quantities are positive integers; numeric details must be finite and within their valid ranges; lens minimum focal length cannot exceed its maximum. Blank optional fields remain unknown. Manual entry does not claim externally verified specifications.

Select a row and choose Edit selected or Remove selected. Save equipment confirms the reviewed form. Removal has an inline confirmation card. A possible duplicate (same type, brand and model, case-insensitive) is rejected until reviewed and explicitly accepted; a duplicate UUID is always rejected by add. Editing or deleting an item changed by another interface fails with refresh/review guidance. Cancel does not mutate storage. Changing a previously verified specification clears that field's verification/source; changing model identity clears prior model evidence.

The CLI uses the same Facade/service, without initializing JavaFX:

```text
gear help
gear add camera brand="Example Brand" model="Camera One" mount="Example Mount" quantity=1
gear add lens brand="Example Brand" model="Zoom" mount="Example Mount" minFocalMm=24 maxFocalMm=70 maxAperture=2.8
gear list query="zoom" type=lens
gear edit <UUID returned by add> quantity=2
gear remove <UUID returned by add>
```

`F05` aliases `gear list`. `gear help` lists exact field names for all five types. Edit changes only supplied fields; `weightGrams=""` clears an optional value. Values containing spaces support single or double quotes. `--confirm-duplicate` accepts a reviewed possible duplicate; it does not bypass duplicate UUID checks. `--confirm` accepts a saved-session removal warning. Unknown actions, options, duplicate options, invalid IDs and unclosed quotes return errors. Tests use fictitious local fixtures, not demonstration results in production code.

### Shutter count checker (2026-11-09)

A second card, **Gear tools > Shutter Count Check** (器材工具 > 快门次数检测), sits directly below the inventory card on the same page, and the CLI has `shutter models` / `shutter check`. Both use `ShutterCountService` through `PhotoPilotFacade.shutterCount()`. It reads a photo's camera counter with the bundled official ExifTool 13.62 (Perl included; no ExifTool or Perl installation needed). It is read-only and local: photos are never modified, copied, uploaded or stored, and no result is persisted.

**No manual camera selection (2026-11-10).** The card has no brand, series, model or search controls; the camera always comes from the photo (`ShutterCountService` → `CameraModelCatalog.detect`). After the description, a blue **Supported models** (支持机型查询) link opens `SupportedModelsDialog`, a lookup-only modal in the application dialog style (`AppDialogs`).
- It lists the existing catalog one brand at a time (Sony/Nikon segmented control), grouped by series, each model with its support level (evidence as a tooltip).
- Its search uses `CameraModelCatalog.search`, so it filters as you type by product name, ID or naming variant.
- It shows the catalog source and closes with Close or Escape. Clicking outside does nothing, as for the other modal dialogs.
- It selects nothing for the check. The GUI therefore no longer shows selected-versus-detected messages; it shows the detected camera's support level. The CLI's optional `brand=`/`model=` comparison is unchanged.

**Photo import (2026-11-10).** The import area is the shared `FileDropZone` (see [GUI extension points](gui-redesign.md#behavior-and-extension-points)) around the unchanged title, formats line, **Choose photo…** button and privacy note. In this card it is at least 240 px tall (it was about 150 px), with its content centred. Files dragged from File Explorer and the chooser's result go through the same zone validation (one file, not a folder, extension in `ShutterCountService.EXTENSIONS`, any case) and then the same handler, `ShutterCountPane.check(Path)`, so a photo gives the same result either way. While a supported photo is over the area it turns the accent colour and says **Release to import the photo** (松开即可导入照片), at the same size. Unsupported files, folders and several files at once tint it in the danger colour with the reason; a refused drop leaves the previous result on screen and shows the reason in the area until a photo is accepted. While a photo is being read, drops are not accepted (the button is disabled as before), so one drop starts exactly one check. Drops are accepted as copies only, so File Explorer never moves or deletes the original.

**Flow.** File (chooser or drag and drop through the drop zone; `.jpg .jpeg .arw .nef .dng`) → `ShutterCountService.check`: name/extension, existence, regular file, readability, size (1 B – 2 GB), signature (JPEG `FF D8 FF`; ARW/NEF/DNG TIFF `II*\0`/`MM\0*`) → `ExifToolAdapter.read` (`-json -a -G5 -n -api SavePath=1 -charset filename=utf8` and only the tags it needs: camera identity, dates, file type, counter and shutter-type fields; no GPS, serial or owner) → `ShutterCountRules` → `CameraModelCatalog.detect` and the comparison with a model given in the CLI → `ShutterCountResult` → `ShutterCountPresentation` (GUI and CLI wording). The GUI runs the check on the window's worker queue. A new check clears the previous result first, so an earlier photo's count is never shown for another file.

**ExifTool process.** The process is created per profile context in `ApplicationBootstrap`, but nothing starts with the application.
- **Start.** The first check locates the bundle (`ExifToolRuntime`) and starts `exiftool_files\perl.exe exiftool.pl -stay_open True -@ -`, working directory `exiftool_files`, with `PERL*` variables removed. Each request writes argument lines followed by `-echo4 {readyN}` and `-executeN`. stdout is read up to `{readyN}` and stderr up to the echoed marker, each on its own daemon thread, so no pipe can fill up.
- **Requests.** Requests are serialized by a lock. Arguments containing line breaks or protocol options are refused. The timeout is 30 s (`PHOTOPILOT_EXIFTOOL_TIMEOUT_SECONDS`, 5–300).
- **Failures.** On a timeout or crash the process tree is stopped and reported; the next request starts a fresh process. A pipe found broken when sending is restarted and retried once.
- **Shutdown.** `close()` (user switch or application exit, via `ProfileManager` → `ApplicationBootstrap.close`) sends `-stay_open False` and stops the process if it has not exited after 3 s.
- **Locating the bundle.** With classes on disk (`javafx:run`, tests), the bundle is used in place after checking every manifest entry's size. From a JAR it is extracted once to `%LOCALAPPDATA%\PhotoPilot\exiftool\13.62-<manifest hash>`, every file is SHA-256-verified, and the folder is published with an atomic move. Other systems report the feature as unavailable (the bundle is the Windows build).

**Counter rules** (from the bundled `Sony.pm`/`Nikon.pm` and the TagNames notes). Fields are never interchangeable:
- **Sony.** The model selects the MakerNotes table:
  - Tag9050d `ShutterCount`: "total number of mechanical shutter actuations" (ILCE-6700/7CM2/7CR, ILME-FX2; ILCE-1M2/7M5/7RM6 only in mechanical-shutter photos).
  - Tag9050a/b/c `ShutterCount`: "total number of image exposures made by the camera". Tag9050a may wrap at 65536 on some models. For b/c, ExifTool's source notes that the count does not increase during silent shooting (ILCE-7RM2).
  - MoreInfo0201 and FocusInfo `ShutterCount`: older DSLR/SLT/NEX models.
  - `ShutterCount2` and `ShutterCount3` have no documented meaning and only cross-check the selected field (shown as matching or differing).
- **Nikon.** MakerNotes 0x00a7 `ShutterCount`: "includes both mechanical and electronic shutter activations"; 4294965247 means n/a. A ShotInfo copy cross-checks it. `MechanicalShutterCount` is shown as a separate counter.
- **All brands.**
  - `ImageCount` is never a shutter count.
  - Zero, non-numeric, out-of-range and n/a values are reported as an invalid counter.
  - Several primary tables with different values are reported as a conflict.
  - No MakerNotes (edited or exported JPEG, converted DNG), no camera data, other brands, damaged files (ExifTool's format warnings) and missing counters each have their own message. A count is never substituted or estimated.

**Model catalog** (`CameraModelCatalog`, resource `catalog/exiftool-camera-models.json` generated by `tools/GenerateCameraCatalog.java` from <https://exiftool.org/models.html>, retrieved 2026-11-09 with ExifTool 13.62).
- **Contents.** Of 796 Sony and 307 Nikon entries, 360 Sony and 300 Nikon cameras are kept. Phones, camcorders, PDAs, printers, the PSP, NEX-VG camcorders, Coolscan scanners and software are excluded.
- **Grouping.** Models are grouped into families and series: Sony α1/α9/α7/α7R/α7S/α7C/α6000/α5000·3000, NEX, QX, A-mount SLT·ILCA and DSLR, Cinema Line, ZV, RX, other Cyber-shot, Mavica; Nikon Z FX/DX, D single/two/three/four-digit, Df, Nikon 1, COOLPIX, KeyMission.
- **Names.** Product names are derived from the IDs (ILCE-7RM5 → α7R V, Z 6_2 → Z 6II). Search and exact lookup accept IDs, product names and variants (A7 IV, Alpha 7 IV, A7M4, ILCE-7M4, NIKON Z 6_2, Z6II).
- **Support levels.** These are kept apart from being listed: *verified* means checked with a real photo against an independent tool (ILCE-7RM5 only, `catalog/shutter-count-verified.json`, which stores no counts or photo data); *documented* means ExifTool's model conditions name a counter (75 Sony, 48 Nikon); *unverified* applies to 252 Nikon models; *not available* means ExifTool defines no counter (284 Sony, mostly Cyber-shot compacts).
- **Model check.** CLI only (`shutter check … brand= model=`): the detected model is compared with the given one (match, model mismatch, brand mismatch, or a model that cannot be matched). The GUI has no selection since 2026-11-10.

**Files.**
- Model: `CameraModel`, `CameraMetadata`, `CounterKind`, `ShutterCountResult`.
- Integration: `CameraMetadataReader`, `ExifToolAdapter`, `ExifToolProcess`, `ExifToolRuntime`, `ExifToolException`.
- Service: `CameraModelCatalog`, `ShutterCountRules`, `ShutterCountService`.
- Presentation and UI: `ShutterCountPresentation`, `ui/cli/ShutterCommands`, `ui/gui/ShutterCountPane` (connected by `GuiProfileController` through `PhotoPilotWorkspace/FeaturePane.connectShutterCount`), `ui/gui/SupportedModelsDialog`, and the shared `ui/gui/FileDropZone` for its import area.
- Resources: `catalog/*.json`; 201 `shutter.*` and 8 shared `fileDrop.*` keys per bundle; `.shutter-content` CSS with existing tokens (and the card's text field added to the shared input rule); the dashed import look is the shared `.file-drop-zone` rule with its drag states.
- Bundle and build: `third-party/exiftool/` (official package, `MANIFEST.sha256`, `NOTICE.md`, `.gitattributes` keeping the bytes); a `pom.xml` resource entry; `tools/ExifToolManifest.java`.
- The inventory card, its table, search and buttons are unchanged.

**Verification (2026-11-09).**
- The user-supplied Sony ARW (outside the repository) gives ILCE-7RM5 and `MakerNotes › Tag9050c › ShutterCount`, equal to the independent tool's reference, through the service, CLI, GUI card and packaged JAR. The photo was unchanged.
- Full suite: 485 run, with 1 known baseline-identical `AppearanceGuiTest` failure. JAR and `GUI_SMOKE_OK`.
- Packaged JAR run from a Unicode install path with a fresh `%LOCALAPPDATA%`: extracted, verified, reused on the next start, and no process left behind.
- 2026-11-10, drop zone and selection order:
  - Full suite: 486 run, same single known failure; `GUI_SMOKE_OK`.
  - `FileDropZoneTest` and `ShutterCountGuiTest` cover drop and chooser equivalence (also with the real ARW), refusals, the busy state and the control order.
  - The import area's default look is pixel-identical to before.
  - A real File Explorer drag is a manual check.
- 2026-11-10, card simplified (no selection, supported-models dialog, taller import area):
  - Focused shutter, drop-zone and translation classes pass, with the real sample.
  - Full suite: 486 run, the known `AppearanceGuiTest` failure plus a `DropdownGuiTest` real-screen focus check that passed twice when rerun on its own.
  - `GUI_SMOKE_OK`.
- Details: [project memory](../../docs/PROJECT_MEMORY.md#shutter-count-checker-with-bundled-exiftool-2026-11-09).

**Limits.**
- Only Windows is bundled.
- Nikon rules are tested with controlled metadata only: no Nikon photograph was available, so no Nikon accuracy is claimed.
- Sony support beyond the ILCE-7RM5 rests on ExifTool's documentation, not on PhotoPilot samples.
- The meaning of a counter follows ExifTool's notes, which are sometimes hedged (Tag9050b/c silent-shooting comment).

## Persistence contract

- Shared location: `<root>/profiles/<uuid>/inventory.json`, where root defaults to `${user.home}/.photopilot/data` or PHOTOPILOT_DATA_DIR. Normal GUI/CLI use the same persisted default UUID. [Phase 3](phase3-local-profiles.md) documents guarded contexts and non-destructive Phase 2 migration. Business files are created on save; startup creates profile metadata and may copy known legacy data.
- UTF-8 JSON schema version 1: root `schemaVersion` and `items`. Each entry contains `type`, `id` (UUID), `brand`, `model`, `quantity`, `weightGrams` (number/null), `verifiedFields` (string array), `sources` (field-to-URI-string map), `verifiedAt` (ISO instant/null), and `specs` (explicit field-to-string values). Type tags are CAMERA, LENS, FILTER, BATTERY, STORAGEMEDIA. Optional absent specs retain unknown values; absent booleans and false are different.
- EquipmentData explicitly reconstructs allowlisted domain types and validates them. Deserialization does not use reflection to bypass domain constructors. Gson 2.11.0 is the only added direct dependency; [Gson's official guide](https://google.github.io/gson/UserGuide.html) documents the JSON library. The record representation avoids reflective access to JDK Optional/Instant classes.
- Missing inventory is a real empty result. Empty/truncated/invalid UTF-8, malformed JSON, duplicate JSON keys, duplicate equipment IDs, unknown fields/types/schema versions, invalid numeric types/values, excessive nesting and files larger than 10 MiB fail without resetting the original file.
- Every service mutation performs read/validate/write under an OS sidecar file lock (`inventory.json.lock`). Concurrent writers receive a retry error. Another interface's unrelated entries are retained. A SHA-256 revision prevents stale full-store saves; GUI/CLI edits additionally compare the original item before replacing it.
- Writes flush a same-directory temporary file and use ATOMIC_MOVE with replacement. There is no non-atomic fallback. Unsupported atomic replacement and simulated write failures return errors and retain the original. Temporary files are cleaned after normal success/failure. A process/power crash may leave an unreferenced temporary file. Storage is local and unencrypted; no cloud sync or backup manager is implemented.
- Reads and reloads access disk each time. GUI Search / Refresh or revisiting F05 observes external changes; it does not poll continuously. Failed writes do not change the displayed inventory into a false successful result.

## Architecture, patterns and Stage 1 decisions

F05 -> UC05 (plus future UC04 result) -> SD02 -> PhotoPilotFacade.inventory()/saveGear -> InventoryService -> GearInventory/Equipment -> InventoryRepository/JsonInventoryRepository. The existing Facade is exercised by both interfaces. Repository port/implementation separation provides the persistence boundary. Other Strategy, Adapter, Command and State structures remain intact; this work does not claim five fully operational business patterns.

| Original design | Implemented change | Reason | Impact |
| --- | --- | --- | --- |
| InventoryService.save(item) chooses add or update | Retain save; add explicit add/update and duplicate-acceptance overloads; edits can carry their original item | Distinguish duplicate-ID additions, missing/stale edits and explicit review | Additive UC05/SD02 APIs; existing Facade route preserved |
| GearInventory.search(query) | Retain search; add read-only items/find/revision access and service type filtering | Render typed inventory and protect persistence transactions | F05 and future F06/F07 consumers can read real values; no F06 behavior added |
| Equipment types and immutable GearSnapshot | Implement validation/snapshot, final equipment fields, typed getters, EquipmentType definitions and EquipmentData mapping | Make existing domain classes usable without parallel equipment models or reflective domain loading | Original fields/constructors retained; missing data and evidence survive persistence |
| JSON repository atomic write | Add versioned schema, strict decoding, bounded input, transact port and revisions | Serialize multi-process writes and prevent corrupt-file/stale-state data loss | Existing load/save preserved; requires file locking and atomic-move filesystem support |
| Check sessions.referencingGear(id) and preserve snapshots | Empty/missing session store proves no references; nonempty/unreadable store blocks deletion; service honors returned references and acceptance | (Historical) F01's saved-session schema did not exist yet; inventing a scanner would risk history | Narrow F05 prerequisite guard only. Real saved-session scanning was added with F01 (`JsonSessionRepository.referencingGear`, `SessionTest.actualGearReferencesWarnAndConfirmedRemovalPreservesHistory`). No session files are mutated |
| Existing GUI feature placeholder | InventoryPane connects inside FeaturePane with shared labels, button/card CSS and Task executor; InventoryCommands handles CLI syntax | Provide real workflows in the established shell | Sidebar, navigation groups, window behavior and unrelated pages preserved |

These are implementation refinements/additive APIs rather than a replacement architecture. Stage1 report and UXF/PNG diagrams were not modified. The existing structural audit still finds every original declaration. Confirmed F04 imports use the same deterministic save path (`InventoryService.confirm`). F06/F07 consume typed getters, immutable item lists, optional values, verification metadata and copied snapshots; their workflows live in their own features.

## Files

New production files under `Stage2/src/main/java/com/photopilot/`:

- `application/EquipmentForm.java`: shared GUI/CLI input mapping and verification invalidation.
- `model/EquipmentType.java`, `model/EquipmentData.java`: supported field definitions and explicit transport representation.
- `ui/cli/InventoryCommands.java`: inventory command parsing, usage and output.
- `ui/gui/InventoryPane.java`: inventory table, adaptive forms, inline confirmation, feedback and background work.

Modified production files in that same tree:

- `model/Equipment.java`, Camera, Lens, Filter, Battery, StorageMedia, GearInventory and GearSnapshot: existing domain implementation and read access.
- `repository/InventoryRepository.java`, JsonInventoryRepository and JsonSessionRepository: transaction port, actual persistence and conservative reference guard.
- `service/InventoryService.java`: shared inventory operations and protections.
- `common/Result.java`: success factory; `config/FeatureCatalog.java`: F05 availability.
- `ui/cli/PhotoPilotCLI.java`, `ui/gui/FeaturePane.java`, PhotoPilotGUI and PhotoPilotWorkspace: integration and navigation verification.

Other changes: `Stage2/pom.xml` (Gson), `Stage2/src/main/resources/com/photopilot/ui/gui/photopilot.css` (scoped F05 controls), `.gitignore` (inventory data), `Stage2/README.md`, `Stage2/docs/feature-mapping.md`, this file, `docs/FEATURE_STATUS.md` and `docs/PROJECT_MEMORY.md`.

New JUnit files: `Stage2/src/test/java/com/photopilot/inventory/InventoryTest.java`, `InventoryCliTest.java`, and `ui/gui/InventoryGuiTest.java`. Existing BootstrapTest and FeaturePresentationTest expectations now distinguish F05 from the ten remaining scaffolds. No AGENTS.md change was needed. Pre-existing edits, including sidebar-number removal, remain in the working tree and are excluded from the F05 commit.

## Executed verification

Use JDK 21 and the existing project Maven caches. Windows commands from Stage2:

```powershell
./mvnw.cmd -B -o clean test
./mvnw.cmd -B -o test
./mvnw.cmd -B -o javafx:run '-Djavafx.args=--smoke-test'
```

| Evidence | Actual result |
| --- | --- |
| Fresh pre-change baseline `test` | 6 tests, 0 failures/errors/skips; original scaffold confirmed |
| Clean rebuild after initial implementation | 97 production / 5 test source files compiled; 34 tests, 0 failures/errors/skips |
| Final full `test` after stricter JSON and stale-editor regressions | 36 tests, 0 failures/errors/skips; 4 BootstrapTest, 2 FeaturePresentationTest, 28 InventoryTest, 1 InventoryCliTest, 1 InventoryGuiTest |
| Domain/service/repository coverage | Five-category round trips, unknown-vs-false, metadata, CRUD, query/type filtering, IDs, duplicate review, invalid fields, reload, malformed JSON/UTF-8/schema, file paths, failed atomic replacement, stale full saves, lock contention, reference warnings and preserved snapshots |
| GUI acceptance | Real JavaFX controls and worker callbacks exercise validation, add/edit/search/filter, duplicate confirmation, cancel/removal, an independent CLI application write and GUI refresh, plus navigation after requesting 880 x 600 |
| CLI acceptance | Quoted values, partial edits, optional clearing, add/list/filter/edit/remove, restart, unknown/invalid options and preserved shared service results |
| Graphical smoke mode | GUI_SMOKE_OK; all eleven navigation entries, statuses, selection and minimum-size navigation; clean application shutdown |
| Native Windows inspection | Compared original shell with F05 empty list, camera form at normal and narrow width, single-column wrapping, reachable save/cancel, focus styling and inline error card; no horizontal clipping observed |
| Structural audit | 87 UML types / 267 fields / 184 operations / 83 relationships recorded; 20 original source hashes unchanged. Old script's blanket NOT IMPLEMENTED footer is not a current F05 business-status claim |

Initial restricted Maven dependency access failed (`getsockopt`); approved network access resolved Gson. A restricted compiler access attempt failed on the JavaFX cache. The first clean attempt encountered a DLL held by the earlier inspection process; after terminating that task-owned process, the clean rebuild succeeded. The final JUnit JavaFX run emits the existing unnamed-module configuration warning and passes. These intermediate failures are not reported as successful runs.

## Limits

- Saved-session reference resolution now uses F01's real scan; the removal warning and accepted removal are tested against real saved sessions (see above). An unreadable session store still blocks deletion.
- F05 itself makes no provider calls. No production user-data migration or final course demonstration video was performed. *(Historical, 2026-10-26: F04 recognition and F06 analysis were not yet implemented; both are now.)*
- Verification covers Windows/JDK 21, current display scale and the local filesystem. macOS/Linux, other display scales, screen-reader speech, network filesystems and power-loss durability are not established.
- Separate interfaces see external changes on reload, not automatic live synchronization. Brand and model are required by this first manual-entry form; unknown optional specifications remain explicit.
