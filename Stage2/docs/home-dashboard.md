# Home dashboard

Redesigned 2026-11-08 (America/Toronto) from user-supplied reference screenshots, then refined three times the same day (below). Presentation and Home-only view logic: no service, repository, schema, CLI command, navigation route, theme system, sidebar, header or Stage1 change, and no new dependency.

*Updated 2026-11-23 ([session workflow](session-workflow.md)):* the session cards now read the window's shared `SessionWorkflowModel`, the same source as the sidebar's progress. **Continue** resumes the session at its recommended page, both cards have a progress row, an *Other sessions* line was added and `HomeDashboard.Step` was removed. Structure, Session cards and Limits describe this state; Files and Verification record the 2026-11-08 passes.

## Structure

Home (`HomeDashboard`, `#home-dashboard`) contains, top to bottom, and nothing else:

1. **Welcome card** (always shown): the active user's avatar, "Welcome, {name}" / "欢迎，{name}" and "Local workspace" / "本地工作区". It has no action; sessions are created from Photography Sessions as before.
2. **Mini AI inspiration card** (always shown): "今天想拍点什么？" is its first and most prominent element; then the description, a multiline input (64 px when short, growing with its text to 120 px, then scrolling; since the 2026-11-09 [Mini AI final refinement](home-mini-ai.md#final-ui-refinement-2026-11-09-user-requested), previously a fixed 176 px) with the placeholder "例如：今晚想出去拍点城市夜景，但还没想好拍什么……", four suggestion chips on the left (今晚拍什么 / moon, 附近随拍 / location, 根据器材 / camera, 随机灵感 / shuffle) and **生成灵感** (with its sparkles icon) on the right; below them, only when needed, the status line and the answer area of [Mini AI](home-mini-ai.md).
3. **Session row**: zero, one or two cards built from the active user's stored sessions and their workflow progress (below).
4. **Other sessions line**, only when sessions the cards do not show need attention, are ready to shoot or await their review: their counts ("Other sessions: 1 need attention · 2 ready to shoot") and a **View all sessions** link to Photography Sessions.

### Refinement (2026-11-08, second pass, user-requested)

- Removed the welcome card's **+ 新建拍摄** button. The Home-only hooks it used (`SessionPane.create()`, `GuiProfileController.newSession`, `HomeDashboard.Links.newSession`, `AppIcons.ADD`, `home.newSession`) were removed with it, so `SessionPane` is byte-for-byte its pre-redesign code; F01's own Create session flow is unchanged.
- Removed the whole Mini AI header group (sparkles icon, its rounded-square background, "AI 摄影灵感", "你的随身摄影创意助手" and their container; keys `home.ai.title` / `home.ai.subtitle`, CSS `.home-icon-large` / `.home-card-caption`). Nothing replaces it, so it takes no space: the question row starts at the card's top padding.
- Layout: the question is now the focal point (28 px bold, up from 26); "Mini AI" sits at the right end of the question's first line (top-aligned with a 6 px offset, so it stays on the first line when a narrow English window wraps the question). Spacing: description 6 px below the question, input 18 px, chips and Generate 16 px. Every Home card's content now starts 22 px from its left edge (welcome `16 22`, Mini AI `22`, session cards unchanged), so the avatar, the question and the session icons line up.
- The session cards' design, content and logic are unchanged. Their one-line title, heading and metadata labels now also carry their full text as the accessible name (in addition to the hover tooltip), which a first focused run showed was needed for a reliable full-text check at the minimum width.

### Final refinement (2026-11-08, third pass, user-requested)

- Removed the "Mini AI" badge completely (label, pill background, the row container that held it beside the question, its 6 px offset, and the `home.ai.badge` key). The question is now a direct child of the card again, at the card's top padding, with no empty space. `.home-pill` remains for the session-card badges. The Generate tooltip and accessible help still say "Mini AI … not available yet"; that is help text, not a badge.
- The input grew from 68 px to 88 px (about 30%; minimum 60 → 80, `prefRowCount` 2 → 3). Width, radius 12, `-pp-input` background, hairline border, internal padding, top-left placeholder and multiline wrapping are unchanged. The card height follows automatically (20 px taller); spacing between question, description (6), input (18) and actions (16) is unchanged.

### Input height doubled (2026-11-08, fourth pass, user-requested)

- The input is now exactly twice as tall: 88 px → **176 px**. Its minimum height is the same 176 px (it was 80), so the page layout cannot squeeze it back at small windows; `prefRowCount` 3 → 8 matches the new height. Width, radius 12, `-pp-input` background, hairline border, internal padding (6 8 outside, 4 6 inside), 14 px font, top-left placeholder and wrapping are unchanged. Longer text scrolls inside the box with the standard vertical scroll bar instead of growing it.
- The card grows by the same 88 px. The question and description stay where they were; the chips and **生成灵感** move down and keep their 16 px gap below the input. Nothing else on Home changed.

The former "Essentials" / "常用功能" and "Planning and discovery" / "规划与发现" feature grids were removed together with their 16 message keys per language; every feature stays reachable from the unchanged sidebar. (2026-11-23: the sidebar is now session-centric; see [session workflow](session-workflow.md).)

### Mini AI

Since 2026-11-09 Mini AI is functional: **Generate** (or Enter) sends the question with the short in-memory conversation to the configured model and shows a short answer below the chips; a chip only fills the input. Design, request, length control, errors and tests: [Home Mini AI](home-mini-ai.md). (Until then it was presentation only, with Generate disabled.) The typed text and the conversation are not saved and are not part of the unsaved-input checks. The existing AI features (F02–F04, F06, F07, F09–F11) are untouched.

## Session cards

Each time Home is shown, `HomeDashboard.refresh()` asks the window's `SessionWorkflowModel` to re-read the active user's sessions: one `SessionWorkflowService.overview()` on the window's ordered local lane, which also picks up changes made in another window or the CLI. The cards render from that model and again whenever it changes, so a save in this window updates them at once; Home makes no session read of its own. A failed read changes nothing (before the first successful one no card is shown); no card is invented. A user switch rebuilds the workspace for that user (existing `GuiProfileController` behaviour), so Home only ever reads the active user's sessions.

Selection uses the lifecycle states (`PhotographySession.status()`), the window's current session and `updatedAt` (`HomeDashboard.Highlights`):

| Card | Shown when | Session chosen | Status badge | Action |
| --- | --- | --- | --- | --- |
| **继续上次 / Continue** | a Draft or Planned session exists | the current session when it is Draft or Planned, else the most recently updated Draft or Planned session | the workflow phase: 刚开始 / Getting started, 规划中 / Planning in progress, 准备拍摄 / Ready to shoot | **继续准备 →** makes it the current session and opens the page of its recommended next milestone (`GuiProfileController.resume`, the same action as **Resume** in Photography Sessions; navigation only) |
| **拍完之后 / After the shoot** | a Completed or Reviewed session exists | the current session when it is Completed or Reviewed; else the most recently updated Completed session (shot, waiting for its review); if none, the most recently updated Reviewed one | Completed → 待复盘 / To review; Reviewed → the existing 已复盘 / Reviewed label | **开始复盘 →** (Reviewed: **查看复盘 →**) makes it the current session and opens Post-Shoot Review with it selected (`ReviewPane.prefer(id)` + existing navigation; an open review draft keeps its own session) |

Draft and Planned sessions are never treated as completed shoots.

Card text, all from stored data:

- **Title**: the session title (one line; a tooltip shows it in full when shortened).
- **Progress row** (both cards): a thin bar and "57% · 4 of 7 milestones" from the session's workflow, below the title.
- **Continue description**: the guidance of the recommended next milestone from the shared workflow (`SessionWorkflow.guidanceKey`), for example "描述并确认拍摄需求。" / "Describe and confirm the shooting requirements.", or its re-check sentence when a result needs review; "All milestones done" when nothing is left. It replaced `HomeDashboard.Step` (removed 2026-11-23).
- **After description**: Completed without statistics → import photo metadata; Completed with statistics → "回看拍摄参数，记录这次的经验。"; Reviewed → the review is saved.
- **Metadata**: the confirmed requirement's subject (there is no separate category field) · on the Continue card, the recommended milestone's name (拍摄需求 / 器材需求 / 器材选择 / 环境评估 / 拍摄计划 / 拍摄完成); the After card shows subject · the session dates (next section). Without a confirmed requirement the subject is omitted (the Continue card shows the session date instead). Nothing is inferred from titles or notes.

### Session dates (Stage2OptRe1 PP-01)

Until 2026-11-23 the After card printed the session date as "Shot on {date}" / "拍摄于 {date}". The session date is the date the shoot was **scheduled** for; a session marked completed early (allowed) or rescheduled was then shown as shot on a day it was not. The shared `application.SessionDates` now words every session date for Home, the Photography Sessions editor and the CLI (`session open`/`list`):

| Evidence | Home After card | CLI and editor detail line |
| --- | --- | --- |
| No saved statistics, or statistics without capture times | Scheduled 2026-12-12 / 计划日期 2026-12-12 | none (the summary line already shows the scheduled date) |
| EXIF capture span inside the scheduled day | Photos dated 2026-12-12 / 照片日期 2026-12-12 | Dates: scheduled …; photos dated … (EXIF camera clock, n of m photos with a capture time) |
| Capture span on other days (shot early or late, or a wrong camera clock) | Photos dated 2026-11-23 · scheduled 2026-12-12 | same line |
| Span over midnight | Photos dated 2026-12-12 to 2026-12-13 | same line |

The photo dates are the saved statistics' first and last camera-clock capture times, so they are dated as the camera recorded them, not proven shooting dates. PhotoPilot records no completion time (`updatedAt` is the last change, not a completion), and a review's own date is its saved time in the review heading. No stored field was added, so existing sessions read and render unchanged. Tests: `SessionDatesTest` (shared wording, CLI, rescheduling, stored bytes, Chinese), `SessionDatesGuiTest` (the audit's 2026-12-12 session completed on 2026-11-23, Home after a photo import, the editor), `HomeDashboardGuiTest` (its fixture without capture times now reads "Scheduled 2026-11-01").

### Four layouts

| State | Data | Home shows |
| --- | --- | --- |
| 1 | unfinished and completed/reviewed sessions | both cards: Continue left, After the shoot right, equal widths and heights |
| 2 | unfinished only | Continue across the full row |
| 3 | completed/reviewed only | After the shoot across the full row |
| 4 | neither (new user) | welcome and Mini AI cards only; the row is removed from layout (`managed=false`), no placeholder |

Only cards that have a session are created (no invisible cards). Each card has `HBox` grow with a zero preferred width, so one card spans the row and two share it equally without any state-specific size calculation; a flexible spacer keeps both footers on the bottom edge.

## Styling

All new rules are scoped under `#home-dashboard` in `photopilot.css` and use only existing tokens, so every accent, Custom Color, Light/Dark/Follow System and Transparency setting applies. Cards keep `.surface-panel` (frosted backdrop, radius 16, border) with the level-2 `-pp-surface-2` fill and a soft shadow; actions are radius-10 rounded rectangles, chips and Generate are pills. Six outline icons were added to `AppIcons` (sparkles, moon, location pin, shuffle, trend, right arrow); the clock and camera icons are reused.

## Files

- `ui/gui/HomeDashboard.java` (rewritten), `PhotoPilotWorkspace.java` (`connectHome`, refresh on `showHome`), `GuiProfileController.java` (Home links, `reviewSession`), `ReviewPane.java` (`prefer(id)`), `AppIcons.java` (icons).
- `photopilot.css` (Home-scoped rules); `messages.properties`, `messages_en.properties`, `messages_zh_CN.properties` (29 keys added, 16 removed, after the final refinement).
- Tests: new `HomeDashboardGuiTest`; `ProfileGuiTest` now checks that Home has no feature cards or New shoot button and that the sidebar still navigates (instead of clicking the removed cards).

## Verification

All passes: 2026-11-08, Windows / JDK 21.0.13, in an isolated copy outside the repository (no `.env`), `LLM_API_KEY` unset, `PHOTOPILOT_LIVE_GEMINI=0`, scratch data/config and the original Maven cache:
`./mvnw.cmd -B -o -Dmaven.repo.local=<Stage2/.mvn/repository> package javafx:run '-Djavafx.args=--smoke-test'`.

**Input height pass (current, fourth pass):** **418 run, 0 failures/errors, 3 skipped** (the opt-in live Gemini tests), JAR, `GUI_SMOKE_OK` (a real launch through splash, user chooser, Home and all 11 features; the launch phase logged only Maven's usual "Overwriting artifact's file" notice). The 18 unresolved-lookup warnings all fall inside `ProfileGuiTest` (the known transient ones).

- `HomeDashboardGuiTest` now asserts that the input is 176 px tall with a 176 px minimum; the chips and Generate start 16 px below it and end inside the card's padding; a three-line text is kept; a 20-line text leaves the box at 176 px, makes the text taller than the visible area and shows the vertical scroll bar. The layout loop adds the user's reference window **1430 × 1055** to 1194 × 843 and 880 × 600 in English and Chinese: at each size the window really has that size, `LanguageLayoutGuiTest.check` passes (no clipping or overlap), the input stays 176 px and Generate is vertically centred on the chips; at 1430 × 1055 the four chips share one row centred with Generate. The removed-element, four-state, card-action and user-switch checks are unchanged.
- A first run of the new alignment check failed because it also required one chip row at 1194 × 843 in English, where (as in the previous passes) "Random idea" wraps to a second row and Generate centres on both rows; the check was corrected, not the layout.
- Snapshots inspected: English and Chinese at 1430 × 1055 (input twice as tall, chips and Generate on one aligned row, no clipping), Chinese at 880 × 600 (page scrolls as before; input keeps its height), compared with the previous pass's images.

**Final refinement pass (historical, third pass):** the same command gave **418 run, 0 failures/errors, 3 skipped**, JAR, `GUI_SMOKE_OK`, with no lookup warnings; it checked the badge removal and the then 88 px input.

**Second pass (historical):** the same command gave **418 run, 0 failures/errors, 3 skipped**, JAR, `GUI_SMOKE_OK`, with only the 18 known transient `ProfileGuiTest` lookup warnings; it checked the then-present "Mini AI" label at the end of the question row.

**First pass (historical, superseded by the refinement):** the same command gave **418 run, 0 failures/errors, 3 skipped**, JAR, `GUI_SMOKE_OK`.

- `HomeDashboardGuiTest` (new, 2 tests) drives the real `GuiProfileController` on temporary storage with controlled fixtures: state 4 (new user: welcome and Mini AI only, row unmanaged, no placeholder), state 2 (Draft only: the card is as wide as the row and the Mini AI card; title, status, next step and metadata come from the stored session), state 1 (Continue left, After the shoot right, equal widths and heights), state 3 (Completed only, full width). Continue opens that session in the F01 editor, After the shoot opens F11 with that session selected, New shoot opens an empty F01 create form, and switching to another user shows no cards. `LanguageLayoutGuiTest.check` (no overlap, nothing past the window edge, no shortened text without a tooltip, no missing translation) passes in English and Chinese at 1194 × 843 and 880 × 600. Generate is disabled with its accessible help, a chip only fills the input and nothing is queued. A second test covers the selection rules (latest `updatedAt`, Planned is not a shoot, Completed preferred over a newer Reviewed, Reviewed fallback) and the step order.
- `ProfileGuiTest` then asserted Home had no feature cards and that New shoot opened the F01 create form (instead of clicking the six removed cards).
- Rendered snapshots (`target/home/*.png`) were inspected: Light/Chinese at 1194 × 843 for all four states against the two reference screenshots, English and Chinese at 880 × 600 (also scrolled to the cards), and Dark mode.
- A first focused run caught a startup regression in the initial wiring: loading sessions inside `connectHome` kept the worker queue busy, so the startup chooser refused to open. Home now loads only when it is shown.
- Not done: a manual real-input session on Home, physical HiDPI displays, macOS/Linux and screen readers. No commit or push.

## Limits

- No session category or "planning stage" field exists, so the metadata uses the confirmed requirement's free-text subject and the stage is the recommended milestone of the shared session workflow. F07 Gear Selection is milestone 03 (until 2026-11-23 Home did not count it as a step); it stays optional for planning, because F10 does not require it.
- Home re-reads sessions when it is shown and updates at once after a save in this window; changes made in another window or the CLI while Home stays open appear on the next visit.
- At the minimum window width, session card headings and metadata can shorten with an ellipsis (the full text is in a tooltip and the accessible name), and a longer translation of the Mini AI question would wrap (the current English one fits on one line at 880 px). When the card is narrower than the four chips plus Generate (English at 1194 px, both languages at 880 px), the chips wrap to a second row and Generate stays centred on them.
- At 880 × 600 the doubled input makes Home taller than the window, so the session cards are reached by scrolling the page (the page already scrolled at that size before). Chinese titles in the English interface use the platform fallback font, as elsewhere in the app.
- Mini AI limits are listed in [Home Mini AI](home-mini-ai.md#limits).
