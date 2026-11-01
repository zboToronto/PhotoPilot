# PhotoPilot — EECS 3311 Stage 2

PhotoPilot is an EECS3311 photography workflow agent planned around sessions, equipment, deterministic tools, AI planning and metadata-based review. F01 Sessions & History, F05 Gear Inventory and F08 Technical Calculators are implemented and verified through GUI and CLI; the other eight business features remain **SCAFFOLDED / NOT IMPLEMENTED**. See [F05 usage, persistence, decisions and verification](docs/f05-inventory.md). It is not the completed Stage 2 submission.

The selected local design is `Project/Stage1/Stage1.docx` revision 46 and its matching `Stage1PPP.pdf` export, with the seven supplied UMLet diagrams. See [verified sources and unresolved gaps](docs/source-verification.md), [feature mapping](docs/feature-mapping.md), [all 87 UML types and 83 relationships](docs/class-inventory.md), [additional decisions](docs/additional-decisions.md), and [actual verification results](docs/verification.md).

## Prerequisites and setup

For a new checkout:

```sh
git clone --branch main https://github.com/zboToronto/PhotoPilot.git
cd PhotoPilot/Stage2
```

For the existing local checkout, use `C:/Users/zbo/Desktop/EECS3311/ProjectGit/Stage2` without cloning again. The Git repository root is `C:/Users/zbo/Desktop/EECS3311/ProjectGit`. The repository's Stage1 directory retains the original report and UML artifacts; `Project/` paths refer to local design-preparation evidence under the parent `EECS3311` directory (`../../Project/` from Stage2) and are not required for Maven builds.

- JDK **21 LTS**, with `JAVA_HOME` pointing to the JDK directory. JavaFX dependencies are resolved by Maven; no separate JavaFX SDK is needed.
- Internet access to Maven Central for the first build. Maven Wrapper 3.3.2 downloads pinned Maven 3.9.9. A system Maven 3.9.x installation is optional.
- A graphical desktop is required for the GUI. The CLI does not require a desktop or initialize JavaFX.
- Run every command below **from this `Stage2` directory**. No API credentials or user-data files are required for startup.

Set JAVA_HOME for the current shell if needed, using your own JDK path. Nothing in the source contains a machine-specific JDK path. The optional `.tools/jdk21/` copy is local verification tooling, not an application dependency to distribute.

Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\your\jdk-21'
.\mvnw.cmd -version
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd javafx:run
.\mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI'
```

macOS / Linux, using a JDK built for the machine's architecture:

```sh
export JAVA_HOME=/path/to/your/jdk-21
sh ./mvnw -version
sh ./mvnw clean compile
sh ./mvnw test
sh ./mvnw javafx:run
sh ./mvnw exec:java -Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI
```

`sh ./mvnw` works without executable-bit setup; `chmod +x mvnw` allows `./mvnw`. With a separately installed Maven, equivalent commands are `mvn clean compile`, `mvn test`, `mvn javafx:run`, and `mvn exec:java -Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI`. The wrapper commands are the commands verified here; do not infer a tested global Maven installation.

The source tree is shared across platforms. OpenJFX resolves platform-specific native dependencies via its Maven metadata; there is no hardcoded Windows classifier. macOS Intel/Apple Silicon and Linux are not runtime-tested in this Windows session.

Wrapper distribution cache defaults to `.mvn/user-home`, and dependency cache to `.mvn/repository`. An explicit `MAVEN_USER_HOME` overrides the distribution-cache location. These caches, `.tools`, `target`, and future `data` are ignored. No global tools or repository settings need to be installed or changed.

## Try the bootstrap

The JavaFX GUI opens Splash → Select User → Home Dashboard before any feature. The chooser supports Add User, Rename User, confirmed Delete User and persistent PNG/JPEG avatars. Home launches the real Equipment, Sessions & History and Calculator modules, with clearly marked future Planning/AI entry points. Switch User / User Settings returns to the chooser; Home remains available in the sidebar. See [the user workspace implementation and verification](docs/user-workspace.md). The GUI uses a translucent frosted workspace with grouped navigation for all 11 features, a selected-feature description, accurate availability status, and a **Check status** action for scaffolded features. F01 opens session history and its editor; F05 opens the inventory list and forms; F08 opens the shared calculator suite; other workflows remain unavailable and disabled. F10 appears under Workspace as Shoot Planning; F04 remains AI Gear Import (recognition/import, not gear reports). Use Up/Down or Home/End within navigation, Tab to move focus, and Space to activate buttons. The sidebar and content scroll independently in smaller windows (minimum 880 × 600). Standard window controls close the GUI and release application resources. F05 uses Gson 2.11.0 for JSON; the existing JavaFX framework and theme are retained. See [GUI implementation and verification](docs/gui-redesign.md).

The CLI accepts `help`, `status`, `F01` through `F11`, `exit`/`quit`, and EOF. `F01` lists saved sessions and `session help` documents session commands. `F05` lists saved gear, and `gear help` documents add/edit/remove/search/filter commands. `F08` lists calculators; `calc help` documents their parameters. Other feature commands report unavailable status. Unknown commands return a helpful message.

Noninteractive CLI example:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=help status F01 F11 exit'
```

