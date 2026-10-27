# PhotoPilot — EECS 3311 Stage 2

PhotoPilot is an EECS3311 photography workflow agent planned around sessions, equipment, deterministic tools, AI planning and metadata-based review. F05 Gear Inventory is implemented and verified through GUI and CLI; the other ten business features remain **SCAFFOLDED / NOT IMPLEMENTED**. See [F05 usage, persistence, decisions and verification](docs/f05-inventory.md). It is not the completed Stage 2 submission.

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

The JavaFX GUI uses a minimalist light workspace with grouped navigation for all 11 features, a selected-feature description, accurate availability status, and a **Check status** action for scaffolded features. F05 opens the inventory list and forms; other workflows remain unavailable and disabled. F10 appears under Workspace as Shoot Planning; F04 remains AI Gear Import (recognition/import, not gear reports). Use Up/Down or Home/End within navigation, Tab to move focus, and Space to activate buttons. The sidebar and content scroll independently in smaller windows (minimum 880 × 600). Standard window controls close the GUI and release application resources. F05 uses Gson 2.11.0 for JSON; the existing JavaFX framework and theme are retained. See [GUI implementation and verification](docs/gui-redesign.md).

The CLI accepts `help`, `status`, `F01` through `F11`, `exit`/`quit`, and EOF. `F05` lists saved gear, and `gear help` documents add/edit/remove/search/filter commands. Other feature commands report unavailable status. Unknown commands return a helpful message.

Noninteractive CLI example:

```powershell
.\mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=help status F01 F11 exit'
```

GUI startup smoke mode (opens and closes a real window):

```powershell
.\mvnw.cmd javafx:run '-Djavafx.args=--smoke-test'
```

A successful run prints `GUI_SMOKE_OK` after showing the stage and navigating all 11 entries, checking status feedback, disabled scaffold workflows, and retained selection, including after requesting the minimum window size. It then closes. This is a graphical control check, not proof of implemented business features or comprehensive visual testing.

The original six JUnit tests check bootstrap wiring identity, honest not-implemented results, CLI handling, configurable strategy and duplicate tool registration, plus complete GUI feature mapping and fail-closed status interpretation. They are not Stage 3 deterministic/KUMA coverage. `python tools/verify_sources.py` optionally checks all 87 UML types, 267 fields and 184 declared operations, source hashes and basic unwanted-output patterns; it requires Python 3 and the original local documents under `../../Project/` in the EECS3311 workspace. Paths recorded in the source-hash manifest resolve against EECS3311, outside the repository. Maven builds do not require Python or those documents. Do not rerun `scaffold_from_uml.py` over this project: it is one-time generation evidence and intentionally refuses to overwrite files.

## F05 gear inventory

Open **Equipment > Gear Inventory** in the GUI, or enter `gear help` in the CLI. Both use `PhotoPilotFacade.inventory()` and the same service. Manual entries require no Gemini connection. For example, in the interactive CLI:

```text
gear add camera brand="Example Brand" model="Camera One" mount="Example Mount" quantity=1
gear list query="camera" type=camera
gear edit <UUID printed by add> quantity=2
gear remove <UUID printed by add>
```

Data is stored in `data/inventory.json` relative to the application working directory, or under `PHOTOPILOT_DATA_DIR`. Start GUI and CLI from the same directory or set the same absolute data root. Use Search / Refresh or reopen F05 to see changes made by the other interface. Duplicate model entries need explicit review; duplicate IDs are rejected by add. The desktop editor also rejects stale edits/removals. Corrupt files are preserved and reported; they are never silently replaced with an empty inventory.

The full JUnit suite now contains **36 passing tests** (Windows, JDK 21, 2026-10-26), including real JavaFX controls. A graphical desktop is required for the full suite. Headless service/CLI runs may use `-Dtest=BootstrapTest,FeaturePresentationTest,InventoryTest,InventoryCliTest`; this excludes GUI acceptance and must not be reported as full verification. See [F05 evidence and known limits](docs/f05-inventory.md).

## Planned features

