# Bilingual localization implementation

Status: implemented and verified for the current GUI, CLI, F05 and F08. Languages: **English (`en`, default/fallback)** and **Simplified Chinese (`zh-CN`)**. This is additional usability infrastructure, not one of the ten required substantial business features. The project still has two implemented business features (F05/F08); nine remain scaffolded. No live LLM response is claimed.

## Usage and preference storage

In the GUI, open **Settings / 设置** in the upper right and choose **English** or **简体中文**. Selection applies immediately and saves automatically. **Save preference / 保存偏好** retries a failed save. The modeless window updates along with the main window and other open settings windows. Current navigation, scroll position, equipment selection, search text, unsaved fields, text selection, calculator choice, inputs and displayed results remain in place. Background completions render in the current language. Text-field context menus are localized too.

The CLI retains `help`, `status`, `F01`–`F11`, `gear`, `calc`, `exit`/`quit` and EOF. Language commands:

```text
lang
lang zh-CN
lang en
```

`lang` shows the current language; `lang <tag>` saves it. Unsupported interactive selections return an error without replacing the preference. Invocation overrides `--lang en`, `--lang zh-CN` and `--lang=zh-CN` take precedence over the saved language and do not save it. An invalid/missing override reports an error and uses English. Passing only an override starts interactive input; a supplied command list executes noninteractively. Each multiword command remains one argument, as before:

```powershell
# From Stage2 with JDK 21 / JAVA_HOME configured
./mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=--lang zh-CN help "calc nd shutter=1/125 ndStops=10" exit'
```

GUI and CLI share `${user.home}/.photopilot/settings.properties`, independently of the business-data working directory. `PHOTOPILOT_CONFIG_DIR` overrides the configuration directory; `PHOTOPILOT_DATA_DIR` continues to control inventory separately. Constructors read preferences but do not create directories or files. The only preference written is `language`; unrelated properties are merged under a sidecar file lock and persisted by atomic replacement. A missing file or unsupported value uses English. Read/write/lock failures are diagnosed once per service; a failed write preserves the old file, keeps the new language for this process, and displays a retryable failure. Malformed files are preserved rather than silently overwritten. Filesystems without atomic replacement cannot persist the selection and report failure. No credentials belong in this file.

CLI stdin/stdout use UTF-8 in the main entry point, including redirected pipes. Use a UTF-8-capable terminal/font. Commands, enum constants, UUIDs, paths, `key=value` fields, units consumed by commands, JSON keys and user values remain unchanged. Separate already-running processes do not watch each other's preferences; a new launch restores the latest saved preference.

## Architecture and responsibilities

| Participant | Responsibility and integration |
| --- | --- |
| `Language` | Allowlist of stable `en` / `zh-CN` identifiers, locales and native display names. Invalid persisted/API values normalize to English. |
| `LocalizationService` | Per-application ResourceBundle facade: UTF-8 decoding, English fallback, MessageFormat, locale selection, preference failure state, Observer subscriptions and once-per-key diagnostics. Never changes `Locale.setDefault`. |
| `LocalePreferenceRepository` / `FileLocalePreferenceRepository` | Storage port and UTF-8, locked, atomic properties implementation. Transient implementation isolates legacy constructors and tests. |
| `LocalizedMessage` / `LocalizedException` / `EnglishMessages` | Stable key and arguments survive domain/service boundaries. Nested descriptors localize field names. Fixed English rendering preserves diagnostic compatibility without a mutable global application locale. |
| `Result` | Additive `failureKey`, descriptor/exception/Result propagation, `errors(localization)` and `hasError(key)`. Existing `errors()` remains English. Duplicate detection now uses a key, not translated text. |
| `ApplicationBootstrap` | Constructs one localization instance before presentation; injects it into GUI/CLI and the model-port decorator. No business preferences or data are reset. |
| `FxLocalizer` | View-owned JavaFX property bindings and localized combo-box cells/context menus. Marshals notifications onto the FX thread. Subscribes only while its window is showing; detaching/closing removes subscriptions. Weak property keys release obsolete dynamic controls. `close` supports explicit disposal. Chooses an installed CJK font without downloads. |
| `SettingsDialog` | Edits the language preference and reports save/read failures; keeps other settings windows synchronized without recursive saves. Uses the existing theme. |
| Existing workspace, feature, inventory and calculator panes | Bind controls and delayed result suppliers. Locale events never call navigation, activate workflows, rebuild forms or rerun business operations. |
| Existing CLI command adapters / `CalculationForm` | Render shared Result messages and typed calculation evidence at the presentation boundary. Parsing and deterministic business rules remain shared. |
| `LanguageAwareLLMClient` / `AgentContext` | Decorates the existing `LLMClient` port. Copies request context with a default response-language instruction and delegates exactly once. Preserves original messages/evidence/history/image approval; does not run translation calls. |

