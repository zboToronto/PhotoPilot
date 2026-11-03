# PhotoPilot
EECS3311Project

The Java 21 / JavaFX application is in [Stage2](Stage2/README.md). English and Simplified Chinese are supported through GUI Settings and CLI `lang` / `--lang`; see the [localization guide and verification](Stage2/docs/localization.md). F01 sessions/history, F05 inventory and F08 calculators are implemented; see [session usage and verification](Stage2/docs/f01-sessions.md). Other business features and live AI integration remain scaffolded.

## Local Gemini key setup (infrastructure only)

**AI features are not implemented.** A key is optional for the current application, and configuring one does not enable AI features. The only request the app ever sends is the manual CLI diagnostic `ai-test` (step 5).

1. Sign in to [Google AI Studio's API Keys page](https://aistudio.google.com/apikey). Create a key in your own Google Cloud project (import/select a project if needed), following [Google's key setup instructions](https://ai.google.dev/gemini-api/docs/api-key). The TA should create their own key using this same process; no developer credential is distributed.
2. From this repository root, copy `.env.example` to `.env` **only if `.env` does not already exist**:

   ```powershell
   if (-not (Test-Path .env)) { Copy-Item .env.example .env }
   ```

   On macOS/Linux: `test -e .env || cp .env.example .env`.
3. Open the root `.env` locally and replace the placeholder (or fill the empty value) after `LLM_API_KEY=` with your key. Never put it in `.env.example`, Java source, reports, screenshots, logs or chat. Leave the key empty when it is not needed.
4. With JDK 21 configured, launch from `Stage2`: `./mvnw.cmd javafx:run` for GUI or `./mvnw.cmd exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI'` for CLI. On macOS/Linux use `sh ./mvnw` instead of `./mvnw.cmd`.
5. To verify the key and Gemini connectivity, start the CLI and type `ai-test`. It sends one fixed prompt (`What is 2 + 2? Answer briefly.`) to `gemini-3.5-flash-lite` (override with `GEMINI_MODEL`) and prints the real reply, followed by `[SUCCESS] Gemini API connection successful.` or a clear `[ERROR]` line. The CLI keeps running afterwards. This is a diagnostic command, not a PhotoPilot AI feature. [Details](Stage2/README.md#gemini-connection-smoke-test-ai-test).

Both entry points pass the system environment to the shared bootstrap. `LLMConfiguration` loads a nonblank system `LLM_API_KEY` first, falling back to the root `.env` when that variable is absent or blank. Launching from `Stage2` resolves the same parent-root file. Restart the application after changing configuration. Local checks detect missing/placeholder/malformed keys; only `ai-test` verifies a key with Google. Invalid configuration does not disable existing non-AI features.

The existing `LLMClient.complete(AgentContext)` and `ModelReply` define the request/response contract. `GeminiAdapter` is the project's Gemini client (no duplicate `GeminiLLMClient` class). It now performs real text requests with provider error, timeout and response handling, but only `ai-test` calls it. Reasoning, structured schemas, tool calls, image analysis and agent workflows remain future work. See [configuration details and integration boundaries](Stage2/README.md#configuration-and-future-integrations).

`.env`, `.env.*` and `config.local.json` are ignored; `.env.example` contains only a placeholder and is safe to commit. Never force-add secret files. Ignore rules do not remove secrets from files already tracked or from history; any accidentally exposed key must be revoked/rotated and removed from the repository and its history.