| ID | Stage 1 feature | Type | Status |
| --- | --- | --- | --- |
| F01 | Photography Session and History Management | Deterministic | SCAFFOLDED / NOT IMPLEMENTED |
| F02 | AI Photography Inspiration and Activity Recommendation | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F03 | Natural-Language Photography Requirement Parsing | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F04 | AI Gear Recognition and Automatic Import | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F05 | Photography Gear Inventory Management | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED |
| F06 | AI Photography Equipment Capability Requirement Analysis | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F07 | Owned Gear Matching and Compatibility Validation | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F08 | Photography Technical Calculation Tool Suite | Deterministic | SCAFFOLDED / NOT IMPLEMENTED |
| F09 | Location, Time, and Environment-Aware Photography Planning | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F10 | AI Shoot Plan Generation and Interactive Modification | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |
| F11 | AI Post-Shoot Analysis and Review | Hybrid | SCAFFOLDED / NOT IMPLEMENTED |

## Architecture and patterns

GUI and CLI both construct the same application composition and access `PhotoPilotFacade`. Session, inventory, discovery, planning, review and calculation services retain their approved dependencies. The agent retains its LLM port, history retriever and tool registry. Repository ports use portable `Path` fields; JsonInventoryRepository implements real atomic inventory persistence. Session persistence remains scaffolded; bootstrap construction creates no user data.

- **Facade:** PhotoPilotFacade delegates to six subsystem services.
- **Strategy:** GearMatchingService retains LoadoutRankingStrategy; PortabilityRanking and CoverageRanking remain distinct from CompatibilityValidator.
- **Adapter:** Gemini, EXIF, environmental and equipment catalog adapters implement the four approved integration ports.
- **Command:** PlanEditor, PlanCommand and its three concrete commands retain ShootPlan and PlanValidator collaboration; no undo/redo added.
- **State:** PhotographySession retains SessionState and four lifecycle state types with the three approved SessionEvent values.

F05 exercises the Facade and repository port/implementation separation. Strategy, external Adapter, Command and State workflows remain scaffolded; this does not establish five completed patterns. Business operations outside F05 return `Result.failure("NOT IMPLEMENTED: ...")`, or throw `UnsupportedOperationException` when the approved signature cannot return Result. F05 returns real saved records and real empty searches; no fake AI responses or measurements are returned.

## Configuration and future integrations

`.env.example` is a safe reference template. **There is no `.env` loader.** Copying it to `.env` alone does not configure the application. Do not enter a real API key for this build: it is neither required nor read. All integration methods are disabled placeholders regardless of settings.

Current environment-variable configuration:

| Variable | Effect/default |
| --- | --- |
| `PHOTOPILOT_DATA_DIR` | Repository path root, default `data`; F05 writes `inventory.json` here |
| `PHOTOPILOT_RANKING` | `portability` (default) or `coverage` selects the approved strategy object; ranking remains unimplemented |
| `GEMINI_MODEL` | Stored model name, default `UNCONFIGURED`; no API call |
| `GEMINI_ENDPOINT` | Stored URI, default reserved `https://unconfigured.invalid/gemini`; no API call |
| `GEMINI_API_KEY` | Placeholder for future implementation; intentionally not read |

For example, `$env:PHOTOPILOT_RANKING='coverage'` (PowerShell) or `export PHOTOPILOT_RANKING=coverage` (POSIX shell) selects wiring before startup. Future real integration must implement private configuration/key loading, provider adapters, validation, timeouts and honest failures, and update this README with the actual supported local-key workflow. `.gitignore` already protects `.env`, `.env.*` except `.env.example`, and `config.local.json`.

## Known limits and next implementation boundaries

Outside F05, session persistence, recognition, EXIF extraction/statistics, calculations, location access, compatibility checks, AI reasoning, plan changes or session transitions work yet. F05 behavior has dedicated tests; other domain methods and ownership invariants must not be treated as validated because their classes compile. Until F01 defines saved-session storage, nonempty session directories block gear deletion; historical files are preserved. No actual Gemini/provider connection or credential handling was tested. No external runtime API was invoked; network access during preparation was limited to tooling documentation and dependency/tool downloads.

The approved design itself lacks F01 location/notes mutation routes, a Facade route for F02 select/save/dismiss, and F07 selected-loadout persistence. These remain explicit gaps, not silently redesigned operations. Provider/library selection, generic list schema validation and concrete PlanCommand decoding are also deferred. Source-verification.md records precise evidence and minimal future proposals.

This build is preparation for Stage 2 implementation. It does not meet the final requirements for working features, operational design patterns, LLM behavior, implementation report, demonstration videos, weekly Git evidence or Stage 3 testing. The original scaffolding verification predates Git integration. Repository uploads do not imply completed features or final course deliverables.