The [updated implementation UML](localization-architecture.md) describes the added class relationships and locale-change sequence. Stage1 files remain untouched. No new dependency or alternate application/framework was introduced.

## Patterns and design change rationale

**Original design → implemented change → reason → impact:** English-only GUI/CLI with no settings repository → a shared localization facade, preference storage port and disposable view subscriptions → reliable runtime language changes without duplicating views or domain rules → F05/UC05/SD02 and F08/UC08/SD03 retain their business implementations; all F01–F11 navigation/unavailable feedback gains bilingual presentation. AI-related use cases retain their unavailable business status.

**Observer:** `LocalizationService` is the subject; `FxLocalizer` subscriptions update the properties of existing views. Window lifecycle releases subscriptions. This solves immediate multiwindow refresh without a global UI rebuild.

**Facade:** `LocalizationService.text/selectLanguage/listen` hides resource, formatting, persistence and notification details. The existing `PhotoPilotFacade` remains the business facade.

**Decorator:** `LanguageAwareLLMClient` implements `LLMClient`, adds request-scoped language policy and calls its delegate once. It preserves the existing Gemini Adapter boundary rather than implementing a second agent. Existing Strategy, Adapter, Command and State classes remain; their unimplemented business behavior is not promoted to completion by this work. No artificial localization Strategy was added.

## AI integration boundary and remaining prerequisite

The selected language reaches the existing model port as `AgentContext.responseLanguageInstruction()` on a **new context copy**. The instruction explicitly yields to a user's requested language, higher-priority instructions, safety, tools, schemas, enum values, API contracts and code syntax. Static UI strings are never sent to a model. No conversation is translated or rewritten; deterministic tool schemas and JSON output are byte-identical across UI languages.

**Important limitation:** `PhotoPilotAgent.run` and `GeminiAdapter.complete` were already unimplemented and remain so. There is no working prompt-to-provider pipeline or chat history UI in this repository to localize end to end. The decorator is wired at bootstrap and verified with a capturing test double; it cannot make live AI responses work. When implementing Gemini, serialize `responseLanguageInstruction()` separately from user/history/evidence content as a default preference within the provider's instruction pipeline. When implementing the bounded agent loop, use the wired model port. Actual model obedience to explicit user language requests remains unverified; the tests establish policy content and unchanged context, not LLM behavior. No API credentials or live provider calls were used.

## Resources, coverage and extension

Resources are in `src/main/resources/i18n/`:

- `messages.properties`: English root fallback.
- `messages_en.properties`: explicit English, kept identical to root by tests.
- `messages_zh_CN.properties`: Simplified Chinese.

All three contain **327 keys**. Keys use semantic namespaces such as `settings.*`, `feature.F05.*`, `gear.*`, `calc.*`, `error.*`, `cli.*`, `edit.*`. Requested language resolves first, followed by English root, then `[!key!]`. The custom ResourceBundle control disables operating-system locale fallback and explicitly reads UTF-8. Missing Chinese keys and fully missing keys emit one diagnostic per key per service. Malformed shipped patterns are caught by development tests; they are not silently hidden.

Use `MessageFormat` arguments (`{0}`, `{1}`), including nested `LocalizedMessage` values. Double apostrophes in resource patterns (`''`), use `\n` for line breaks, and retain the same placeholders in every locale. Never concatenate localized sentence fragments or retranslate rendered English strings. Technical numeric entry syntax stays invariant; MessageFormat formats UI counts for the selected locale. English and Chinese calculator output uses the existing six-decimal deterministic numeric convention and localized labels/units. No date/time UI currently exists.

