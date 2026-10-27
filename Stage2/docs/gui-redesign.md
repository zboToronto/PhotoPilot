# PhotoPilot GUI redesign

The existing application uses JavaFX 21.0.6, not Swing. The redesign retains JavaFX, Maven, JDK 21, the shared application layer, and the CLI. No dependencies were added and `pom.xml` is unchanged. FlatLaf is a Swing look and feel and is not needed here.

## Changed presentation files

| File | Responsibility |
| --- | --- |
| `src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java` | Existing entry point, lifecycle, stylesheet loading, GUI callbacks, background brief processing, graphical smoke mode |
| `src/main/java/com/photopilot/ui/gui/PhotoPilotWorkspace.java` | Header, three sidebar groups, selected feature, independent scroll areas, footer and keyboard navigation |
| `src/main/java/com/photopilot/ui/gui/FeaturePane.java` | Feature description, status, disabled workflow placeholder, available status inspection and feedback |
| `src/main/java/com/photopilot/ui/gui/FeaturePresentation.java` | Concise presentation names, original identifiers, descriptions and catalog status interpretation |
| `src/main/resources/com/photopilot/ui/gui/photopilot.css` | Light palette, typography, spacing, subtle borders, selection and keyboard focus styles |
| `src/test/java/com/photopilot/ui/gui/FeaturePresentationTest.java` | Complete ID coverage, grouping and conservative status interpretation |
| `README.md` | Updated GUI behavior, keyboard guidance and test documentation |

This document records the implementation and verification. Existing unrelated working-tree edits were retained.

## Behavior and extension points

- Workspace: F01 Sessions & History, F02 Inspiration, F03 Requirement Parsing, F10 Shoot Planning.
- Equipment: F04 AI Gear Import, F05 Gear Inventory, F06 Capability Analysis, F07 Compatibility Check.
- Photography Tools: F08 Technical Calculator, F09 Environment Planning, F11 Post-Shoot Analysis.
- Status comes from the existing `FeatureCatalog.lines()` output. The GUI translates its scaffold marker to **Scaffolded**. Missing or unrecognized markers become **Unavailable**. The CLI catalog and its output are unchanged.
- Selecting a feature resets its feedback and returns its content to the top. Repeated selection cannot leave the workspace without a selected item.
- The current shared application has no available business workflows. The disabled workflow control and empty state make that explicit. **Check status** preserves the original unavailable-operation feedback without calling a business operation.
- Up/Down and Home/End navigate across groups. Tab moves between focusable controls; Space activates buttons. Navigation scrolls to keyboard selections. Standard window controls handle closing.
- `FeaturePane` is the place to connect future feature forms and results. A catalog status alone never enables a nonexistent GUI workflow. When implementing a feature, connect the real controls and action handler as well as updating its application status.
- UI changes occur on the JavaFX Application Thread. The existing `submitBrief` callback uses a background `Task`; completion is returned to the UI thread and ignored if navigation changed meanwhile. Workers are interrupted during shutdown. Future I/O handlers should follow the same pattern with appropriate service timeouts and cancellation.

The default scene is 1120 × 760, with a minimum window size of 880 × 600. Content uses a bounded reading width and wraps text. Smaller windows have independently scrollable navigation and content; this intentionally allows vertical scrolling instead of hiding actions or compressing text.

## Verification on Windows, JDK 21

- `mvnw.cmd clean test`: successful full compilation; 6 tests passed, 0 failures/errors/skips.
- `mvnw.cmd test javafx:run -Djavafx.args=--smoke-test`: successful graphical launch, all F01–F11 selections and status feedback exercised, workflows disabled, repeat selection retained, and navigation exercised after requesting the minimum size. The smoke check also verifies content scroll reset on navigation. Smoke failures propagate through application shutdown to the process result.
- Real desktop inspection: reviewed the main window, F10 selection, keyboard End to F11, Tab to Check status, and Space activation showing the F11 unavailable result.
- An ignored temporary layout harness loaded the actual workspace and stylesheet in an 880 × 600 window. F09's heading, sidebar and empty state were inspected; scrolling exposed the full action and feedback area without horizontal clipping.
- Noninteractive CLI: `help status F01 F02 F03 F04 F05 F06 F07 F08 F09 F10 F11 exit` completed successfully with all original unavailable responses. The existing CLI unit test also covers unknown commands and interactive exit.
- `tools/verify_sources.py`: passed for 87 UML types, 267 fields, 184 operations and 83 recorded relationships. All 20 original design-source hashes were unchanged.

The restricted execution sandbox initially denied the existing JavaFX dependency cache; verification succeeded using the same local JDK and Maven configuration outside that restriction. The system Python alias was unavailable, so the read-only structural audit used the bundled Python runtime.

## Limits

All eleven business features remain scaffolded. This change implements the GUI presentation and preserves honest unavailable states; it does not implement persistence, AI calls, calculations or other planned workflows. Windows was tested; macOS/Linux rendering, other display scales, and screen-reader speech were not tested. The graphical smoke test checks control behavior, not every possible layout or future asynchronous service implementation.

Implementation references: [JavaFX 21 CSS](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/doc-files/cssref.html) and [JavaFX Task threading](https://openjfx.io/javadoc/21/javafx.graphics/javafx/concurrent/Task.html).