GUI startup smoke mode (opens and closes a real window):

```powershell
.\mvnw.cmd javafx:run '-Djavafx.args=--smoke-test'
```

A successful run prints `GUI_SMOKE_OK` after the splash, user chooser, real backend selection, Home landing and navigation through all 11 entries, checking status feedback, disabled scaffold workflows, and retained selection, including after requesting the minimum window size. It then closes. This is a graphical control check, not proof of implemented business features or comprehensive visual testing.

The original six JUnit tests check bootstrap wiring identity, honest not-implemented results, CLI handling, configurable strategy and duplicate tool registration, plus complete GUI feature mapping and fail-closed status interpretation. They are not Stage 3 deterministic/KUMA coverage. `python tools/verify_sources.py` optionally checks all 87 UML types, 267 fields and 184 declared operations, source hashes and basic unwanted-output patterns; it requires Python 3 and the original local documents under `../../Project/` in the EECS3311 workspace. Paths recorded in the source-hash manifest resolve against EECS3311, outside the repository. Maven builds do not require Python or those documents. Do not rerun `scaffold_from_uml.py` over this project: it is one-time generation evidence and intentionally refuses to overwrite files.

## Language settings (English / 简体中文)

English (`en`) is the fresh-user default and fallback. Open **Settings / 设置** in the GUI and select **English** or **简体中文**; labels, open settings windows, validation and displayed calculation results update immediately while preserving unsaved inputs and selection. The preference is saved at `${user.home}/.photopilot/preferences.json`, shared with the CLI. Set the actual environment variable `PHOTOPILOT_CONFIG_DIR` to override that directory. If JSON is absent, existing settings.properties is read without modification; an explicit save writes JSON and retains the original properties file. Save failures retain the session language and show retry feedback.

In the CLI, `lang` shows the current language; `lang zh-CN` / `lang en` saves a preference. `--lang zh-CN` (or `--lang=en`) overrides only the current invocation. Existing command identifiers/options and structured tool data stay unchanged. UTF-8 terminal input/output is used.

```powershell
./mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=--lang zh-CN help "calc nd shutter=1/125 ndStops=10" exit'
./mvnw.cmd test '-Dtest=LocalizationTest,LocalizedCliTest,TranslationResourcesTest,AgentLanguageTest,LocalizationGuiTest'
```

The GUI tests need a desktop. Use the same list without `LocalizationGuiTest` for a headless localization check; that does not verify GUI acceptance. The full suite has **165 passing tests** (2026-10-31); GUI smoke and JAR packaging also passed. [Final storage integration evidence](docs/data-storage.md) records current verification; [F01 evidence](docs/f01-sessions.md) records its earlier 95-test snapshot; localization evidence below describes its earlier 71-test snapshot. See [localization architecture, resource coverage, extension instructions and verification limits](docs/localization.md) and [updated implementation UML](docs/localization-architecture.md).

Translations are UTF-8 `src/main/resources/i18n/messages{,_en,_zh_CN}.properties`. Stable semantic keys use MessageFormat parameters. Missing Chinese entries fall back to English; unknown keys show `[!key!]` with deduplicated diagnostics. Test checks detect missing/duplicate keys, invalid patterns and placeholder mismatches.