| Audit class | Implemented handling |
| --- | --- |
| Static UI | Shell/navigation, headings, status badges, settings, buttons, form hints, empty states, table headers, selectors, keyboard hint, accessible names and text-field context menus are bundled. |
| Human feedback/errors | F05/F08 validation, duplicate review, save/removal/status text, CLI help/usage/errors and unavailable Result messages use descriptors/bundles. Service Results propagate descriptors rather than flattening them. |
| Dynamic UI | Counts, equipment identities, confirmation details, calculation labels/assumptions and field names use parameterized resources. Already displayed results relocalize without recalculation. |
| User/AI content | Brand/model/search/form input, identifiers and existing messages are untouched. No runtime translation dictionary, translation API or UI-to-LLM translation exists. |
| Machine contracts | Catalog's internal status protocol, calculation evidence keys/units/English assumptions, tool schemas, serialization and command syntax stay stable. Human renderers use separate localized labels. |
| Diagnostics | Low-level JVM/OS/JSON exception details and developer smoke/log markers stay technical, preceded by a localized explanation where exposed. Native OS window chrome follows the OS. |

No known application-owned static GUI/CLI English sentence is left unbundled in the current reachable interfaces. Unknown future third-party error text remains original diagnostic detail behind a localized wrapper. The source audit is deliberately a targeted check, not a claim that arbitrary future Java expressions can be exhaustively recognized by a regex.

To add a language: add a stable enum tag/Locale/native name; create the matching UTF-8 bundle with the full root key set; extend resource-consistency tests to enumerate it; add a response-language policy name to `LanguageAwareLLMClient`; test persistence, CLI and live GUI switching, parameter rendering and installed-font fallback. Update the supported-language docs. Do not use translated display names as configuration values.

## Verification and observed failures

On Windows / JDK 21, 2026-10-27 (Toronto):

```powershell
./mvnw.cmd -B -o test
./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'
./mvnw.cmd -B -o -DskipTests package
./mvnw.cmd -B -o package javafx:run '-Djavafx.args=--smoke-test'
```

Baseline: **53/53** existing tests passed before edits. Localization adds **18 tests**: 8 service/persistence tests, 3 CLI integration tests, 2 resource checks, 2 model-policy/tool-contract tests and 3 real JavaFX acceptance tests. Full suite: **71 passed, 0 failed/errors/skipped**. Real GUI smoke prints `GUI_SMOKE_OK`; all eleven feature entries and minimum-window behavior are exercised. Packaging succeeds, and all three UTF-8 bundles were read from the resulting JAR. Packaging skips tests only after the full suite run; it is not an independent test result.

The final combined `package javafx:run` command also completed successfully with all 71 tests, JAR construction and GUI_SMOKE_OK. Maven's JavaFX process-classes lifecycle emitted an artifact-file reassignment warning after packaging; the JAR remained present and its 327 entries per language bundle were verified directly. Existing 53-test source files and Stage1 files are unchanged. Final diff whitespace and local documentation links were checked.

New tests use temporary or transient preferences. GUI coverage includes multiple settings windows, saved-language restoration, retained unsaved fields/text selection/search/table selection/scroll position, repeated language changes, newly opened windows, translated calculator results, background-thread locale events, inventory completion after a language change, all equipment forms/calculator choices and zero service subscriptions after closing windows. Model tests capture instructions/context and verify exactly one delegate call; no real model is contacted.

Independent CLI launches from the packaged JAR verified fresh English, saved Chinese, restart restoration, temporary English override without overwriting Chinese, Chinese calculator output and UTF-8 redirected input/output. Evidence output is generated under ignored `target/cli-zh-utf8.txt`; test preferences stay under ignored `target/`.

`tools/verify_sources.py` passes 87 baseline UML types / 267 fields / 184 operations and all 20 original source hashes. Its null-return safeguard now has one exact exception for the standard `ResourceBundle.Control.getFallbackLocale` hook, where null is required to disable OS fallback; domain null-return checks remain intact.

