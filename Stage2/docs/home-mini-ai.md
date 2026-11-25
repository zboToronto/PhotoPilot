# Home Mini AI

Implemented 2026-11-09 (America/Toronto), user-requested: the Home inspiration card ([Home dashboard](home-dashboard.md)) now sends questions to the configured model and shows short answers. The approved card layout is unchanged; only the status line and the answer area were added below the chips. F02 Inspiration and F10 Shoot Planning are separate and unchanged.

Open **General > Home** for the Mini AI prompt and quick actions. **General > Discover Inspiration** opens the separate F02 workflow.

## Flow

1. The user types (or a chip fills) a question in the input, which is 64 px (about two lines) when short, grows with its wrapped text up to 120 px and then scrolls (see UI refinements below). **Enter** sends; **Shift+Enter** inserts a new line; while an input method is composing (e.g. pinyin), Enter belongs to the input method and never sends.
2. `MiniAiService.validate`: empty or whitespace-only → "Type a photography question first."; more than **300 characters** (code points) → "Please keep your question within 300 characters (it has N)." Nothing is sent; the input is kept.
3. Generate is disabled and a small spinner with "Generating ideas…" appears under the chips. The request runs on the window's existing worker queue (`UiOperations`), never on the JavaFX thread; Enter and Generate do nothing while it runs, so there is never a second request.
4. `MiniAiService.ask` builds one request and calls the shared model port: `LanguageAwareLLMClient` → `GeminiAdapter`, so the configured `LLM_API_KEY`, `GEMINI_MODEL`, endpoint and `GEMINI_TIMEOUT_SECONDS` apply. No second client exists.
5. The reply is checked and tidied (below). On success the exchange is added to the conversation, the answer appears in the card, and the input is cleared (unless the user typed something else meanwhile). On failure a short error appears, the input and the conversation stay as they were, and Generate retries.

## Request