The selected response language is wired into the existing LLM port as a request-scoped default, with explicit user language requests taking precedence. **Actual AI responses remain unavailable:** `PhotoPilotAgent.run` and `GeminiAdapter.complete` are still scaffolds. Tests establish policy propagation/context preservation through a model test double, not live AI behavior. Localization is an additional usability capability, not an additional substantial business feature.

## F01 sessions and history

Open **Workspace > Sessions & History** or use `session help` in the CLI. Create with title/date and optional manual location or notes/goals; open a history row to edit. Search/filter by text, date and state. Delete requires confirmation; drafts survive navigation, language switching and save errors. Both interfaces persist to the same data root and protect against stale overwrites. Lifecycle events retain the Stage1 F10/F11 approval/review prerequisites.

```text
session create title="Evening portrait" date=2026-11-01 location="Park" notes="Soft light"
session list status=DRAFT
session open <generated-id>
session edit <generated-id> notes="Bring reflector"
session delete <generated-id> --confirm
```

[F01 schema, usage, classes, design diagrams, 95-test verification and limitations](docs/f01-sessions.md).

## F05 gear inventory

Open **Equipment > Gear Inventory** in the GUI, or enter `gear help` in the CLI. Both use `PhotoPilotFacade.inventory()` and the same service. Manual entries require no Gemini connection. For example, in the interactive CLI:

```text
gear add camera brand="Example Brand" model="Camera One" mount="Example Mount" quantity=1
gear list query="camera" type=camera
gear edit <UUID printed by add> quantity=2
gear remove <UUID printed by add>
```

Data is stored in `<root>/profiles/<selected-uuid>/inventory.json`; root defaults to `${user.home}/.photopilot/data`, overridden by `PHOTOPILOT_DATA_DIR`. GUI and CLI share the persisted default UUID regardless of launch directory and can select another profile for their own process. Use the same absolute root override for custom storage. Use Search / Refresh or reopen F05 to see changes made by the other interface. Duplicate model entries need explicit review; duplicate IDs are rejected by add. The desktop editor also rejects stale edits/removals. Corrupt files are preserved and reported; they are never silently replaced with an empty inventory.

The historical F05 verification contained **36 passing tests** (Windows, JDK 21, 2026-10-26), including real JavaFX controls; the current full-suite count is listed in Language settings above. A graphical desktop is required for the full suite. Headless service/CLI runs may use `-Dtest=BootstrapTest,FeaturePresentationTest,InventoryTest,InventoryCliTest`; this excludes GUI acceptance and must not be reported as full verification. See [F05 evidence and known limits](docs/f05-inventory.md).

## Planned features

| ID | Stage 1 feature | Type | Status |
| --- | --- | --- | --- |
| F01 | Photography Session and History Management | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED; F10/F11 prerequisites retained |
| F02 | AI Photography Inspiration and Activity Recommendation | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F03 | Natural-Language Photography Requirement Parsing | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F04 | AI Gear Recognition and Automatic Import | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F05 | Photography Gear Inventory Management | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED |
| F06 | AI Photography Equipment Capability Requirement Analysis | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F07 | Owned Gear Matching and Compatibility Validation | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F08 | Photography Technical Calculation Tool Suite | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED |
| F09 | Location, Time, and Environment-Aware Photography Planning | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F10 | AI Shoot Plan Generation and Interactive Modification | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F11 | AI Post-Shoot Analysis and Review | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |

## Architecture and patterns

GUI and CLI both construct the same application composition and access `PhotoPilotFacade`. Session, inventory, discovery, planning, review and calculation services retain their approved dependencies. The agent retains its LLM port, history retriever and tool registry. Repository ports use portable `Path` fields; JsonInventoryRepository implements real atomic inventory persistence. JsonSessionRepository implements strict atomic session persistence with optimistic revisions; normal bootstrap initializes a stable default profile and safely migrates known Phase 2 business files when necessary.

- **Facade:** PhotoPilotFacade delegates to six subsystem services.
- **Strategy:** GearMatchingService retains LoadoutRankingStrategy; PortabilityRanking and CoverageRanking remain distinct from CompatibilityValidator.
- **Adapter:** Gemini, EXIF, environmental and equipment catalog adapters implement the four approved integration ports.
- **Command:** PlanEditor, PlanCommand and its three concrete commands retain ShootPlan and PlanValidator collaboration; no undo/redo added.
- **State:** PhotographySession retains SessionState and four lifecycle state types with the three approved SessionEvent values.

