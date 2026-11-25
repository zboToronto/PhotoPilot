# Stage 2 submission materials

The implementation remains in [Stage2](../README.md) in the same repository as [Stage 1](../../Stage1). This directory contains the implementation report and updated UML supplied for Stage 2, organized separately from application code and development notes.

## Implementation report

- [stage2-report.pdf](report/stage2-report.pdf) — 28-page reading copy.
- [stage2-report.docx](report/stage2-report.docx) — editable Word report.

The course instructions allow PDF/Word in the report section and specify PDF in the submission checklist; both formats are provided. Sections 1–6 cover implementation status, feature/class/method mappings, implemented patterns and updated UML, design changes, AI collaboration, and development/verification evidence. The report identifies its source baseline as commit `93f9264`; this publication does not change application code.

The supplied report's wording, diagrams and formatting are preserved. Its 18 UML hyperlink targets now point to the files below on GitHub, and the PDF was exported from that Word copy. Source-code and historical commit links retain their original revision targets.

## Updated UML

Each diagram has a PNG preview and an editable UMLet UXF source. All 18 files are unchanged copies of the supplied diagrams.

| Category | Diagram | Preview | Editable source |
| --- | --- | --- | --- |
| Class | Application architecture | [PNG](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram.png) | [UXF](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram.uxf) |
| Class | Pattern collaborations | [PNG](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram-Patterns.png) | [UXF](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram-Patterns.uxf) |
| Class | Persisted domain | [PNG](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram-Domain.png) | [UXF](uml/class-diagrams/PhotoPilot-Stage2-ClassDiagram-Domain.uxf) |
| Use case | UC01–UC14 and Stage 2 additions | [PNG](uml/use-case/PhotoPilot-Stage2-UseCaseDiagram.png) | [UXF](uml/use-case/PhotoPilot-Stage2-UseCaseDiagram.uxf) |
| Sequence | SD01 — Sessions, discovery and requirements | [PNG](uml/sequence-diagrams/PhotoPilot-Stage2-SD01-Sessions-Discovery-Requirements.png) | [UXF](uml/sequence-diagrams/PhotoPilot-Stage2-SD01-Sessions-Discovery-Requirements.uxf) |
| Sequence | SD02 — Recognition, inventory and capabilities | [PNG](uml/sequence-diagrams/PhotoPilot-Stage2-SD02-Recognition-Inventory-Capabilities.png) | [UXF](uml/sequence-diagrams/PhotoPilot-Stage2-SD02-Recognition-Inventory-Capabilities.uxf) |
| Sequence | SD03 — Matching, calculators and environment | [PNG](uml/sequence-diagrams/PhotoPilot-Stage2-SD03-Matching-Calculators-Environment.png) | [UXF](uml/sequence-diagrams/PhotoPilot-Stage2-SD03-Matching-Calculators-Environment.uxf) |
| Sequence | SD04 — Shoot planning | [PNG](uml/sequence-diagrams/PhotoPilot-Stage2-SD04-Shoot-Planning.png) | [UXF](uml/sequence-diagrams/PhotoPilot-Stage2-SD04-Shoot-Planning.uxf) |
| Sequence | SD05 — Post-shoot review | [PNG](uml/sequence-diagrams/PhotoPilot-Stage2-SD05-Post-Shoot-Review.png) | [UXF](uml/sequence-diagrams/PhotoPilot-Stage2-SD05-Post-Shoot-Review.uxf) |

## Demonstration index status

The report ends with a [Google Drive demonstration folder](https://drive.google.com/drive/folders/1k5OguYP9zQdfcHkPynt7o1nLkzSzXYSJ?usp=drive_link). It does not yet provide the course-required feature-by-feature video links or timestamps. That index and anonymous access to the videos still need verification; publishing these report/UML files does not establish that the complete Stage 2 submission requirements have been met.

Local recordings (checked 2026-11-23, Stage2OptRe1 DOC-01): the author's workspace holds one recording per feature, named by feature id. They were recorded on 2026-11-16 before the Stage2OptRe1 patch, so PP-01/PP-02/PP-03 behaviour shown in them is the earlier one. Whether the Drive folder contains these exact files, their per-file share links, timestamps inside them and anonymous access were **not** verified; no link below is invented.

| Feature | Local recording | Size | Per-file link / timestamps |
| --- | --- | --- | --- |
| F01 Photography sessions and history | `DemoVideos/F01.mp4` | 50 MB | not yet provided |
| F02 Inspiration and activity recommendation | `DemoVideos/F02.mp4` | 293 MB | not yet provided |
| F03 Natural-language requirements | `DemoVideos/F03.mp4` | 100 MB | not yet provided |
| F04 Gear recognition and import | `DemoVideos/F04.mp4` | 35 MB | not yet provided |
| F05 Gear inventory | `DemoVideos/F05.mp4` | 171 MB | not yet provided |
| F06 Capability requirement analysis | `DemoVideos/F06.mp4` | 65 MB | not yet provided |
| F07 Gear matching and compatibility | `DemoVideos/F07.mp4` | 49 MB | not yet provided |
| F08 Technical calculators | `DemoVideos/F08.mp4` | 284 MB | not yet provided |
| F09 Location, time and environment | `DemoVideos/F09.mp4` | 110 MB | not yet provided |
| F10 Plan generation and revision | `DemoVideos/F10.mp4` | 60 MB | not yet provided |
| F11 Post-shoot analysis and review | `DemoVideos/F11.mp4` | 165 MB | not yet provided |

To close DOC-01: upload or confirm each file in the Drive folder, add its share link (and timestamps where one file covers several flows) to this table and the report, and open each link once without signing in.
