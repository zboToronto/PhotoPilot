# PhotoPilot
EECS3311Project

PhotoPilot is a Java 21 / JavaFX photography workflow agent with a GUI and a CLI that share one application layer. The application is in [Stage2](Stage2/README.md). All eleven features F01–F11 are implemented in both GUI and CLI. Current per-feature status and its evidence: [feature status](docs/FEATURE_STATUS.md). English and Simplified Chinese are supported through GUI Settings and CLI `lang` / `--lang` ([localization](Stage2/docs/localization.md)).

AI features (F02, F03, F04, F06, F07 ranking, F09 interpretation, F10 planning/revision, F11 review) call Gemini through `PhotoPilotAgent` → `LanguageAwareLLMClient` → `GeminiAdapter`. Every reply is decoded strictly, checked deterministically (compatibility, calculations, timing, evidence) and saved only after explicit user confirmation. Deterministic features (F01, F05, F08, F11 statistics) and manual entry paths work without a key; AI features then report a clear configuration error.

## Local Gemini key setup

1. Sign in to [Google AI Studio's API Keys page](https://aistudio.google.com/apikey). Create a key in your own Google Cloud project (import/select a project if needed), following [Google's key setup instructions](https://ai.google.dev/gemini-api/docs/api-key). The TA should create their own key using this same process; no developer credential is distributed.
2. From this repository root, copy `.env.example` to `.env` **only if `.env` does not already exist**:

   ```powershell
   if (-not (Test-Path .env)) { Copy-Item .env.example .env }
   ```

   On macOS/Linux: `test -e .env || cp .env.example .env`.
3. Open the root `.env` locally and replace the placeholder after `LLM_API_KEY=` with your key. Never put it in `.env.example`, Java source, reports, screenshots, logs or chat.
4. With JDK 21 configured, launch from `Stage2`: `./mvnw.cmd javafx:run` for GUI or `./mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI'` for CLI. On macOS/Linux use `sh ./mvnw` instead of `./mvnw.cmd`.
5. To verify the key and Gemini connectivity, start the CLI and type `ai-test`. It prints where the key was looked up (the `LLM_API_KEY` environment variable, or the exact `.env` path), sends one fixed prompt to `gemini-3.5-flash-lite` (override with `GEMINI_MODEL`) and prints the reply followed by `[SUCCESS] ...` or a clear `[ERROR]` line. It is a diagnostic command, not a PhotoPilot feature. [Details](Stage2/README.md#gemini-connection-smoke-test-ai-test).

Both entry points pass the system environment to the shared bootstrap. `LLMConfiguration` uses a nonblank system `LLM_API_KEY` first and otherwise reads `.env` in the launch directory, where launching from `Stage2` resolves the parent (repository root) file. Launching from any other directory looks for `.env` there; `ai-test` shows which file was used. Restart the application after changing configuration. Local checks detect missing, placeholder and malformed keys before any request; only an AI operation or `ai-test` contacts Google.

`.env`, `.env.*` and `config.local.json` are ignored; `.env.example` contains only a placeholder and is safe to commit. Never force-add secret files. Ignore rules do not remove secrets from files already tracked or from history; any accidentally exposed key must be revoked/rotated and removed from the repository and its history.