F01 exercises Facade and State; F05/F08 retain the shared Facade and service architecture. Strategy, external Adapter and Command workflows remain scaffolded; this does not establish five completed patterns. Unimplemented workflows continue to return unavailable errors. Working features return real saved records, calculated results and real empty searches; no fake AI responses or measurements are returned.

## Configuration and future integrations

`.env.example` is a safe reference template. **There is no `.env` loader.** Copying it to `.env` alone does not configure the application. Do not enter a real API key for this build: it is neither required nor read. All integration methods are disabled placeholders regardless of settings.

Current environment-variable configuration:

| Variable | Effect/default |
| --- | --- |
| `PHOTOPILOT_DATA_DIR` | Repository path root, default `${user.home}/.photopilot/data`; registry `profiles.json` lives here; business files live under `profiles/<uuid>/` |
| `PHOTOPILOT_CONFIG_DIR` | Preference directory, default `${user.home}/.photopilot`; JSON preferences with read-only legacy properties compatibility |
| `PHOTOPILOT_RANKING` | `portability` (default) or `coverage` selects the approved strategy object; ranking remains unimplemented |
| `GEMINI_MODEL` | Stored model name, default `UNCONFIGURED`; no API call |
| `GEMINI_ENDPOINT` | Stored URI, default reserved `https://unconfigured.invalid/gemini`; no API call |
| `GEMINI_API_KEY` | Placeholder for future implementation; intentionally not read |

For example, `$env:PHOTOPILOT_RANKING='coverage'` (PowerShell) or `export PHOTOPILOT_RANKING=coverage` (POSIX shell) selects wiring before startup. Future real integration must implement private configuration/key loading, provider adapters, validation, timeouts and honest failures, and update this README with the actual supported local-key workflow. `.gitignore` already protects `.env`, `.env.*` except `.env.example`, and `config.local.json`.

## Known limits and next implementation boundaries

F01 session/history persistence, F05 inventory and F08 calculations are verified. Recognition, EXIF extraction/review production, device-location access, gear matching, AI reasoning and plan approval remain unavailable. New sessions remain Draft until F10 supplies an approved plan; Reviewed requires F11 to supply a review. F01 now scans actual historical gear references; corrupt or unrecognized session storage blocks removal. No actual Gemini/provider connection was tested.

The original F01 optional-location/notes gap is resolved by documented SessionService overloads. F02 selection/save/dismiss routing and F07 selected-loadout persistence remain explicit gaps. Provider/library selection, generic AI list schema validation and PlanCommand decoding remain deferred. See [F01 design changes](docs/f01-sessions.md) and the historical source-verification gap record.

This build is preparation for Stage 2 implementation. It does not meet the final requirements for working features, operational design patterns, LLM behavior, implementation report, demonstration videos, weekly Git evidence or Stage 3 testing. The original scaffolding verification predates Git integration. Repository uploads do not imply completed features or final course deliverables.

## F08 calculators

Select Technical Calculator in the existing GUI, or enter `calc help` in the CLI. Supported tools: exposure, nd, dof, hyperfocal, fov, storage, battery. Example: `calc nd shutter=1/125 ndStops=10` returns 8.192 s. Optional inventory prefill never changes saved gear; manual input needs no inventory or API key. All results show units and assumptions; optical and capacity tools are labeled estimates.

[F08 usage, formulas, source inventory, design decisions, tests and limitations](docs/f08-calculators.md). Final F08 suite: 53/53 tests and GUI_SMOKE_OK, Windows/JDK 21. A graphical desktop is needed for the real JavaFX acceptance tests. Close existing GUI instances before `clean` on Windows if the native cache DLL is locked.

## Local JSON storage and user management

