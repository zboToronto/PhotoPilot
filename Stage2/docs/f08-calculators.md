# F08 technical calculator implementation

Verified 2026-10-26 (America/Toronto), Windows, JDK 21. Stage 1 report sections 2, 6.8, 8 and 9.8 plus SD03/SD04 define six operations. All six are implemented; exposure and ND are separate GUI/CLI choices under the original EXPOSURE_ND kind. No Stage1 files, F05 domain/repository code, global styles, dependencies, or unrelated workflows changed.

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
| ND | t, exactly one stops k / factor F / optical density D; k=log2(F) or D/log10(2); time=2^(log2(t)+k) | Seconds. Aperture/ISO fixed; ideal filter transmission. ND8 means factor 8 (3 stops); OD 3.0 means factor 1000, not exactly 10 stops. |
| Hyperfocal | f and CoC c in mm, aperture N; h0=f²/(N*c), H=h0+f | H in metres; user supplies CoC explicitly. |
| Depth of field | same plus subject distance s; near=s*h0/(h0+s-f); far=s*h0/(h0-s+f) | Convert s to mm internally; outputs metres. Far and total depth Infinity when s>=H. Distance must exceed f. |
| Field of view | actual sensor width/height and focal length in mm; angle=2 atan(dimension/(2f)); diagonal=hypot(width,height) | Horizontal/vertical/diagonal degrees; rectilinear lens at infinity focus. |
| Storage | planned integer shots * average file size MB / 1000; cards=ceil(size GB / usable card GB) | Estimated GB and whole cards. Decimal MB=10^6 bytes, GB=10^9 bytes. No overhead/reserve/redundancy included. |
| Battery capacity | ceil(planned integer shots / rated shots per full battery) | Estimated whole batteries. Rating must reflect usage; age, temperature, screen, AF and video affect capacity. mAh alone is insufficient. |

Optical results are theoretical thin-lens approximations, ignoring diffraction, pupil magnification, breathing, cropping and distortion as applicable. Principal-plane subject distance is used. CoC is an explicit assumption, not inferred from sensor dimensions. No claim of macro or real-lens calibration.

Domain/API accepts optical mm/cm/m, distance m/mm/ft (1 ft=0.3048 m), shutter s/ms, file MB/MiB and card GB/GiB. GUI/CLI use the units displayed in `calc help`: mm, m, s, MB, GB. No units are silently guessed for API/JSON calls. All parameters require matching unit entries, unknown keys/units fail. Zero/negative/nonfinite/missing values fail except nonnegative ND stops/OD; ND factor >=1. Counts must be positive integers <=2^53-1. Overflow/underflow and unrepresentable results return errors; only mathematically justified far/total infinity is allowed.

Double precision is retained in results; display rounds half-up to at most six decimals, scientific notation below 1e-6 or at/above 1e9. Capacity ceilings use BigDecimal over normalized input values to avoid an extra card from binary roundoff. Extremely large/small inputs can be rejected even where another arbitrary-precision algorithm could represent them.