| Part | Content |
| --- | --- |
| System instruction | `MiniAiService.INSTRUCTION` (the user-supplied Mini AI specification adapted to PhotoPilot: concise, plain text, 50–100 Chinese characters / 25–55 words, at most about 150 / 80, one to three sentences, user's language, no invented weather, places, events, gear or tool results; recommend the Inspiration page for detailed configurable ideas), then a language line (Chinese question → "reply in Chinese, in the same script"; otherwise "the same language as the user's latest message", both overriding the UI-language default of the existing Decorator), then the detail rule when asked. |
| Evidence (labelled data) | `conversation`: up to 6 earlier exchanges, oldest first; `localTime`: e.g. `Monday 2026-11-09 18:30` (no time zone, so no location hint). Nothing else: no profile, sessions, location or inventory. |
| User message | The question exactly as typed (stripped). |
| `generationConfig.maxOutputTokens` | 1024, or 2048 when the user asks for more detail. Sent only for Mini AI requests. |

The F02/F10 prompts and every other request are unchanged: an `AgentContext` without a cap sends no `maxOutputTokens` (`GeminiAdapterTest.outputTokenCapIsSentOnlyForRequestsThatAskForIt`).

## Response length

Four layers, cheapest first:

1. **Instruction**: target and maximum lengths per language, one to three sentences, no Markdown, lists, tables or filler.
2. **Token cap**: 1024 / 2048 output tokens. It bounds runaway replies and cost; it is not the length control itself, because models that reason before answering count that reasoning against the same cap, and a tight cap would produce empty replies.
3. **Post-generation check** (`MiniAiService.tidy`): code fences and table rows are dropped; headings, list markers, emphasis and inline code are removed; single line breaks flow together (no full stop is invented at a soft break), so the answer wraps naturally at the display width; a blank line from the model (an intentional paragraph break) is kept as one blank line, except after a heading or inside a list; a heading or list item becomes a sentence of its paragraph. Sentences are detected only to count and shorten; no line break is ever inserted between them. Empty or symbol-only text is an error, never shown.
4. **Sentence-aware shortening**: length is measured in Han characters plus Latin runs (Chinese replies) or words (other replies). Over the limit (150 / 80, or 300 / 160 after an explicit request for detail), the leading complete sentences that fit are kept. A trailing unfinished sentence (a reply cut off by the token cap) is dropped when complete sentences precede it. Only a single sentence that is longer than the whole limit is cut, at the last clause boundary (comma, semicolon, colon) that fits, with an ellipsis. No extra model request is made to shorten.

"More detail" is recognised from phrases such as "more detail", "explain", "elaborate", "step by step", 详细, 具体, 展开, 解释.

## Conversation

`MiniAiService.Conversation` is an immutable, ordered list of at most **6** exchanges (`with` drops the oldest). The Home view holds it in memory for the current user and window: only successful exchanges are added; **Clear conversation** resets it; a user switch rebuilds Home with an empty one. Nothing is written to disk or logged, and it never reaches F02 or any other page.

## Quick prompts

The four chips keep their appearance and wrapping, and only fill the input (no request):

| Chip | Filled text |
| --- | --- |
| What to shoot tonight | A request for a few concise night photography ideas. |
| Shoot nearby | A request for a short photo walk near the user. No location is read or sent (PhotoPilot has no stored or device location on desktop); the instruction tells the model to give general ideas and invite the user to name a city or neighbourhood. |
| Based on my gear | The names of the user's cameras, lenses and filters from the F05 inventory (read only, at most eight; no batteries, cards or specifications), e.g. "My gear: Sony A7 IV, Sony FE 24-105mm G. What could I shoot with it today?". An empty inventory gives "I haven't added any gear to PhotoPilot yet…"; an unreadable one gives a neutral sentence. Nothing is invented. |
| Random idea | A creative challenge around one of 12 localized themes (reflections, long shadows, a single colour, …), never the same theme twice in a row; the model's answer varies further. |

Weather and sunlight are not used: the existing F09 providers need coordinates, which Mini AI does not have and does not ask for.

## Answer area

Hidden until the first answer (no empty panel, no placeholder text). Then, below the chips: a hairline divider; a header row with the sparkles icon and "AI Inspiration" / "AI 灵感" on the left and the small text actions **Clear conversation** / 清空对话 and **Advanced inspiration** / 高级灵感 (opens Discover Inspiration through the sidebar navigation) on the right; the latest question as a one-line context subtitle — a small message icon and the question in smaller muted text, no "You:"/"你：" label, line breaks folded, an ellipsis with the full text in a tooltip and the accessible name when it is long; the answer as 14.5 px body text (line spacing 3.5, about 1.55 line height, one left-aligned label that wraps at the surface's full width and reflows on resize) on a subtle inner surface (`-pp-badge` fill, radius 12, padding 12 14, no border or shadow); then, once there are earlier exchanges, "View earlier messages (N)" / "查看之前的对话（N）". Earlier exchanges are collapsed by default and again after every new answer; when opened they show inline in muted text with a hairline between them (no card per message). Collapsing only hides them: they stay in the conversation context. All styles are Home-scoped (`#home-dashboard .home-ai-*`, `.home-link`) and use existing tokens. When the window is small, the page scrolls only as far as needed to show a new answer; at the reference size (1430 × 1055) nothing scrolls.

The Generate tooltip and accessible help now say what is sent: "Sends your question, with the last few messages of this conversation, to the AI service configured for PhotoPilot. Nothing is saved." (It replaced the old "not available yet" note.)

### Natural wrapping (2026-11-09, user-requested)

- The answer label no longer has an 820 px line cap (nor the earlier answers), so text wraps at the inner surface's width instead of short of it. `tidy` no longer appends a full stop to every line of the reply (which also broke soft-wrapped sentences) and keeps blank-line paragraph breaks; length limits and shortening by whole sentences are unchanged. JavaFX has no native "balanced" line wrapping, so short final lines are left as laid out. Verification: one isolated Maven run that compiled main and test sources and ran only `MiniAiServiceTest` (11/11 passed, including the new paragraph cases); no GUI tests, launch or live requests.

### Question display (2026-11-09, user-requested)

- The chat-style "You: … / 你：…" prefix was removed from the latest question and from the earlier exchanges (key `home.ai.asked` deleted); the latest question is a muted context line with a small message icon (`AppIcons.MESSAGE`, Home-scoped `.home-ai-asked-icon`). The answer stays the primary content. Nothing else changed. Verification, as requested: code and diff review plus one isolated `test-compile`; `HomeMiniAiGuiTest` was updated (plain question text) but not run.

### Final UI refinement (2026-11-09, user-requested)

- Input: 96–160 px → **64 px** default (about two lines; CSS minimum) growing to **120 px** (CSS maximum), then scrolling; same `fitPrompt` mechanism, `prefRowCount` 2. Clearing returns it to 64 px.
- Answer: actions back in the header row (right side, Clear conversation then Advanced inspiration); the answer sits on a subtle inner surface (described above); tighter spacing (divider 14, header 10, question 8, answer 6 px).
- History: toggle text "View earlier messages (N)" / "查看之前的对话（N）"; collapsed again after each new answer; hairlines between earlier exchanges instead of a left rule.
- No functional change beyond folding the history after a new answer. Verification, as requested: one isolated `test-compile` of main and test sources (BUILD SUCCESS); no tests, GUI runs, app launches or live requests. `HomeDashboardGuiTest` (64 px empty, growth, 120 px maximum, back to 64 px) and `HomeMiniAiGuiTest` (64 px, history folds after a new answer) were updated but not run.

### UI refinement (2026-11-09, user-requested; superseded in part by the final refinement above)

- Input: the fixed 176 px height was too large for short questions. It now defaults to **96 px** (CSS minimum), grows with its wrapped text (`HomeDashboard.fitPrompt` measures the text with an off-screen `Text` at the input's width and sets the preferred height; it runs on text changes and when the text area's content width changes) up to **160 px** (CSS maximum), then scrolls. Clearing returns it to 96 px; the answer never changes it. Width, radius, background, border, padding, font and placeholder are unchanged; chips and Generate keep their layout and 16 px gap.
- Answer area: the actions moved from the heading row to the row below the answer (heading → question → answer → actions), the question became smaller (12.5 px), the answer got more line spacing and a readable maximum line length, and earlier exchanges got a thin left rule.
- No change to requests, prompts, length rules, conversation, errors or keyboard behaviour. Verification for this refinement was limited, as requested, to one `test-compile` of main and test sources in an isolated copy (BUILD SUCCESS). No tests, GUI runs or app launches were done; `HomeDashboardGuiTest` and `HomeMiniAiGuiTest` were updated to the new heights (96 px empty, growth, 160 px maximum) but not run.

## Errors

The shared adapter's failures are mapped to short Home messages (`MiniAiService.explain`); the input is kept and nothing is shown as an answer:

| Cause | Message |
| --- | --- |
| Key missing, placeholder, malformed or unreadable `.env` | AI is not configured. Please check your API settings (LLM_API_KEY in the .env file), then restart PhotoPilot. |
| Key rejected (401/403) | The AI service rejected the API key. Please check your API settings. |
| Network | Unable to connect to the AI service. Please try again. |
| Timeout | The request timed out. Please try again. |
| 429 | The AI service is busy or its usage limit was reached. Please wait a moment and try again. |
| 5xx | The AI service is temporarily unavailable. Please try again later. |
| Empty, unreadable or Markdown-only reply | The AI returned an empty or unreadable answer, so nothing is shown. Please try again. |
| Blocked prompt | The AI service declined this question. Try rephrasing it. |
| Anything else | "Could not generate ideas." followed by the existing explanation (which never contains the key). |

There is no API-key screen in the GUI (the key lives in `.env` or the environment), so the message names the setting instead of linking to a page.

## Files

- New: `service/MiniAiService.java`; tests `service/MiniAiServiceTest.java`, `ui/gui/HomeMiniAiGuiTest.java` and the opt-in `service/LiveGeminiMiniAiSmokeTest.java`.
- Changed: `agent/AgentContext.java` (`withMaxOutputTokens`), `integration/GeminiAdapter.java` (`maxOutputTokens` only when set), `application/PhotoPilotFacade.java` (`miniAi()`, 8-argument constructor), `config/ApplicationBootstrap.java` (wiring), `ui/gui/HomeDashboard.java` (behaviour, status line, answer area), `ui/gui/GuiProfileController.java` (Advanced inspiration link), `photopilot.css` (Home-scoped answer styles), the three `messages*.properties` (22 keys added, `home.ai.unavailable` removed, chip prompts rewritten as questions), `GeminiAdapterTest`, `HomeDashboardGuiTest`.
- Design change record: [stage2-design-changes](stage2-design-changes.md).

## Verification

2026-11-09, Windows / JDK 21.0.13, in an isolated copy outside the repository (no `.env`), `LLM_API_KEY` unset, `PHOTOPILOT_LIVE_GEMINI=0`, scratch data/config and the original Maven cache:
`./mvnw.cmd -B -o -Dmaven.repo.local=<Stage2/.mvn/repository> clean package javafx:run '-Djavafx.args=--smoke-test'` → **435 run, 0 failures/errors, 4 skipped** (the opt-in live Gemini tests, including `LiveGeminiMiniAiSmokeTest`), JAR, `GUI_SMOKE_OK`. The 18 unresolved-lookup warnings are the known transient `ProfileGuiTest` ones.

- `MiniAiServiceTest` (10, scripted model and loopback fake): empty/whitespace and over-300-character input rejected before any request (characters, not UTF-16 units); request = Mini AI instruction, 1024-token cap, the question as the user message, evidence limited to `conversation` + `localTime`; six-exchange bound, order and reset; Chinese language line and detail rule (2048 tokens); English and Chinese shortening to whole sentences, clause-boundary cut for one run-on sentence; Markdown/list/table flattening and dropped unfinished tail; every provider failure mapped to its short message and never an answer; the real `GeminiAdapter` with no key (no network contact), a closed port, 429, non-JSON and Markdown-only replies; gear names = owned cameras, lenses and filters only, empty and unreadable inventory.
- `HomeMiniAiGuiTest` (5, real workspace, `MiniAiService` and `GeminiAdapter` against the loopback `FakeGemini` with a fake key): Enter sends and the answer appears with its question; a follow-up request contains the first exchange; the earlier exchange is collapsible; EN/ZH layout checks (no overlap, clipping or missing text) at 1430 × 1055 and 880 × 600 with the answer and history open, input still 176 px; Shift+Enter inserts a new line; Clear conversation drops the context; Advanced inspiration opens the Inspiration page; while a request is held open the FX thread answers immediately, Generate is disabled, the spinner and "Generating ideas…" show, repeated Generate/Enter send nothing more, and text typed meanwhile is kept; validation, 503, unreadable reply and missing configuration keep the input and show no answer; failed requests are not in the conversation; Enter during IME composition sends nothing; at 880 × 600 a new answer is scrolled into view; chips fill text only (gear from real inventory, empty-inventory sentence, random never repeats a theme).
- `GeminiAdapterTest.outputTokenCapIsSentOnlyForRequestsThatAskForIt`: requests without a cap send no `generationConfig`; a schema request keeps its fields beside the cap.
- `HomeDashboardGuiTest` adapted: Generate enabled with the privacy help; status line and answer area take no space before the first question.
- Snapshots inspected: answer with expanded history in English and Chinese at 1430 × 1055 and 880 × 600.
- Not done: a live Gemini request (run `LiveGeminiMiniAiSmokeTest` with `PHOTOPILOT_LIVE_GEMINI=1` and a configured key; it writes `target/mini-ai-live-smoke.txt`), a manual session with real keyboard/IME input, HiDPI and screen readers. No commit or push.

## Limits

- Answers depend on the model following the instruction; the post-check bounds length and format but cannot judge photographic accuracy.
- One request at a time per window, on the shared worker queue: while a Mini AI request runs (up to the configured timeout), other work of the same window, such as reading gear for the gear chip, waits behind it, as for the other AI features. There is no cancel action.
- No location, weather or sun data is used; "Shoot nearby" stays general unless the user names a place.
- The conversation is lost when the app closes or the user switches (by design: nothing is stored).
- Live answers were not checked in this implementation run; the automated tests use a loopback fake (see Verification).
