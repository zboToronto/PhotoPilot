# PhotoPilot — EECS 3311 Stage 2

PhotoPilot is an EECS3311 photography workflow agent built around sessions, equipment, deterministic tools, AI planning and metadata-based review. Nine business features (F01, F02, F03, F04, F05, F07, F08, F09 and F10) have working GUI/CLI workflows and recorded verification. F06 provides inventory analysis and manual capability-profile confirmation, while its AI derivation remains unfinished; F11 review production remains scaffolded. See [current feature status and verification limits](../docs/FEATURE_STATUS.md). It is not the completed Stage 2 submission.

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

The JavaFX GUI opens Splash → Choose a workspace → Home Dashboard before any feature. The full-window chooser shows users as circular avatars with the selected user centred; Left/Right changes the user and Enter continues. User management lives on the in-app **User Settings** page: Add User, Refresh list, Rename, Change Avatar, Restore Default Avatar and confirmed Delete. The avatar menu at the top right opens User Settings, Application Settings or Switch Workspace, and only Switch Workspace returns to the chooser. Home launches the real Equipment, Sessions & History and Calculator modules, with clearly marked future Planning/AI entry points. Home remains available in the sidebar. See [the user workspace implementation and verification](docs/user-workspace.md). The GUI uses translucent surfaces over a Gaussian-blurred version of the startup wallpaper, with grouped navigation for all 11 features, a selected-feature description, accurate availability status, and a **Check status** action for scaffolded features. F01 opens session history and its editor; F05 opens the inventory list and forms; F08 opens the shared calculator suite; other workflows remain unavailable and disabled. F10 appears under Workspace as Shoot Planning; F04 remains AI Gear Import (recognition/import, not gear reports). Use Up/Down or Home/End within navigation, Tab to move focus, and Space to activate buttons. The sidebar and content scroll independently in smaller windows (minimum 880 × 600). Standard window controls close the GUI and release application resources. F05 uses Gson 2.11.0 for JSON; the existing JavaFX framework and theme are retained. See [GUI implementation and verification](docs/gui-redesign.md).