Exposure terminology checked against [Scantips EV derivation](https://www.scantips.com/lights/evchart.html). [DOFMaster equations](https://www.dofmaster.com/equations.html) documents the photographic hyperfocal/unit conventions; this implementation retains h0=H-f in the near/far equations for a consistent thin-lens infinity boundary.

## GUI / CLI usage and F05 compatibility

Select Photography Tools > Technical Calculator, choose a calculation, enter every required value and Calculate. Invalid input replaces the result with feedback; changing fields hides stale results. Forms wrap to one column at narrow widths; content and navigation retain independent scrolling.

Optional Load inventory > choose item > Use known values copies known camera sensor dimensions, fixed-lens focal length, filter ND stops, storage capacity or rated battery shots via existing F05 abstractions. The user reviews before Calculate. Unknown values remain missing; zoom focal length, taking aperture and CoC require manual input. Inventory is loaded only on request; empty/failed reads leave manual calculation available. Reopening invalidates cached gear; stale asynchronous callbacks are discarded. No inventory mutation occurs.

CLI examples (inside the running CLI):

```text
calc exposure aperture=8 shutter=1/128 iso=400
calc nd shutter=1/125 ndStops=10
calc nd shutter=1/125 ndFactor=8
calc nd shutter=1/125 opticalDensity=3
calc dof focalLength=50 aperture=8 coc=.03 distance=3
calc hyperfocal focalLength=50 aperture=8 coc=.03
calc fov focalLength=50 sensorWidth=36 sensorHeight=24
calc storage shotCount=1000 fileSize=25 cardCapacity=16
calc battery shotCount=1000 ratedShots=600
```

`F08` / `calc help` lists commands. Optional `gear=<inventory UUID>` copies known values; explicit arguments override them, including the ND convention. Command submission confirms supplied inputs. Fractions apply only to shutter; no arbitrary expression evaluation.

## Original design -> implementation -> reason -> impact

- Six unspecified numeric schemas -> concrete documented keys/units above -> reproducible validation and meaningful results -> UC08 and the existing six enum values/methods preserved; no architectural diagram change.
- Prompt labels log2(N²/t) EV100 -> camera setting EV plus ISO-normalized EV100 -> the bare formula has no ISO term and equals EV100 at ISO 100 -> prevents confusion with sensitivity/compensation; clearly labeled in both interfaces.
- General battery/capacity estimate -> rated-shot model -> F05 has ratedShots but no voltage/usage power for a reliable mAh energy model -> manual rated shots supported; unsupported mAh-only inference rejected by omission.
- Generic calculation JSON adapter -> strict 16,384-character, six-parameter schema and deterministic execution -> Stage1 shared tool route becomes independently usable -> F10 tool registry/LLM loop remains scaffolded and is not claimed complete.

## Executed verification

- Baseline: 36/36 tests passed.
- Stage A: 44/44 passed and GUI_SMOKE_OK before commit `c4ae938` (pushed to origin/main). Exposure and ND already worked independently in GUI/CLI.
- Stage B final: `./mvnw.cmd -B -o test javafx:run '-Djavafx.args=--smoke-test'` passed **53 tests, 0 failures/errors/skips**, plus GUI_SMOKE_OK. Full compilation covered 101 main and 10 test source files; all original F05 tests passed.
- Known answers include EV=13 and EV100=11 at f/8, 1/128, ISO400; 8.192 s for 1/125 + 10 stops; 10.466666667 m hyperfocal at 50 mm, f/8, c=.03 mm; near=2.337905237 m / far=4.185267857 m at s=3 m; horizontal FOV=39.597752709 degrees at 50 mm/36 mm sensor; 25 GB / 2 cards for 1000 x 25 MB / 16 GB; 2 batteries for 1000/600 rated shots.
- Tests cover missing/invalid values, high stops, fractional shutters, unit conversions, rounding/decimal ceiling, precision limits, hyperfocal infinity boundary, all CLI commands, all seven GUI choices, validation, optional inventory reads/overrides without mutation and retained F05 CRUD/reload tests.
- Real JavaFX acceptance uses Stage, Scene, actual controls and shared stylesheet. Rendered wide DOF and narrow ND input/result screenshots were inspected; narrow fixture asserts actual viewport width and vertically stacked fields. Native Computer Use inspection separately entered f/8, 1/128, ISO400 and confirmed the complete EV/EV100 result card and scrolling.
- A separate real CLI process executed all seven calculators and rejected aperture=0; exited successfully. No fake calculator or external provider was used.
- `tools/verify_sources.py`: PASS, 87 UML types / 267 fields / 184 operations, 83 relationships recorded, 20 original source hashes unchanged. Its historical blanket business-unimplemented footer is not current F08 status.
- Initial restricted compilation failed to access the existing JavaFX dependency cache; elevated runs succeeded. A later `clean test` was blocked by a locked `target/javafx-cache/vcruntime140_1.dll`. Only generated classes/test-classes were then removed and every source recompiled successfully, preserving the native cache and pre-existing GUI process. Final passing command above was not a successful Maven clean.

Remaining limits: Windows desktop only; other platforms/display scales and a submission demonstration video are unverified. F10 agent orchestration and session persistence remain separate incomplete features; standalone F08 does not save results or require Gemini. Optical/capacity estimates retain the assumptions above. No claim of device calibration or real-world battery life.