During development the sandbox could not read a cached JavaFX JAR for javac; the same offline build succeeded with access to the existing cache. Intermediate compilation failures (JavaFX accessibility property type and checked Control exceptions) were fixed. New audit/test fixtures initially misidentified a domain `text("mount")` helper, assumed a connected inventory in a shell-only smoke check, and assumed all skin labels had non-null text; these were corrected without removing existing tests. Visual inspection found blank custom combo-box placeholders; the application fix now preserves and translates them. The JavaFX unnamed-module warning remains an existing non-failing environment warning.

Actual JavaFX snapshots were visually inspected for Chinese settings, wide inventory, the scrolled equipment editor, narrow calculator and calculation results. Wrapping, Chinese glyphs and controls are readable. Snapshots regenerate under ignored `target/i18n-*.png`; they are not demonstration videos. macOS/Linux, other DPI scales, screen-reader speech, live providers, end-to-end conversation preservation and submission videos are unverified. There was no clean-build claim, commit, push, token-cost claim or course-completion claim.

## Changed-file map

Paths below are relative to `Stage2/src/main/java/com/photopilot` unless stated otherwise.

| Added/modified files | Change |
| --- | --- |
| Added `i18n/Language.java`, `LocalizationService.java`, `LocalePreferenceRepository.java`, `FileLocalePreferenceRepository.java`, `LocalizedMessage.java`, `LocalizedException.java`, `EnglishMessages.java` | Localization and preference subsystem. |
| Added `ui/gui/FxLocalizer.java`, `SettingsDialog.java`; modified `PhotoPilotGUI.java`, `PhotoPilotWorkspace.java`, `FeaturePane.java`, `FeaturePresentation.java`, `InventoryPane.java`, `CalculatorPane.java` | Existing controls bind resources, language settings, safe FX refresh and disposal, localized context menus and installed CJK fonts. |
| Modified `config/ApplicationBootstrap.java`, `FeatureCatalog.java` | Shared instance wiring; localized catalog display while retaining internal status parsing. |
| Modified `ui/cli/PhotoPilotCLI.java`, `InventoryCommands.java`, `CalculationCommands.java` | Shared language selection/persistence, temporary override, UTF-8 entry point and human output. |
| Modified `common/Result.java`, `application/EquipmentForm.java`, `CalculationForm.java`, `model/Equipment.java`, `EquipmentType.java`, `EquipmentData.java`, `GearInventory.java`, `CalculationResult.java` | Typed messages and presentation formatting; domain/API diagnostics and calculation evidence preserved. |
| Modified `service/InventoryService.java`, `CalculationValidation.java`, `TechnicalCalculator.java`, `repository/InventoryRepository.java`, `JsonInventoryRepository.java`, `JsonSessionRepository.java` | Localized validation/storage wrappers and descriptor-preserving failure propagation. |
| Added `integration/LanguageAwareLLMClient.java`; modified `agent/AgentContext.java` | Request-scoped response policy with unchanged context/history. |
| Existing scaffold Result producers in `agent`, `command`, `integration`, `model`, `service`, `state`, `strategy`, `validation`, `ui/privacy` | Only unavailable messages converted to `failureKey("error.notImplemented", operation)`; no placeholder business operation was enabled. |
| Added `src/main/resources/i18n/messages*.properties`; modified `src/main/resources/com/photopilot/ui/gui/photopilot.css` | Complete English/Chinese resources; settings reuse existing form theme. |
| Added `src/test/java/com/photopilot/i18n/{LocalizationTest,LocalizedCliTest,TranslationResourcesTest,AgentLanguageTest}.java`, `ui/gui/LocalizationGuiTest.java`, `src/test/resources/i18n/fallback*.properties` | Service/persistence, integration, GUI and resource consistency checks. Existing tests retained. |
| Modified `tools/verify_sources.py`, READMEs, `.gitignore`, `.env.example`, feature mapping, verification and project status docs; added this guide and implementation UML | Reproducible usage, architecture, audit evidence and explicit limitations. |

See Git's current diff for the complete per-file list. Generated build outputs, process-test preferences, authoring scripts and screenshots are ignored and are not source deliverables.