The CLI accepts `help`, `status`, `F01` through `F11`, `exit`/`quit`, and EOF. `F01` lists saved sessions and `session help` documents session commands. `F05` lists saved gear, and `gear help` documents add/edit/remove/search/filter commands. `F08` lists calculators; `calc help` documents their parameters. `ai-test` runs the diagnostic [Gemini connection smoke test](#gemini-connection-smoke-test-ai-test). Other feature commands report unavailable status. Unknown commands return a helpful message.

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

English (`en`) is the fresh-user default and fallback. Open **Application Settings / 应用设置** in the GUI and select **English** or **简体中文**; labels, validation and displayed calculation results update immediately while preserving unsaved inputs and selection. The preference is saved at `${user.home}/.photopilot/preferences.json`, shared with the CLI. Set the actual environment variable `PHOTOPILOT_CONFIG_DIR` to override that directory. If JSON is absent, existing settings.properties is read without modification; an explicit save writes JSON and retains the original properties file. Save failures retain the session language and show retry feedback.

In the CLI, `lang` shows the current language; `lang zh-CN` / `lang en` saves a preference. `--lang zh-CN` (or `--lang=en`) overrides only the current invocation. Existing command identifiers/options and structured tool data stay unchanged. UTF-8 terminal input/output is used.

```powershell
./mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=--lang zh-CN help "calc nd shutter=1/125 ndStops=10" exit'
./mvnw.cmd test '-Dtest=LocalizationTest,LocalizedCliTest,TranslationResourcesTest,AgentLanguageTest,LocalizationGuiTest'
```

The GUI tests need a desktop. Use the same list without `LocalizationGuiTest` for a headless localization check; that does not verify GUI acceptance. The full suite has **165 passing tests** (2026-10-31); GUI smoke and JAR packaging also passed. [Final storage integration evidence](docs/data-storage.md) records current verification; [F01 evidence](docs/f01-sessions.md) records its earlier 95-test snapshot; localization evidence below describes its earlier 71-test snapshot. See [localization architecture, resource coverage, extension instructions and verification limits](docs/localization.md) and [updated implementation UML](docs/localization-architecture.md).

Translations are UTF-8 `src/main/resources/i18n/messages{,_en,_zh_CN}.properties`. Stable semantic keys use MessageFormat parameters. Missing Chinese entries fall back to English; unknown keys show `[!key!]` with deduplicated diagnostics. Test checks detect missing/duplicate keys, invalid patterns and placeholder mismatches.

The selected response language is wired into the existing LLM port as a request-scoped default, with explicit user language requests taking precedence. `GeminiAdapter.complete` serializes this instruction as part of Gemini `systemInstruction`; F03 requirement parsing and the diagnostic CLI `ai-test` are its only callers, and F03 clarification questions follow the selected language. Tests establish policy propagation/context preservation through a model test double, not live AI behavior. Localization is an additional usability capability, not an additional substantial business feature.

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

## F02 Find Something to Shoot (UC02)

Open **Workspace > Inspiration** (or the Home card). No session is needed. Enter a place (a name or region is enough; **Find place coordinates** or typed coordinates enable distance, sun and weather checks; device location only after explicit consent), the date and the period you can shoot, an optional longest activity, the maximum **straight-line** travel distance, and optional interests, subjects to avoid, portability and other constraints. Your F05 gear is shown read-only. **Find ideas** asks Gemini for candidate activities; each one is checked deterministically (time, straight-line distance to the place lookup match, owned-gear capabilities via F07, light/weather evidence via F09, avoided subjects, event dependence) and shown as *feasible*, *unverified* (open points listed) or *not suitable*, ranked without invented scores. **Continue in Requirements** opens an unconfirmed F03 draft (F03 confirms), **Save idea** stores it in a Draft/Planned session (or a new draft session), **Dismiss** removes it from the results. Road distance, travel time, opening hours and events are never verified or invented; a Gemini failure yields no ideas. CLI:

```text
discover place Toronto
discover run place=1 date=2026-11-06 from=13:00 until=17:00 distance=40 interests="landscape; aviation; architecture" avoid=portraits
discover save 1 new
discover select 2 <session-id>        (then requirement draft / requirement confirm)
discover dismiss 3
discover again distance=10
```

[Workflow, checks, ranking, design changes, demo script and limits](docs/f02-discovery.md).

## F03 requirement parsing (Gemini)

Open **Workspace > Requirement Parsing**, choose a Draft or Planned session, describe the shoot and select **Analyze with Gemini** (or **Enter manually**). Review and edit the extracted fields, answer any clarification questions, then **Confirm and save**. Nothing is stored until confirmation; Cancel discards the draft. If Gemini fails, the request text stays in place and manual entry still works. The CLI offers the same workflow over the same services:

```text
requirement parse <session-id> I want to photograph aircraft at an airshow around sunset for three hours, two bodies, no lens changes.
requirement set location="Lakeshore airfield" date=2026-11-14
requirement clarify It is on November 14 at the lakeshore airfield
requirement confirm
requirement show <session-id>
```

`requirement manual <session-id>` starts manual entry (reusing a request kept after an AI failure) and `requirement cancel` discards the draft. Requires `LLM_API_KEY` (see configuration below). [Workflow, classes, schema, verification and limits](docs/f03-requirements.md).

## F04 AI gear recognition and import (UC04)

Open **Equipment > AI Gear Import**, or select **Import from image (AI)** in Gear Inventory. Choose the image type (equipment photo, product label or purchase screenshot) and a PNG/JPEG file, then **Load image**: the file is validated by content, size and dimensions and decoded on this computer only. Drag on the preview to black out personal information or to crop to the product, then **Create sanitized copy**: redacted pixels are replaced, the copy is downscaled to at most 2048 px and re-encoded as PNG without the original metadata, and the copy itself is shown. Tick the approval box and **Approve and recognize with Gemini**: only that copy is sent. Gemini proposes candidate models from visible markings only; PhotoPilot then retrieves specifications from Wikipedia (revision-pinned infobox data) and the Lensfun open database. Each field shows its value and status (*Externally verified (source)*, *Read by AI from the image*, *Entered by you*, *Unknown or conflicting*), with conflicts, sources and retrieval times. Choose the correct candidate, correct fields, decide about possible duplicates (add separately or increase an existing item's quantity), explicitly accept any unverified required values, and **Confirm import**. Nothing is saved before that; cancelling or any failure leaves the inventory unchanged and releases the temporary image data; your original file is never modified. CLI:

```text
import load "C:\path\order.png" --kind=screenshot
import redact 10 10 600 80            (x y width height in original pixels; also: crop, undo, regions)
import preview                        (writes the sanitized copy and prints its path for review)
import approve --confirm
import select 2 | import set mount="Sony E" quantity=2 | import verify
import confirm [--accept-unverified] [--add-separate | --increase=<UUID>]
import cancel
```

`F04` prints the command help. [Workflow, privacy, sources, design changes, verification, demo script and limits](docs/f04-gear-import.md).

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

## F07 gear matching and compatibility (UC07 + UC14)

Open **Equipment > Compatibility Check**, choose a Draft or Planned session with confirmed F03 requirements, select **Enter capability needs**, add needs (for example focal length 100–600 mm, 2 camera bodies) and **Confirm and save profile**. This is the minimal manual F06 confirmation path; F06 AI capability analysis is still unavailable. Choose **Portability** or **Coverage** and select **Match My Gear**. Every combination of owned gear is checked by deterministic UC14 rules (mount, filter thread, battery type, storage format, quantities). Unknown specifications count as unverified, never compatible. Only verified loadouts are ranked. Gemini may reorder and explain them; if it fails, a clearly labelled deterministic ranking is shown. **Select this loadout** and confirm to save it to the session. The selection is revalidated against current inventory first. The CLI offers the same workflow:

```text
match profile <session-id>
match need focalLength minimum=100 maximum=600 priority=1 mandatory=yes
match need cameraCount minimum=2 maximum=2 priority=2
match profile confirm
match run <session-id> portability
match select 1
match confirm            (match confirm replace to replace an existing selection)
match show <session-id>
```

`match capabilities` lists the supported needs. [Workflow, rules, design changes, verification, demo script and limits](docs/f07-matching.md).

## F09 environment planning (UC09)

Open **Photography Tools > Environment Planning** and choose a Draft or Planned session. Its confirmed F03 subject, goals and date are shown read-only and prefilled; without a confirmed requirement, enter the activity for this assessment only. Enter a location name and select **Find place coordinates** to choose among matching places (a name alone is never used), or type coordinates. Device location is requested only after explicit consent and is unsupported on desktop Java, so manual entry always works. Enter the date, the period you can be on site (local times; an end before the start continues past midnight) and an optional preparation lead time, then **Assess environment**. PhotoPilot retrieves real Open-Meteo weather and sunrise-sunset.org sun times, computes golden/blue-hour estimates, asks Gemini to propose windows from that evidence and shows only windows that pass deterministic checks. Facts, estimates, AI interpretation, rejected proposals and limitations appear in separate cards. Nothing is saved. CLI:

```text
env show <session-id>
env place Billy Bishop Toronto City Airport
env assess <session-id> place=1 date=2026-11-06 from=13:00 until=18:30 lead=30
env again from=15:30 until=21:00
```

[Providers, time-zone rules, validation, design changes, verification, demo script and limits](docs/f09-environment.md).

## F10 shoot planning (UC10, UC11 + UC14)

Prerequisites for a session: confirmed F03 requirements and a confirmed F06 capability profile (entered in F07); a selected F07 loadout and a fresh F09 assessment made in the same application run are used when available. Open **Workspace > Shoot Planning**, choose the session, review the **Planning inputs** card and select **Generate plan**. Gemini receives only labelled evidence (no identifiers, coordinates, titles or notes) and may call the deterministic F08 calculation tool in a bounded loop; it chooses one of the verified owned loadouts, mounts, settings, strategy, schedule, preparation and shot priorities. `PlanValidator` then re-runs UC14, the confirmed camera-count/lens-change/weight limits, mandatory capability needs, numeric settings against recorded lens data, recomputes every calculation and checks schedule windows against the F09 availability and sun evidence. The preview shows validation, evidence and tool calls; nothing is saved until **Approve and save**, which revalidates and saves atomically (the first approval moves a Draft session to Planned). With a saved plan, enter a **Change request** (for example "Make the setup lighter, but keep the two-camera requirement and avoid lens changes") and **Preview change**: only the affected sections change, fixed constraints are preserved, the comparison card lists changed and unchanged decisions, and the saved plan is untouched until you approve. CLI:

```text
plan show <session-id>
plan generate <session-id>
plan approve                     (or plan reject)
plan revise <session-id> "Make the setup lighter, but keep the two-camera requirement and avoid lens changes."
plan compare
plan approve
plan list <session-id>
plan open <session-id> [revision]
```

[Workflow, agent/tool safety, validation rules, design changes, verification, demo script and limits](docs/f10-planning.md).

## Planned features

| ID | Stage 1 feature | Type | Status |
| --- | --- | --- | --- |
| F01 | Photography Session and History Management | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED; F10/F11 prerequisites retained |
| F02 | AI Photography Inspiration and Activity Recommendation | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (offline fakes; live providers + Gemini CLI smoke-tested) |
| F03 | Natural-Language Photography Requirement Parsing | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (live Gemini smoke-tested) |
| F04 | AI Gear Recognition and Automatic Import | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (offline fakes; live Gemini + Wikipedia + Lensfun CLI smoke-tested) |
| F05 | Photography Gear Inventory Management | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED |
| F06 | AI Photography Equipment Capability Requirement Analysis | Hybrid | IN PROGRESS (inventory analysis + manual profile confirmation; AI analysis not implemented) |
| F07 | Owned Gear Matching and Compatibility Validation | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (AI ranking verified offline; live run fell back) |
| F08 | Photography Technical Calculation Tool Suite | Deterministic | IMPLEMENTED / GUI + CLI VERIFIED |
| F09 | Location, Time, and Environment-Aware Photography Planning | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (offline fakes; live providers + Gemini CLI smoke-tested) |
| F10 | AI Shoot Plan Generation and Interactive Modification | Hybrid | IMPLEMENTED / GUI + CLI VERIFIED (offline fakes; see docs/f10-planning.md for the live run) |
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

**F03 requirement parsing is the only AI workflow.** It and the manual CLI diagnostic `ai-test` (below) are the only code paths that send Gemini requests; other AI workflows remain NOT IMPLEMENTED. Startup, existing GUI/CLI commands and non-AI features do not require a key. The [repository-root setup guide](../README.md#local-gemini-key-setup-infrastructure-only) explains Google AI Studio key creation and the same setup process for developers and TAs using their own credentials.

The canonical file is `<repository-root>/.env` (`ProjectGit/.env` in the local checkout), outside `Stage2`. From this directory, create it without overwriting an existing file:

```powershell
if (-not (Test-Path ../.env)) { Copy-Item ../.env.example ../.env }
```

On macOS/Linux: `test -e ../.env || cp ../.env.example ../.env`. Open `../.env` locally and paste your key after `LLM_API_KEY=`, replacing `YOUR_LOCAL_API_KEY_HERE` or filling the empty value. Keep both tracked example files unchanged. `Stage2/.env.example` retains a matching safe template for compatibility; if using it, copy it to **`../.env`**, not `Stage2/.env`. Launch the GUI/CLI with the commands above and restart after key changes.

Alternatively, set `LLM_API_KEY` through your operating system's environment-variable settings before launching the app. Use this project's `LLM_API_KEY` name; `GEMINI_API_KEY` and `GOOGLE_API_KEY` are not read by PhotoPilot.

`LLMConfiguration.load(environment)` is called by the shared `ApplicationBootstrap` and injected into `GeminiAdapter` for both GUI and CLI. Loading is read-only and follows these rules:

- A nonblank system `LLM_API_KEY` takes precedence. An absent/blank variable falls back to the local file. An invalid nonblank environment value is reported as invalid configuration, without silently switching to the file's key.
- Running from the repository root reads `.env`; running from the Maven `Stage2` directory reads `../.env`. Other launch directories read their own `.env`; IDEs should use one of the documented working directories. Embedders/tests can supply an explicit root with `load(environment, projectRoot)`. No parent-directory search or resource/classpath loading occurs.
- The UTF-8 file supports `LLM_API_KEY=value`, optional surrounding single/double quotes, blank lines and whole-line `#` comments (including a UTF-8 BOM). Other variables are ignored; `.env` does not configure storage, preferences, model or endpoint. Shell execution, `export`, variable expansion, escapes, multiline values and inline comments are not supported. Duplicate key assignments and malformed values are rejected.
- `keyStatus()` reports `MISSING`, `PLACEHOLDER`, `INVALID`, `UNREADABLE_FILE` or `CONFIGURED`. Local validation rejects the example placeholder and accepts only nonempty ASCII letters, digits, `.`, `_` and `-` (newer Google keys contain a `.`); it makes no fixed prefix/length assumption and **does not establish credential authenticity, permissions, quota or connectivity**. File errors remain configuration status rather than startup failures.
- `requireApiKey()` is used only by `GeminiAdapter`, which sends the key in the `x-goog-api-key` header (never the URL); it throws a generic, secret-free error for unconfigured keys. Configuration diagnostics redact the key. No key is written to preferences, user profiles, logs or request/response objects.

Preserved integration contract:

```java
Result<ModelReply> LLMClient.complete(AgentContext context);
```

`AgentContext` and `ModelReply` are the existing request/response types; no duplicate DTOs or client interface were introduced. `GeminiAdapter` serves as the Gemini LLM client skeleton under its Stage 1 Adapter name. Its configuration-injection constructor is additive; the original constructor remains compatible and unconfigured. `complete` sends the context's text messages to `{GEMINI_ENDPOINT}/models/{GEMINI_MODEL}:generateContent` and returns the generated text as untrusted `ModelReply` output. Empty contexts and unusable keys fail locally without a request. `AgentContext` can also carry an application task instruction (sent as `systemInstruction`), labelled evidence JSON and a provider-neutral JSON Schema, which the adapter maps to `generationConfig.responseMimeType=application/json` + `responseJsonSchema` (used by F03). F04 adds image input: an approved `SanitizedImage` is sent as one inline image part, and unapproved or released images fail before any request. Provider-native tool calls still return explicit failures. No response is ever fabricated. The existing language decorator and agent/service/Facade wiring are retained.

### Gemini connection smoke test (`ai-test`)

`ai-test` is a **diagnostic command, not a PhotoPilot AI feature**. It checks the local key configuration, then sends one real request with the fixed prompt `What is 2 + 2? Answer briefly.` through `PhotoPilotFacade.aiDiagnostics()` → `LLMDiagnosticService` → the shared `LanguageAwareLLMClient` → `GeminiAdapter`. It prints the model's actual reply. Non-empty generated text counts as success. The command is CLI-only; the GUI is unchanged. No session data, evidence or images are sent.

The default model is **`gemini-3.5-flash-lite`**. Google lists it as stable and recommended for new projects, with no shutdown date announced (checked 2026-11-02). Override it with `GEMINI_MODEL`, for example `$env:GEMINI_MODEL='gemini-3.6-flash'`, before launching.

```powershell
# From Stage2, with JDK 21 and LLM_API_KEY in ../.env
./mvnw.cmd -q compile exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI'
# then type at the prompt:
#   ai-test
#   help
#   exit
```

Noninteractive form: `./mvnw.cmd -q compile exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=ai-test status exit'`.

Expected success output:

```text
[INFO] Checking Gemini API configuration...
[INFO] Sending test request to Gemini (model gemini-3.5-flash-lite)...

[AI Response]
2 + 2 = 4.

[SUCCESS] Gemini API connection successful.
```

Failures print one `[ERROR]` line and the CLI keeps accepting commands: missing/empty, placeholder, malformed or unreadable key (detected before any request); rejected key (HTTP 400 `API_KEY_INVALID`/401/403); rate limit or quota (429); unknown model (404); provider errors (5xx); timeout (30 s); network failure; and malformed, empty or blocked responses. The key is never printed, and provider messages are redacted and truncated.

Automated tests never call Google. `GeminiAdapterTest` and `AiTestCliTest` run the real HTTP client and CLI against a loopback `HttpServer` stand-in: `./mvnw.cmd -B -o test '-Dtest=GeminiAdapterTest,AiTestCliTest,BootstrapTest,LLMConfigurationTest'`. The live check is manual, through `ai-test`.

Current environment-variable configuration:

| Variable | Effect/default |
| --- | --- |
| `PHOTOPILOT_DATA_DIR` | Repository path root, default `${user.home}/.photopilot/data`; registry `profiles.json` lives here; business files live under `profiles/<uuid>/` |
| `PHOTOPILOT_CONFIG_DIR` | Preference directory, default `${user.home}/.photopilot`; JSON preferences with read-only legacy properties compatibility |
| `PHOTOPILOT_RANKING` | `portability` (default) or `coverage` sets F07's default strategy; GUI/CLI can choose per request |
| `GEMINI_MODEL` | Gemini model ID, default `gemini-3.5-flash-lite`; used by F02, F03, F04, F07, F09, F10 and `ai-test` |
| `GEMINI_ENDPOINT` | API base URI, default `https://generativelanguage.googleapis.com/v1beta`; used by F02, F03, F04, F07, F09, F10 and `ai-test` |
| `GEMINI_TIMEOUT_SECONDS` | Per-request Gemini timeout, whole seconds 5-300, default `30`; longer values help the larger F10 planning requests |
| `LLM_API_KEY` | System environment first; root `.env` fallback; sent only by Gemini requests (F02 discovery, F03 parsing, F04 recognition, F07 ranking, F09 interpretation, F10 planning) and `ai-test` |
| `PHOTOPILOT_WEATHER_ENDPOINT` | F09 Open-Meteo forecast URL, default `https://api.open-meteo.com/v1/forecast` (free, no key) |
| `PHOTOPILOT_SUN_ENDPOINT` | F09 sunrise-sunset.org URL, default `https://api.sunrise-sunset.org/json` (free, no key) |
| `PHOTOPILOT_GEOCODING_ENDPOINT` | F09 Open-Meteo geocoding URL, default `https://geocoding-api.open-meteo.com/v1/search` (free, no key) |
| `PHOTOPILOT_SPEC_WIKI_ENDPOINT` | F04 Wikipedia Action API URL, default `https://en.wikipedia.org/w/api.php` (free, no key) |
| `PHOTOPILOT_SPEC_LENSFUN_ENDPOINT` | F04 Lensfun data-file base URL, default `https://raw.githubusercontent.com/lensfun/lensfun/master/data/db/` (free, no key) |
| `PHOTOPILOT_IMPORT_MAX_MB` / `PHOTOPILOT_IMPORT_MAX_MEGAPIXELS` | F04 image limits, whole numbers 1-100, defaults 20 MB and 50 MP |

For example, `$env:PHOTOPILOT_RANKING='coverage'` (PowerShell) or `export PHOTOPILOT_RANKING=coverage` (POSIX shell) selects wiring before startup. `PhotoPilotAgent.run` performs a single structured step with strict JSON decoding for F02, F03, F07 and F09; `PhotoPilotAgent.runWithTools` is F10's bounded multi-step tool loop (allowlisted, schema-checked deterministic tools). F04 image recognition uses `run` with the approved sanitized image; F06 AI analysis and F11 remain future work.

`.gitignore` protects `.env`, `.env.*` except `.env.example`, and `config.local.json`; both examples contain only `LLM_API_KEY=YOUR_LOCAL_API_KEY_HERE`. Never force-add a key or include it in recordings/reports. Ignore rules cannot remove already tracked secrets or old commits; exposed keys must be revoked/rotated and removed from the repository/history.

## Known limits and next implementation boundaries

F01, F02, F03, F04, F05, F07, F08, F09 and F10 are verified (see the feature table). EXIF extraction/review production, device-location access and AI review remain unavailable. New sessions remain Draft until an F10 plan is approved; Reviewed requires F11 to supply a review. F01 now scans actual historical gear references; corrupt or unrecognized session storage blocks removal. F02, F03, F04, F07, F09 and F10 use Gemini; `ai-test` remains a separate diagnostic.

The original F01 optional-location/notes gap is resolved by documented SessionService overloads. F02 selection/save/dismiss routing and F07 selected-loadout persistence remain explicit gaps. Provider/library selection, generic AI list schema validation and PlanCommand decoding remain deferred. See [F01 design changes](docs/f01-sessions.md) and the historical source-verification gap record.

This build is preparation for Stage 2 implementation. It does not meet the final requirements for working features, operational design patterns, LLM behavior, implementation report, demonstration videos, weekly Git evidence or Stage 3 testing. The original scaffolding verification predates Git integration. Repository uploads do not imply completed features or final course deliverables.

## F08 calculators

Select Technical Calculator in the existing GUI, or enter `calc help` in the CLI. Supported tools: exposure, nd, dof, hyperfocal, fov, storage, battery. Example: `calc nd shutter=1/125 ndStops=10` returns 8.192 s. Optional inventory prefill never changes saved gear; manual input needs no inventory or API key. All results show units and assumptions; optical and capacity tools are labeled estimates.

[F08 usage, formulas, source inventory, design decisions, tests and limitations](docs/f08-calculators.md). Final F08 suite: 53/53 tests and GUI_SMOKE_OK, Windows/JDK 21. A graphical desktop is needed for the real JavaFX acceptance tests. Close existing GUI instances before `clean` on Windows if the native cache DLL is locked.

## Local JSON storage and user management

GUI startup initializes local storage behind a short splash, then presents Choose a workspace. Continue validates the selected user through the shared ProfileManager and opens Home. The avatar menu opens User Settings for creation, rename, confirmed archive and avatar management inside the workspace; Switch Workspace returns to the chooser for selection. Names may be duplicated; identifiers stay hidden in the GUI (the CLI still lists them). Display names never determine paths. All business services/history/tool dependencies use guarded repositories beneath `<root>/profiles/<uuid>/`. PNG/JPEG avatars are copied as normalized `avatar.png` in that UUID directory, with preview, restart persistence and restoration to initials. Display language remains global under PHOTOPILOT_CONFIG_DIR. See [user workspace behavior, architecture and verification](docs/user-workspace.md).
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
