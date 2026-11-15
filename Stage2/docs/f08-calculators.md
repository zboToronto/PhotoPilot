# F08 technical calculator implementation

Verified 2026-10-26 (America/Toronto), Windows, JDK 21. Stage 1 report sections 2, 6.8, 8 and 9.8 plus SD03/SD04 define six operations. All six are implemented; exposure and ND are separate GUI/CLI choices under the original EXPOSURE_ND kind. No Stage1 files, F05 domain/repository code, global styles, dependencies, or unrelated workflows changed.

## Calculator workspace (current, 2026-11-14)

Six calculations: exposure value, ND filter exposure, depth of field, hyperfocal distance, field of view and motion blur (added 2026-11-14). The CLI has a command for each. The GUI shows them on five pages, because depth of field and hyperfocal distance share one page with two focus modes (merged later on 2026-11-14; see [the combined page](#depth-of-field-and-hyperfocal-distance-one-page-two-focus-modes-2026-11-14)). The GUI page is a workspace with one page selector and, per page, live inputs, results and a native visualization; there is no Calculate button. This section is the current description. The sections after it describe the original 2026-10-26 form-based implementation and are historical where they differ (form fields, Calculate button, text result card, storage/battery).

Storage and battery (2026-11-09): those two calculators were removed from the GUI and CLI at the user's request (`calc storage` / `calc battery` report an unknown calculator). The STORAGE and BATTERY_CAPACITY operations stay in `CalculationKind`, `CalculationValidation`, `TechnicalCalculator` and the agent `calculation` tool, because F10 plans can still request and recompute them.

### Everything is computed locally in Java

No calculator needs a model, an API key, the network, a browser engine or another process, and opening or using the workspace sends no AI request. `CalculatorIndependenceTest` checks the sources for it and `CalculatorGuiTest` uses the workspace against a recording fake model endpoint and asserts that nothing was sent.

### Layers and files

| Layer | Files (under `src/main/java/com/photopilot`) | Responsibility |
| --- | --- | --- |
| Formulas | `service/PhotoFormulas.java` (new) | The one place the formulas live: pure functions in mm and seconds, no validation text, rounding, formatting or UI. |
| Validated calculation | `service/TechnicalCalculator.java`, `service/CalculationValidation.java`, `model/CalculationInput/Result/Kind.java` | Existing entry point of the Facade, CLI, agent tool and F10. Checks parameters and units, converts units, evaluates `PhotoFormulas`, returns named values with units and assumptions. `MOTION_BLUR` added. |
| Camera model | `model/CameraSetup.java` (new) | Sensor width/height in mm, optional image pixel dimensions, circle-of-confusion policy or explicit value, format id. Six format presets plus custom. |
| Presentation (no JavaFX) | `application/CalculationForm.java`, `application/CalculatorPresentation.java` (new), `application/MotionBlurPreview.java` (new) | Field definitions and parsing shared with the CLI; nominal stop scales, slider scales, units, time/distance/angle text, the distance-axis rule, ND commercial names, preview geometry. |
| UI state | `ui/gui/CalculatorPane.java` (rewritten), `CalculatorMode.java`, `CalculatorField.java`, `CameraSetupEditor.java`, `ExposureMode`, `NdFilterMode`, `DepthOfFieldMode` (depth of field and hyperfocal distance, two focus modes), `FieldOfViewMode`, `MotionBlurMode` (new; `HyperfocalMode` existed until the merge and was removed) | Page selector (`CalculatorPane.PAGES`), per-page inputs kept for the session, slider and field kept in step, inline validation, scoped reset, shared camera and distance unit. Each change calls `PhotoPilotFacade.calculate`. |
| Rendering | `ui/gui/CalculatorDiagram.java`, `ExposureDiagram`, `NdFilterDiagram`, `FocusDiagram`, `FieldOfViewDiagram`, `MotionBlurDiagram` (new) | Native JavaFX `Canvas` drawings. They only draw values that were already calculated. Colours come from the theme tokens and text from the stylesheet font, so mode, theme colour, Font Size, Bold Text and language apply. |
| CLI / agent | `ui/cli/CalculationCommands.java`, `agent/CalculationTool.java` | `calc motion`, optional `distance` on `calc fov`, near/far lines on `calc hyperfocal`; tool schema lists `MOTION_BLUR` and the optional parameters. |

One rule constrains the calculation layer: F10 recomputes the calculations stored in a plan and compares them key for key (`PlanValidator`), so an unchanged input must keep exactly its output names. New outputs therefore exist only as a new operation (`MOTION_BLUR`) or behind a new optional parameter (`distance` on `FIELD_OF_VIEW`); the near limit when focused at the hyperfocal distance is a second calculation (depth of field at s = H), not an extra value of `HYPERFOCAL`.

### Conventions

- **Units.** Formulas use mm and seconds. The calculation API takes explicit units per parameter (optical lengths mm/cm/m, distance m/mm/ft, shutter s/ms, speed m/s, km/h, mph or ft/s, image width px, blur limit mm/um or px) and reports distances in metres. In the GUI a distance is kept in metres and only shown and typed in the chosen unit (m or ft), so a distance set exactly (the hyperfocal distance) reaches the calculation unchanged.
- **Nominal stop markings.** A marking is the number it shows: f/5.6 is N = 5.6, 1/125 is 0.008 s, ISO 125 is 125. The exact series behind the markings is not substituted, so neighbouring slider positions are about, not exactly, one third of a stop apart; EV readouts show the exact figure. Typed values are used as typed and may lie between markings.
- **Precision and display.** Results keep double precision. Display text is rounded separately (three significant digits for most readouts, two decimals for EV, fractions for exposure times below 0.3 s). Very small or large values use scientific notation rather than looking like ordinary measurements.
- **Unbounded results** are explicit: +Infinity in the result, the string `Infinity` in tool JSON, the infinity sign plus the word "unbounded" in the GUI. A finite value is never turned into infinity.
- **Circle of confusion.** CLI and agent take an explicit `coc`. The GUI derives it from the shared camera by a visible policy: sensor diagonal / 1500 by default (0.0288 mm for 36 x 24 mm), diagonal / 1730 as a stricter choice, or a custom value. The value in use is shown in the camera section, under the policy it comes from ("CoC in use: 0.0288 mm"; a custom value is its own field). That sharpness depends on the selected CoC is one line of the page's calculation details.
- **Sensor formats.** Presets (full frame 36 x 24, APS-C 23.5 x 15.6, APS-C Canon 22.3 x 14.9, Micro Four Thirds 17.3 x 13.0, 1-inch type 13.2 x 8.8, medium format 43.8 x 32.9 mm) fill in typical dimensions; the stored numbers are editable and are what is calculated with. There is no camera database. Sensor dimensions can also be copied from an inventory camera on request.
- **Pixel dimensions** are optional and entered by the user for the configured sensor area. They are never derived from megapixels; without them the motion blur calculator gives the physical blur and its share of the image width, and no pixel figure.

### The six calculations

Five GUI pages: Exposure value, ND exposure, Depth of field & hyperfocal distance (景深与超焦距), Field of view, Motion blur. The two focus rows below are the two focus modes of one page.

| Calculation | Inputs | Results | Visualization and interaction |
| --- | --- | --- | --- |
| Exposure value | Aperture N, exposure time t (fraction or decimal seconds), ISO S | Settings EV = log2(N²/t) (aperture and time only); EV100 = EV - log2(S/100), labelled separately; stops relative to a reference | Slide rule: three scales at one width per stop, each with its current value on the centre line; dragging a scale changes that setting. "Hold the exposure by solving" names the one setting that is solved (time, aperture or ISO) and marks it as solved. A reference is only the settings the user captured; without one nothing is called brighter, darker or correct. Equivalent aperture/time pairs can be applied. Grey patches are labelled a demonstration. |
| ND filter | Base exposure time, strength in stops; the same strength as filter factor and optical density (exact conversions, either can be typed) | adjusted = base x 2^stops; factor; density | Two bars to scale plus a ladder of stops on a labelled logarithmic scale. Commercial names are explained as labels (ND1000 is sold as 10 stops; factor 1000 is 9.97 stops; 10 stops is factor 1024). Zero stops returns the base time exactly. No timer. |
| Depth of field (page "Depth of field & hyperfocal distance", Manual focus) | Actual focal length, aperture, focus distance, camera (sensor, CoC) | Total depth (emphasized), near limit, far limit, hyperfocal distance | Side view on a linear, labelled distance axis: camera, focus subject, hatched interval of acceptable sharpness, hyperfocal marker. The subject can be dragged (or moved with the arrow keys); that edits the focus distance. A finite far limit outside the view is drawn broken off with its value; an unbounded one fades past the edge with the infinity sign. |
| Hyperfocal distance (same page, Hyperfocal focus) | Actual focal length, aperture, camera (sensor, CoC); the focus distance is locked to the result | Hyperfocal distance (emphasized), near limit = H/2, far limit unbounded, total unbounded | The same diagram with the focus locked at H: a padlock on the focus marker, "Focus locked at hyperfocal distance", the interval running from the near limit to the infinity sign. Near and far come from the depth of field operation at s = H (`CalculationForm.atHyperfocal`), so the two modes always agree. |
| Field of view | Actual focal length, sensor width and height, optional subject distance | Horizontal, vertical and diagonal angle; scene width and height at the distance; 35 mm equivalent focal length as additional information | Top view with the horizontal angle at its true angle, and the frame in the sensor's proportions with a 1.7 m figure to scale. The equivalent focal length is never used in the calculation. |
| Motion blur | Exposure time, actual focal length, subject distance, lateral speed (m/s, km/h, mph), camera (sensor, optional pixels), optional blur tolerance (µm on the sensor or px) | Blur on the sensor, blur in image pixels (only with the image width), share of image width, subject travel, longest exposure within the tolerance | Whole frame to scale with a 1.7 m figure smeared by the calculated blur, and a magnified detail comparing a sharp edge with the same edge in this exposure. The detail's scale (screen pixels per image pixel, or per µm when the image width is unknown) is chosen from the blur and written under the view. Zero speed gives zero blur and "no limit from motion". |

Motion blur model: stationary camera, constant subject speed parallel to the sensor, subject distance much larger than the focal length; blur (mm) = f (mm) x v (m/s) x t (s) / d (m), pixels = blur x image width / sensor width, longest exposure = limit x d / (f x v). It leaves out camera shake, panning, rotation, rolling shutter, stabilization and autofocus, and the preview illustrates the blur length rather than predicting a photograph. A subject closer than 10 focal lengths adds a note that the approximation underestimates magnification.

### Depth of field and hyperfocal distance: one page, two focus modes (2026-11-14)

User-requested consolidation of the two former pages, which duplicated the lens inputs, the camera and the diagram. Only the presentation changed: no formula, operation, output name, CLI command or tool parameter. The hyperfocal calculation remains a capability of its own (`CalculationKind.HYPERFOCAL`, `TechnicalCalculator.hyperfocal`, `calc hyperfocal`, the agent `calculation` tool).

- **Navigation.** `CalculatorPane.PAGES` lists five pages; `CalculationForm.Tool` keeps six tools for the CLI. `CalculatorPane.select(Tool.HYPERFOCAL)` opens the combined page with Hyperfocal focus, and `mode(Tool.HYPERFOCAL)` returns that page.
- **Focus mode** (`DepthOfFieldMode.FocusMode`), chosen with a two-segment selector directly above the focus distance:
  - *Manual focus* (default): the focus distance comes from its slider, its number or dragging the subject, as on the former depth of field page. One `DEPTH_OF_FIELD` calculation.
  - *Hyperfocal focus*: a persistent lock. `HYPERFOCAL` and then `DEPTH_OF_FIELD` at exactly that distance are recalculated on every change of focal length, aperture, sensor (preset or custom), circle-of-confusion policy or value, camera reset or copied inventory values. The distance is used as calculated and never limited to the slider (0.1 to 1000 m) or the diagram: the slider rests at its end and the field shows the value.
- **One source of the focus distance.** While locked, the focus distance field shows the locked value, captioned "Focus distance (locked)" in the style of a solved exposure setting. Its text and slider cannot be edited and the subject cannot be dragged or moved by keyboard. This follows the existing exposure-hold convention; "editing unlocks" was the alternative and was not chosen. The note under the selector ("Locked to hyperfocal. Manual distance kept: 5 m."), the padlock on the focus marker and the leading result tile all say the focus is locked.
- **Manual distance kept.** Locking sets the manual distance aside: in metres, or as typed when it was not usable. Unlocking restores it exactly, also after unit changes, and calculated distances never replace it. Unusable text comes back on top of the user's last usable distance, never the locked one, so the slider and a later unit change fall back to the user's value. It is read again only in the unit it was typed in; after a unit change it is dropped, as any unit change drops unreadable text. If the hyperfocal distance cannot be calculated (an unusable focal length, for example), the locked field is empty and the page says why.
- **State.** The focus mode and the manual distance stay while other pages are used and across language changes. Like every calculator input they last for the session; locked focus counts as changed input.
- **Results.** The same four tiles in both modes. Manual focus: total (emphasized), near, far, hyperfocal. Hyperfocal focus: hyperfocal (emphasized), near ("Half the hyperfocal distance"), far (∞, "Unbounded"), total (∞, "From … to infinity").
- **Diagram.** One `FocusDiagram` for both modes. Manual focus: the draggable subject and a separate dashed hyperfocal marker, or a pointer at the edge when H is beyond the view. Hyperfocal focus: the focus marker with a padlock and "Hyperfocal focus …". When the axis range changes (a mode change, a new distance), the scale eases geometrically to the new range in 0.24 s instead of jumping, with one tick spacing during the transition. The transition stops by itself and is skipped for unit changes and pages not on screen. The far limit is classified against the range actually drawn, so its style and its label always match. The axis rule is unchanged (focus at no more than about 55% of the axis). Other details:
  - Pressing the subject does not move it; only dragging does.
  - A drag never goes below the 0.1 m minimum, in feet either.
  - The keyboard focus ring shows in both modes.
  - The focus is labelled "at hyperfocal distance" only when locked or set to exactly H with an unbounded far limit; otherwise H keeps its own marker.
  - Labels are kept inside the diagram and clear of the camera.
- **Reset.** "Reset this calculator" restores 50 mm, f/8, 3 m and Manual focus, and drops the set-aside manual distance. "Reset camera" restores the camera only; a lock stays on and follows the default camera. The former standalone hyperfocal page started at 24 mm; the combined page starts at 50 mm.
- **Removed.** `HyperfocalMode`, the one-time "Focus at the hyperfocal distance" button (replaced by the mode), and the message keys `calc.hyperfocal.*` and `calc.dof.focusAtHyperfocal`.
- **Shared code touched.** `CalculatorField.automatic(boolean, captionKey)` and `retype(text)`; `CalculatorMode.order(...)` and `Tile.primary(...)`; `.calc-note:locked` in `photopilot.css`; 13 new and 2 changed keys per bundle (the reset tooltip now says "inputs and options"). The other four pages behave as before.
- **Stage 1 diagrams.** The Stage 1 class and sequence diagrams model F08 at the operation level (`TechnicalCalculator`, `CalculationKind`), which is unchanged, so they need no change for this merge. Stage 2 UML is still an outstanding deliverable.

### Text of the workspace: labels in view, details on request (2026-11-14)

User-requested cleanup of the wording on all five pages, after the pages themselves were in place. Only presentation text and its layout changed: no formula, service, range, control, unit conversion or behaviour. The CLI and the agent tool keep their own texts (`calc.assumption.*`, `calc.output.*`, `calc.field.*` and the rest are untouched).

- **Removed from the working view.**
  - The description line at the top of each page.
  - The always-visible "Model and assumptions" block.
  - How-to-use sentences: the drag hint under the focus diagram, the manual-focus note, "Off: each setting changes only when you change it", the note under the equivalent exposures, the stops/factor/density explanation.
  - Captions that repeated a value shown beside them: the scene size under the field-of-view frame, the blur in screen pixels, the millimetre copy of the sensor blur, the bar-scale sentence, "no reference" inside the exposure diagram (the tile says it).
  - The ND page's "Filter strength" tile, which only repeated the three input fields (stops, filter factor, optical density stay as those labelled fields).
  - Notes under the camera fields (presets, circle of confusion, pixel dimensions) became one tooltip and two short lines.
- **Shortened.** Result tiles keep a caption, a value and at most a few words ("66.2 cm in front", "from 1/125 s", "At ISO 100", "For comparison only; crop factor 1", "Blur ≤ 10 µm"). Diagram labels are names, values and units ("Far ∞", "Far 123 m (beyond view)", "Distance from camera (m)", "1 image px = 24 screen px"). Notes that only matter in one state appear only then: the hold note while holding, the reference line while a reference is set, the lock note while locked, a filter name when there is one. A rounded filter name is given with its own factor and stops to two decimals ("ND32000 is nominal: factor 32000 ≈ 14.97 stops."), so it never reads as the advertised whole number.
- **Calculation details / 计算详情.** One section per page, closed by default: a standard `TitledPane` (`#calc-details`) styled with the existing tokens, reachable by keyboard (Space or Enter) and announced with its expanded state. It holds two to four short lines, the formula (with its letters named where it uses any) and the assumptions, written for the GUI in ordinary notation (`CalculatorMode.details()`, keys `calc.details.*`). Explanations without practical value were removed, not moved there.
- **Cautions.** A warning that changes how a result should be read is not hidden in the details: it is a bold line under the results (`#calc-cautions`). Today that is one case, taken from the calculation's own note: a motion-blur subject closer than 10 focal lengths ("blur is underestimated"). Validation and error messages are unchanged.
- **Kept on purpose.**
  - Settings EV and EV100 as two named results.
  - Stops, filter factor and optical density as three labelled fields that convert into each other.
  - The CoC in use and "Unbounded" / ∞ against finite limits, including "(beyond view)".
  - "Focal length (actual)" against the 35 mm equivalent, which is labelled as a comparison.
  - "Blur on the sensor" against "Blur in the image" (pixels, only with the image width).
- **Accessibility.** How to move the focus subject remains the diagram's accessible help, and every diagram keeps its accessible description.
- **Layout.** The ND diagram is 20 px and the motion-blur diagram 14 px lower because a line under each was removed; nothing else moved.
- **Code.** `CalculatorMode.compose(diagram, …)` (no summary or hint), `details()`, `available(String... cautions)`, `detailTexts()`, `cautionTexts()`; CSS rules `.calc-details` and `.calc-caution`. Per bundle: 19 keys added, 35 removed and 81 changed (77 in Chinese, where four already read short). The workspace's own calculator text is about 45% shorter in English and 36% in Chinese; no key outside `calc.*` and no CLI or agent key changed.
- **Review.** Four read-only reviewers (scientific correctness, Chinese wording, leftover verbosity, functional regression), each followed by a verifier trying to refute the findings.
  - Confirmed and fixed:
    - the shortened filter-name line could state a false equality ("factor 32000 = 15 stops; 15 stops = factor 32768") because the stops were rounded to three significant digits;
    - the ND strength tile and the second "no reference" text were redundant;
    - the formula letters were undefined;
    - Enter did not toggle the details section, its arrow kept a focus glow, and Bold Text did not reach its lines;
    - Chinese wording: 前景深 / 后景深, 传感器尺寸, full-width parentheses;
    - two labels had become ambiguous ("Blur as share of image width", "figure 1.7 m tall").
  - Refuted and left: removing the lock indicators, the infinity indicators or the EV / EV100 tile details, which carry the distinctions the cleanup had to keep.
- **Tests.** `CalculatorGuiTest` has 8 cases. The new `workingViewShowsLabelsAndValuesWhileFormulasWaitInCollapsedDetails` checks on every page: no description line; the details section present, closed, focusable and titled in both languages; two to four detail lines of at most 140 characters; no visible label longer than 80 characters outside the details; no caution at the starting values. It then checks the kept distinctions page by page, the filter-name line including ND32000, the motion-blur caution appearing and clearing, and opening the section by Space and Enter. Assertions on changed texts in the other cases and in `LocalizationGuiTest` were updated.
- **Verification (2026-11-14, same environment as above, final tree).**
  - `mvnw.cmd -B -o test`: **553 run, 1 failure, 0 errors, 6 skipped**. The failure is the pre-existing `AppearanceGuiTest` PEACH-light check with the identical figure (21.236109950890143). `CalculatorGuiTest` 8/8, `LocalizationGuiTest` 3/3, `TranslationResourcesTest` 3/3, `ThemeTest` 10/10, and the calculation classes unchanged and passing.
  - `mvnw.cmd -B -o -DskipTests package javafx:run -Djavafx.args=--smoke-test`: JAR built, `GUI_SMOKE_OK`.
  - Separate CLI JVM: `calc nd` and `calc motion` print their results and their own assumption texts as before.
  - Screenshots written by the tests were inspected: all five pages, hyperfocal lock, details open in English and Chinese, the caution, the minimum window, dark mode, Font Size 125% with Bold Text.
  - Not done: hands-on use of the running application (no desktop-control tool), tooltips on hover, screen-reader output.

### Interaction rules of the workspace

- Results and drawings update on every slider position while dragging and on every usable keystroke.
- A value that cannot be used is left exactly as typed, explained on the line under its field, and summarized under the results; every result then shows a dash and the drawing is dimmed and labelled "Not current". No dialog is opened.
- Each calculator keeps its inputs while another is used. Only the camera and the distance unit are shared, and only the calculators that use them show them. Unusable text in one calculator's camera fields does not change the shared camera.
- "Reset this calculator" resets that page's inputs and options (the exposure hold and reference; Manual focus on the depth of field page); "Reset camera" resets the shared camera. Inputs that differ from their starting values count as unsaved input when switching user or closing.
- The view is two columns (inputs beside the diagram) from the minimum window size up and stacks below that; diagrams with two views stack them when narrow.
- Optional inventory values: Load inventory, choose an item, Use known values. A fixed focal length or an ND strength goes to the selected calculator, sensor dimensions to the shared camera. Nothing is inferred and the inventory is only read.

### Reference and attribution

The interaction ideas (immediate feedback, a side-view focus diagram with a draggable subject, readouts tied to the drawing) were taken from the Depth Of Field Simulator at <https://jherr.github.io/depth-of-field/>, viewed on 2026-11-13. It was used as inspiration only: no code, assets, layout or data from that project are included, nothing from its web stack is used, and the formulas and boundary behaviour were verified independently. No third-party code or assets were added for this feature and `pom.xml` is unchanged.

### Tests added or changed (2026-11-14)

Expected values are worked out from the stated formulas or taken from the task's reference figures, not produced by the code under test.

- `calculation/PhotoFormulasTest` (new, 9): the formula core in mm and seconds. f/8 at 1/125 s is EV 12.9657842847 and EV100 11.9657842847 at ISO 200; ISO does not change the settings EV; 1/125 s with 10 stops is 8.192 s, zero stops returns the time bit for bit and each stop exactly doubles it; stops, factor and density conversions; 50 mm, f/8, c = 0.03 mm gives H = 10466.6667 mm, near 2337.9052 mm and far 4185.2679 mm at 3000 mm; at H the far limit is unbounded and near = H/2, just below H it is finite, just above unbounded, for 450 lens/aperture/CoC combinations; 36 mm at 50 mm is 39.597752709°; coverage; 50 mm, 10 m/s, 20 m, 1/1000 s is 0.025 mm and 4.1666667 px at 6000 px on 36 mm; scaling and zero-speed rules.
- `calculation/MotionAndCoverageTest` (new, 8): the validated entry point: `MOTION_BLUR` with and without resolution, unit conversions, invalid and missing inputs, zero speed, blur limit in px and on the sensor; optional coverage on `FIELD_OF_VIEW`; unchanged output names of the original operations; hyperfocal agreement through `CalculationForm.atHyperfocal` across the metre conversion; CLI and agent tool on the same core.
- `calculation/CalculatorPresentationTest` (new, 13): nominal scales and slider round trips, units, time/number/distance text, equivalent exposures, ND commercial names, the distance-axis rule (clipped is never infinity), detail-view scale, smear as a box average, figure mask, `CameraSetup`.
- `calculation/CalculatorIndependenceTest` (new, 2): no calculator source refers to a model client, HTTP, a browser or script engine or a process, the formula and presentation classes do not need JavaFX, and all six calculate with nothing configured.
- `ui/gui/CalculatorGuiTest` (rewritten, 6): real controls in real windows: all six calculators from typing and from a slider while it is being dragged; independent inputs and scoped reset; exposure hold and reference; dragging the focus subject by mouse and keyboard; finite-beyond-view against unbounded; metres and feet; inline validation without dialogs; minimum window, stacked layout, dark mode, Font Size 125% with Bold Text, Chinese; inventory values; no request reaches a recording fake model endpoint.
- `ui/gui/LocalizationGuiTest` (calculator case rewritten): language switches in place with inputs, results and the selected calculator kept.
- Unchanged and passing: `CalculationTest`, `CalculationCliTest`, `CalculationToolTest`, `CalculationInventoryTest`, `PlanValidatorTest`, `AgentToolLoopTest`, `ProfileGuiTest` (calculator input still counts as unsaved input).

### Verification (2026-11-14)

Windows 11, bundled JDK 21.0.13, an isolated offline copy of `Stage2` without `.env`, `LLM_API_KEY` unset, `PHOTOPILOT_LIVE_GEMINI=0`, scratch data and configuration directories, the repository's Maven cache.

- `mvnw.cmd -B -o test` on the finished tree: **545 tests run, 1 failure, 0 errors, 6 skipped** (opt-in live Gemini x4, real shutter sample, real RAW sample). All calculator classes pass: `PhotoFormulasTest` 9, `MotionAndCoverageTest` 8, `CalculatorPresentationTest` 13, `CalculatorIndependenceTest` 2, `CalculationTest` 10, `CalculationCliTest` 1, `CalculationToolTest` 3, `CalculationInventoryTest` 1, `CalculatorGuiTest` 6, `LocalizationGuiTest` 3.
- The one failure is `AppearanceGuiTest.transparencyVisiblyChangesOnlyThePanelBackgroundAtEveryStepInBothModesAndThemes` (PEACH light: 21.24 levels against a threshold of 24). It builds the workspace shell and Application Settings only. A/B: the same sources with the calculator rules removed from `photopilot.css` fail with the identical figure, and the same figure is recorded for unchanged source on 2026-11-09. It is not caused by this feature and was not changed.
- Because that failure stops the build before packaging: `mvnw.cmd -B -o -DskipTests package javafx:run -Djavafx.args=--smoke-test` built the JAR and printed `GUI_SMOKE_OK` (splash, user selection, Home, all 11 features, minimum-size layout).
- Separate CLI process (`java -cp target/classes;<gson, metadata-extractor, xmpcore> com.photopilot.ui.cli.PhotoPilotCLI ...`): `calc help` lists the six calculators with their optional arguments; EV 12.965784 and EV100 11.965784 for f/8, 1/125 s, ISO 200; 8.192 s for 10 stops and 0.008 s for zero stops; near 2.337905 m, far 4.185268 m, H 10.466667 m, and "near limit 5.233333 m, far limit Infinity" for the hyperfocal calculator; 14.4 m x 9.6 m at 20 m; 0.025 mm, 4.166667 px and 0.00048 s for the motion blur reference case; zero speed gives zero blur; a pixel blur limit without `imageWidth` and `calc storage` are refused.
- GUI. `CalculatorGuiTest` drives the real controls in real JavaFX windows: results while a slider is being dragged, typed values, switching and scoped reset, exposure hold and reference, dragging the focus subject by mouse and by keyboard, inline validation without dialogs, resizing to the minimum window and below, dark mode, Font Size 125% with Bold Text, Chinese, inventory values, and no request to a recording fake model endpoint. The screenshots it writes to `target/` were inspected: all six calculators in the light theme, in dark mode inside the shell and in Chinese; the "Not current" state; reference and hold; the finite-beyond-view and the unbounded far limit; the minimum window and the stacked layout; large bold text at Transparency 0.
- Not done: no hands-on session with the running application, because this environment has no desktop-control tool, so real pointer use (dragging with a mouse, hover cursors) is covered only by events fired at the real controls. `tools/verify_sources.py` was not run (Python is not installed); the six Stage 1 `CalculationKind` constants and all Stage 1 signatures are unchanged and `MOTION_BLUR` is additive. Other display scales, macOS/Linux and screen reader output are untested.

### Tests and verification of the combined depth of field and hyperfocal page (2026-11-14)

The two sections above record the six-page workspace before the merge. For the merge:

- `calculation/FocusCalculatorTest` (new, 6, no display). Expected values are worked out in `BigDecimal` from q = f²/(N·c), H = q + f, near = q·s/(q + s − f), far = q·s/(q − s + f). Covered:
  - the reference results (near 2.3379052369 m, far 4.1852678571 m, H 10.4666666667 m) and output names of `DEPTH_OF_FIELD` and `HYPERFOCAL` are unchanged;
  - for five lenses, hyperfocal focus (HYPERFOCAL, then DEPTH_OF_FIELD at H) equals manual focus at the distance shown for H, with near = H/2 and far and total unbounded;
  - the far limit is unbounded at and beyond H and finite one representable step below it, and matches the decimal value at 0.5 to 0.999999 H;
  - every sensor preset with every circle-of-confusion policy gives one H to both operations, and (H − f) is inversely proportional to c;
  - 10 ft and 3.048 m give identical results;
  - empty, non-numeric, NaN, infinite, negative, zero and fraction inputs and missing parameters are refused for both operations.
- `ui/gui/CalculatorGuiTest` (7, one new). The new `hyperfocalFocusIsAPersistentLockAndManualFocusComesBackUnchanged` drives the real controls:
  - manual focus by number, by slider while it is dragged, and the diagram following both;
  - locking: field, caption, automatic style, disabled slider, tiles and their order, note, hint and diagram;
  - drag and arrow keys ignored while locked;
  - the lock following focal length (35 to 50 mm), aperture, both CoC policies, a custom CoC, a sensor preset, a custom sensor width and a camera reset;
  - 800 mm f/1.4 (H about 15.8 km) used unclamped;
  - feet and metres with the manual distance exactly 5.0 m throughout;
  - another page and back;
  - exact restore, repeated locking, a manual distance kept through a unit change, unusable and empty manual text kept as typed, and locking without a usable focal length;
  - reset of the calculator against reset of the camera;
  - `select(Tool.HYPERFOCAL)`;
  - the axis easing that stops by itself.

  The other cases now cover five pages, the locked page at the minimum window, dark mode, Font Size 125% with Bold Text (no segment caption cut short) and Chinese, and the lock following a camera copied from the inventory.
- Independent review before completion: four read-only reviewers (focus state, diagram, calculation and API preservation, requirement coverage), each followed by a verifier trying to refute the findings.
  - Confirmed and fixed:
    - restoring an empty manual entry after a lock without a hyperfocal distance left a required field empty but "valid", so the next unit change threw `NoSuchElementException`;
    - after a lock round trip with unusable text, H became the slider position and the fallback manual value;
    - saved text was re-read in a different unit;
    - the diagram had a press that moved the subject, a drag in feet below 0.1 m, "at hyperfocal" decided by pixels, an H label that could be hidden by the camera, no focus ring when locked, hover left behind after a drag released outside, and linear easing that nearly jumped over large range changes;
    - two test assertions on the far-limit style were tautological.
  - Regression checks were added to `CalculatorGuiTest` (drawn far-limit style via `FocusDiagram.farExtent()`, press without drag, feet minimum, hover, the three restore cases).
  - A/B in the scratch copy: with the restore and `CalculatorField.display()` fixes reverted, the lock test fails, first at "the slider is not left at H"; with that block removed it reaches `NoSuchElementException: No value present` on the JavaFX thread. With the fixes it passes.
  - Not changed: a diagram marked "Not current" keeps its last valid drawing and description, as on every page.
- `ui/gui/LocalizationGuiTest` and `calculation/CalculatorIndependenceTest` updated for five pages; `CalculationTest`, `CalculationCliTest`, `CalculationToolTest`, `CalculationInventoryTest`, `MotionAndCoverageTest`, `PhotoFormulasTest` unchanged and passing.
- `mvnw.cmd -B -o test` (same environment as above) on the final tree after the review fixes, compiled from clean in the scratch copy: **552 run, 1 failure, 0 errors, 6 skipped**. The failure is the pre-existing `AppearanceGuiTest` PEACH-light check with the identical figure (21.236109950890143).
- `mvnw.cmd -B -o -DskipTests package javafx:run -Djavafx.args=--smoke-test`: JAR built, `GUI_SMOKE_OK`.
- Separate CLI JVM: `calc help` lists the six calculators; `calc dof focalLength=50 aperture=8 coc=.03 distance=3` gives near 2.337905 m, far 4.185268 m, H 10.466667 m; `calc hyperfocal focalLength=50 aperture=8 coc=.03` gives H 10.466667 m and "near limit 5.233333 m, far limit Infinity"; without `coc` it is refused.
- Screenshots written by the tests were inspected: manual and locked focus, the lock at 15.8 km, the minimum window, dark mode in the shell, large bold text and Chinese.
- Not done: hands-on use of the running application (no desktop-control tool), other display scales, macOS/Linux and screen readers.

### Limits of the current workspace

Calculator inputs live for the session only; nothing is saved. The optical results are ideal thin-lens and pinhole estimates (no diffraction, pupil magnification, focus breathing, distortion or fisheye projection). The motion blur model covers lateral subject motion past a stationary camera and nothing else. Sensor presets are typical dimensions, not a camera database. The diagrams are schematic except where they say "to scale".

## Architecture and source inventory

- `service/TechnicalCalculator.java`: existing calculate dispatcher and six public operation methods now perform deterministic mathematics.
- `service/CalculationValidation.java` (new): operation-specific required/allowed keys, ranges, finite values, unit normalization, integer counts. `model/CalculationInput.java` delegates validate and exposes immutable inputs; `model/CalculationResult.java` exposes immutable numerical evidence, units, assumptions and input.
- `application/CalculationForm.java` (new): shared tool/field definitions, numeric and shutter-fraction parsing, formatting, known-value inventory mapping. No formula in presentation handlers.
- `ui/gui/CalculatorPane.java` (new), `FeaturePane.java`, `PhotoPilotGUI.java`, `PhotoPilotWorkspace.java`: form selector, responsive fields, result/validation cards, optional asynchronous inventory read, navigation lifecycle and stale callback guard. Reuses the existing inventory form CSS, primary/secondary controls, typography and spacing; no CSS changes.
- `ui/cli/CalculationCommands.java` (new), `PhotoPilotCLI.java`: `calc` commands and F08 alias, error output, optional gear UUID. Both presentations call the existing `PhotoPilotFacade.calculate` unchanged.
- `agent/CalculationTool.java`: strict bounded JSON input, duplicate/type/unknown key rejection, domain validation and serialized evidence. Infinity becomes the JSON string `Infinity`. No LLM request. Existing bootstrap supplies the same TechnicalCalculator instance to the Facade/planning/tool.
- `config/FeatureCatalog.java`: F08 implemented marker only after all operations were wired and verified. GUI navigation/status regression expectations updated.
- New tests: `calculation/CalculationTest.java`, `CalculationCliTest.java`, `CalculationInventoryTest.java`, `CalculationToolTest.java`, `ui/gui/CalculatorGuiTest.java`. Existing `BootstrapTest.java` and `FeaturePresentationTest.java` updated for truthful F08 availability; F05 tests retained.

Paths above are relative to `src/main/java/com/photopilot` or `src/test/java/com/photopilot`. README, feature mapping, root PROJECT_MEMORY and FEATURE_STATUS document current behavior.

Pattern: existing Facade provides shared calculation access. CalculationTool is the existing tool adapter boundary. No new Strategy/Command/State pattern is invented; other pattern implementations are unaffected.

## Formulas, units and assumptions

| Operation | Inputs and calculation | Output / assumption |
| --- | --- | --- |
| Exposure | aperture N, shutter t seconds, ISO S; EV=2 log2(N)-log2(t); EV100=EV-log2(S/100) | Camera setting EV and ISO-normalized EV100. Equal at ISO 100. Does not measure light or represent exposure compensation. |
| ND | t, exactly one stops k / factor F / optical density D; k=log2(F) or D/log10(2); time=t*2^k (whole stops are an exact binary scaling since 2026-11-14) | Seconds. Aperture/ISO fixed; ideal filter transmission. Zero stops returns t exactly; one more stop exactly doubles. ND8 means factor 8 (3 stops); OD 3.0 means factor 1000, not exactly 10 stops. |
| Hyperfocal | f and CoC c in mm, aperture N; h0=f²/(N*c), H=h0+f | H in metres; CoC supplied explicitly to the calculation (the GUI states where its value comes from). |
| Depth of field | same plus subject distance s; near=s*h0/(h0+s-f); far=s*h0/(h0-s+f), evaluated as s*h0/(H-s) | Convert s to mm internally; outputs metres. Far and total depth Infinity when s>=H; a distance just below H gives a very large finite limit. A distance equal to the reported H (metres) is exactly the boundary. Distance must exceed f. |
| Field of view | actual sensor width/height and focal length in mm; angle=2 atan(dimension/(2f)); diagonal=hypot(width,height); optional distance d: coverage=d*dimension/f | Horizontal/vertical/diagonal degrees; rectilinear lens at infinity focus. With a distance also scene width and height in metres (2 d tan(angle/2)). |
| Motion blur (2026-11-14) | t, f (mm), distance d (m), lateral speed v (m/s), sensor width (mm); optional image width (px) and blur limit (mm/um on the sensor, or px with the image width); blur=f*v*t/d | Sensor blur (mm), share of image width (%), subject travel v*t (m); with the image width, blur in px; with a limit, longest exposure limit*d/(f*v), Infinity when v=0. Stationary camera, lateral motion, d much larger than f. |
| Storage | planned integer shots * average file size MB / 1000; cards=ceil(size GB / usable card GB) | Estimated GB and whole cards. Decimal MB=10^6 bytes, GB=10^9 bytes. No overhead/reserve/redundancy included. |
| Battery capacity | ceil(planned integer shots / rated shots per full battery) | Estimated whole batteries. Rating must reflect usage; age, temperature, screen, AF and video affect capacity. mAh alone is insufficient. |

Optical results are theoretical thin-lens approximations, ignoring diffraction, pupil magnification, breathing, cropping and distortion as applicable. Principal-plane subject distance is used. CoC is an explicit assumption, not inferred from sensor dimensions. No claim of macro or real-lens calibration.

Domain/API accepts optical mm/cm/m, distance m/mm/ft (1 ft=0.3048 m), shutter s/ms, file MB/MiB and card GB/GiB. GUI/CLI use the units displayed in `calc help`: mm, m, s, MB, GB. No units are silently guessed for API/JSON calls. All parameters require matching unit entries, unknown keys/units fail. Zero/negative/nonfinite/missing values fail except nonnegative ND stops/OD; ND factor >=1. Counts must be positive integers <=2^53-1. Overflow/underflow and unrepresentable results return errors; only mathematically justified far/total infinity is allowed.

Double precision is retained in results; display rounds half-up to at most six decimals, scientific notation below 1e-6 or at/above 1e9. Capacity ceilings use BigDecimal over normalized input values to avoid an extra card from binary roundoff. Extremely large/small inputs can be rejected even where another arbitrary-precision algorithm could represent them.

Exposure terminology checked against [Scantips EV derivation](https://www.scantips.com/lights/evchart.html). [DOFMaster equations](https://www.dofmaster.com/equations.html) documents the photographic hyperfocal/unit conventions; this implementation retains h0=H-f in the near/far equations for a consistent thin-lens infinity boundary.

## GUI / CLI usage and F05 compatibility

GUI (current, 2026-11-14): select Photography Tools > Technical Calculator and choose a calculator in the selector; results follow the inputs (see "Calculator workspace" above). The original form with a Calculate button was replaced.

Optional Load inventory > choose item > Use known values copies known camera sensor dimensions (to the shared camera), fixed-lens focal length or filter ND stops via existing F05 abstractions. Unknown values remain missing; zoom focal length, taking aperture, CoC and pixel dimensions require manual input. Inventory is loaded only on request; empty/failed reads leave manual calculation available. Reopening invalidates cached gear; stale asynchronous callbacks are discarded. No inventory mutation occurs.

CLI examples (inside the running CLI):

```text
calc exposure aperture=8 shutter=1/128 iso=400
calc nd shutter=1/125 ndStops=10
calc nd shutter=1/125 ndFactor=8
calc nd shutter=1/125 opticalDensity=3
calc dof focalLength=50 aperture=8 coc=.03 distance=3
calc hyperfocal focalLength=50 aperture=8 coc=.03
calc fov focalLength=50 sensorWidth=36 sensorHeight=24
calc fov focalLength=50 sensorWidth=36 sensorHeight=24 distance=20
calc motion shutter=1/1000 focalLength=50 distance=20 speed=10 sensorWidth=36
calc motion shutter=1/1000 focalLength=50 distance=20 speed=10 sensorWidth=36 imageWidth=6000 blurLimit=2
```

`F08` / `calc help` lists commands; arguments in [brackets] are optional. `calc hyperfocal` also prints the near and far limits when focused at the result (the depth of field operation at that distance). `calc motion` takes speed in m/s and `blurLimit` in pixels (which needs `imageWidth`); the calculation API also accepts km/h, mph, ft/s and a limit in mm or um. Optional `gear=<inventory UUID>` copies known values; explicit arguments override them, including the ND convention. Command submission confirms supplied inputs. Fractions apply only to shutter; no arbitrary expression evaluation.

## Original design -> implementation -> reason -> impact

- Six unspecified numeric schemas -> concrete documented keys/units above -> reproducible validation and meaningful results -> UC08 and the existing six enum values/methods preserved; no architectural diagram change.
- Prompt labels log2(N²/t) EV100 -> camera setting EV plus ISO-normalized EV100 -> the bare formula has no ISO term and equals EV100 at ISO 100 -> prevents confusion with sensitivity/compensation; clearly labeled in both interfaces.
- General battery/capacity estimate -> rated-shot model -> F05 has ratedShots but no voltage/usage power for a reliable mAh energy model -> manual rated shots supported; unsupported mAh-only inference rejected by omission.
- Generic calculation JSON adapter -> strict 16,384-character, six-parameter schema and deterministic execution -> Stage1 shared tool route becomes independently usable -> F10's bounded tool loop now invokes it (`calculation` is the only registered agent tool; `AgentToolLoopTest`, `F10WorkflowTest`).

## Executed verification (historical, 2026-10-26; the current record is "Verification (2026-11-14)" above)

- Baseline: 36/36 tests passed.
- Stage A: 44/44 passed and GUI_SMOKE_OK before commit `c4ae938` (pushed to origin/main). Exposure and ND already worked independently in GUI/CLI.
- Stage B final: `./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'` passed **53 tests, 0 failures/errors/skips**, plus GUI_SMOKE_OK. Full compilation covered 101 main and 10 test source files; all original F05 tests passed.
- Known answers include EV=13 and EV100=11 at f/8, 1/128, ISO400; 8.192 s for 1/125 + 10 stops; 10.466666667 m hyperfocal at 50 mm, f/8, c=.03 mm; near=2.337905237 m / far=4.185267857 m at s=3 m; horizontal FOV=39.597752709 degrees at 50 mm/36 mm sensor; 25 GB / 2 cards for 1000 x 25 MB / 16 GB; 2 batteries for 1000/600 rated shots.
- Tests cover missing/invalid values, high stops, fractional shutters, unit conversions, rounding/decimal ceiling, precision limits, hyperfocal infinity boundary, all CLI commands, all seven GUI choices, validation, optional inventory reads/overrides without mutation and retained F05 CRUD/reload tests.
- Real JavaFX acceptance uses Stage, Scene, actual controls and shared stylesheet. Rendered wide DOF and narrow ND input/result screenshots were inspected; narrow fixture asserts actual viewport width and vertically stacked fields. Native Computer Use inspection separately entered f/8, 1/128, ISO400 and confirmed the complete EV/EV100 result card and scrolling.
- A separate real CLI process executed all seven calculators and rejected aperture=0; exited successfully. No fake calculator or external provider was used.
- `tools/verify_sources.py`: PASS, 87 UML types / 267 fields / 184 operations, 83 relationships recorded, 20 original source hashes unchanged. Its historical blanket business-unimplemented footer is not current F08 status.
- Initial restricted compilation failed to access the existing JavaFX dependency cache; elevated runs succeeded. A later `clean test` was blocked by a locked `target/javafx-cache/vcruntime140_1.dll`. Only generated classes/test-classes were then removed and every source recompiled successfully, preserving the native cache and pre-existing GUI process. Final passing command above was not a successful Maven clean.

Remaining limits: Windows desktop only; other platforms/display scales and a submission demonstration video are unverified. Standalone F08 does not save results or require Gemini; F10 plans keep the calculations the agent requested. Parameters come from user input or a selected F05 item; values from a confirmed shoot reach the calculator through F10's agent tool, not through the standalone page. Optical/capacity estimates retain the assumptions above. No claim of device calibration or real-world battery life.
