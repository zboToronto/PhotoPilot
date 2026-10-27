# F05 gear inventory implementation and verification

Verified on 2026-10-26, America/Toronto, Windows with JDK 21. F05 is implemented; F04 recognition and F06 capability analysis remain scaffolded. This is not a claim that all Stage 2 requirements are complete.

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

## Persistence contract

- Shared location: `PHOTOPILOT_DATA_DIR/inventory.json`, default `data/inventory.json` relative to the process working directory. Both interfaces must run from the same working directory or use the same absolute configured data root. No machine-specific source path was added. Default inventory directories, local launchers, caches and build outputs are excluded from this commit.
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
| Check sessions.referencingGear(id) and preserve snapshots | Empty/missing session store proves no references; nonempty/unreadable store blocks deletion; service honors returned references and acceptance | F01's saved-session schema is not implemented; inventing a scanner would risk history | Narrow F05 prerequisite guard only. Real saved-session scanning awaits F01. No session files are mutated |
| Existing GUI feature placeholder | InventoryPane connects inside FeaturePane with shared labels, button/card CSS and Task executor; InventoryCommands handles CLI syntax | Provide real workflows in the established shell | Sidebar, navigation groups, window behavior and unrelated pages preserved |

These are implementation refinements/additive APIs rather than a replacement architecture. Stage1 report and UXF/PNG diagrams were not modified. The existing structural audit still finds every original declaration. F04 recognition and confirmation stay unavailable; future confirmed imports can use the shared deterministic save path. F06/F07 can consume typed getters, immutable item lists, optional values, verification metadata and copied snapshots; their business workflows were not implemented here.

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

- Real nonempty saved-session reference resolution requires F01; deletion is blocked safely until then. Its warning/acceptance contract was tested with a controlled repository, not with a completed F01 workflow.
- F04 recognition, F06 capability analysis and the remaining business features were not implemented. No provider calls, external verification, production user-data migration or final course demonstration video was performed.
- Verification covers Windows/JDK 21, current display scale and the local filesystem. macOS/Linux, other display scales, screen-reader speech, network filesystems and power-loss durability are not established.
- Separate interfaces see external changes on reload, not automatic live synchronization. Brand and model are required by this first manual-entry form; unknown optional specifications remain explicit.