GUI startup initializes local storage behind a short splash, then presents Select User. Continue validates the selected user through the shared ProfileManager and opens Home. The active-user bar opens Switch User / User Settings in the same window for selection, creation, rename, confirmed archive and avatar management. Names may be duplicated; secondary UUIDs distinguish rows. Display names never determine paths. All business services/history/tool dependencies use guarded repositories beneath `<root>/profiles/<uuid>/`. PNG/JPEG avatars are copied as normalized `avatar.png` in that UUID directory, with preview, restart persistence and restoration to initials. Display language remains global under PHOTOPILOT_CONFIG_DIR. See [user workspace behavior, architecture and verification](docs/user-workspace.md).
In the CLI, use `profile list`, `profile create "Display Name"`, `profile current`, `profile select <UUID>`, `profile rename <UUID> "New Name"`, `profile delete <UUID> --confirm`, and `profile help`. `--profile <UUID>` or `--profile=UUID` selects for one invocation; invalid selection aborts before business commands. Separate launches always start with the stable default unless explicitly selected. Each multiword command is one argument or one stdin line. Normal exits return 0; any command/configuration failure returns process status 2.

On first initialization, existing `<root>/inventory.json` and `<root>/sessions/sessions.json` are validated and copied into the default profile, preserving the originals. A durable journal resumes interrupted initialization; conflicts/corrupt data stop without overwriting or inventing records. Later starts do not re-import legacy files. Close older application versions before migration. Retain the full root, including profiles.json, when backing up.

Switching prepares a fresh service/agent graph and revokes the old context. GUI switching waits for queued work/completion callbacks and confirms discarding unsaved session/equipment/calculator inputs. Failed switching retains original views and drafts; successful switching rebuilds views and clears old inputs/results. CLI commands execute synchronously and replace the active Facade after success. Use the profile list's Refresh action and existing feature refresh actions for changes from another process. Deletion is confirmed archive: active/default profiles are protected, records remain on disk, and archived identities cannot be opened. It does not reclaim disk space; no restore command is available.

Phase 5 stabilized two integration boundaries: reselecting the current profile validates its storage without discarding valid views, and authoritative JSON preference saves no longer depend on the old properties writer lock. Legacy first-copy locking and non-destructive failure behavior remain intact. No schema or storage-layout changes were needed.

See the [storage reference: architecture, formats, selection, migration, errors and backup/recovery](docs/data-storage.md), [GUI/CLI usage](docs/phase4-profile-ui.md), and [Phase 3 registry and migration APIs](docs/phase3-local-profiles.md). Comprehensive JUnit/KUMA and prolonged stress validation remain Stage 3 work.

## Integrated JSON persistence (Phase 2)

Historical Phase 2 snapshot; business paths are now profile-scoped as described above. StorageConfiguration centralizes stable default and configured roots. Session/inventory repositories and JsonLocalePreferenceRepository share bounded UTF-8 IO, OS locks and atomic replacement. Existing schema v1 domain data remains compatible; unsupported atomic moves fail without overwriting the original. Canonical session revisions prevent false conflicts caused by another JVM changing object-key order.

Default business files are `${user.home}/.photopilot/data/inventory.json` and `data/sessions/sessions.json` beneath the same `.photopilot` directory. When launch-directory `data/` contains legacy records, GUI and CLI show an explicit notice; files are never relocated or deleted. To keep using old storage, set `PHOTOPILOT_DATA_DIR` to its absolute directory before launching both interfaces. Legacy data outside the launch directory must also be selected explicitly. Relative overrides remain supported but depend on the launch directory.

See [Phase 2 architecture, formats, safety, file inventory, 124-test verification and Phase 3 handoff](docs/phase2-json-persistence.md). Programmatic lifecycle and GUI/CLI selection are now implemented in Phases 3–4.

## Persistent profile storage preparation (Phase 1)

Historical Phase 1 snapshot (default paths superseded by Phases 2/3 above): existing JSON storage retained v1 schemas and then-current paths. Session restoration now calls domain constructors and preserves immutable evidence; linked plan/review identities and basic stored-value invariants are checked. Inventory save returns the committed revision. A fixed StorageScope is shared by all business repositories at bootstrap; at that stage normal GUI/CLI launches still used legacy storage. Profile creation, switching and migration are deferred. Language preferences remain application-wide. [Architecture audit, changed files, implementation diagram, 106-test verification and Phase 2 handoff](docs/phase1-persistence-readiness.md).
