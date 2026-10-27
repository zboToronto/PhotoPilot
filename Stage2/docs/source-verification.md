# Verified local design baseline

> Historical record of the original scaffolding task before Git integration. Statements about no Git operations or publication apply to that task only.

Inspection date: October 26, 2026 (Toronto). Project output was originally in workspace-root `Stage2/` and is now in `ProjectGit/Stage2/`. The course PDF was originally under `Project/Stage2/`; before repository relocation it was already at `Project/Project-Stage2-Instruction.pdf`, with the same SHA-256. Course materials were read only. No Git operations were run.

## Selected report and evidence

Selected `../../Project/Stage1/Stage1.docx`, internal revision 46, last modified by the report author at 2026-10-10 04:56 UTC, together with its corresponding 27-page export `../../Project/Stage1/Stage1PPP.pdf`, created at 2026-10-10 00:56:55 -04:00. These are the latest complete local report artifacts found, not a selection based solely on filenames.

The Word report and PDF contain sections 1–9: overview, F01–F11, diagram references, five pattern explanations, UC01–UC14, sequence references, traceability, and implementation explanations. A normalized full-text comparison found only generated numbering and repeated table-header additions in the PDF; no substantive text replacement/deletion. The Word document has no tracked insertions/deletions and no embedded media: sections 3, 5, and 7 explicitly reference the separately supplied UML files. The late F06 confirmation and F10 approval safeguards in the narrative agree with the current class/sequence source. `Section8.docx` contains only a traceability table, not an alternative complete report. No `Stage1(2).docx` or other complete report version was found in the workspace.

This establishes the local design baseline. It does not prove submission/approval status on a course website; no external repository or submission platform was consulted.

Paths in the following table and source-hash manifest are relative to the EECS3311 workspace, one level above the ProjectGit repository. Course materials remain outside Git.

## Sources read

| Local source (relative to workspace) | Read/used scope |
| --- | --- |
| `Project/Stage1/Stage1.docx` | Full document XML/text and metadata; section 8 table supplies feature mapping |
| `Project/Stage1/Stage1PPP.pdf` | Full 27-page report text; corresponding exported design version |
| `Project/Stage1/PhotoPilot-ClassDiagram.uxf` | All classifier definitions, attributes, signatures, notes and relations; 87 unique classifiers; 83 relations |
| `Project/Stage1/PhotoPilot-UseCaseDiagram.uxf` | UC01–UC14, actors, associations; UC07/UC10/UC11 include UC14 |
| `Project/Stage1/PhotoPilot-SD01.uxf` | F01–F03 interactions, confirmation and explicit interface gaps |
| `Project/Stage1/PhotoPilot-SD02.uxf` | F04–F06 privacy, import, inventory, profile confirmation and persistence |
| `Project/Stage1/PhotoPilot-SD03.uxf` | F07–F09 deterministic compatibility/calculation, ranking and environment boundaries |
| `Project/Stage1/PhotoPilot-SD04.uxf` | F10 tool loop, validated previews, commands, binding/revision/state/approval checks |
| `Project/Stage1/PhotoPilot-SD05.uxf` | F11 distinct statistics/review/save requests and atomic state change |
| `Project/Project-Stage2-Instruction.pdf` | All 5 pages; final implementation, traceability, reporting, videos, weekly code activity, key protection |
| `Project/Project-Instruction.pdf` | All 17 pages; design, implementation, deterministic testing and KUMA boundaries |
| `Project/Stage1/stage1.pdf` | Official course Stage 1 requirements, pp. 1–7; sample project appendix is not PhotoPilot design authority |
| `Slides/EECS3311-03-UML.pdf` | pp. 15–26: visibility, omitted void returns, abstract/interface notation, relationships and multiplicities |
| `Slides/EECS3311-04-UML-II.pdf` | Introductory diagram conventions; no architecture substitutions |
| `Slides/EECS3311-07-Structural Patterns-updated.pdf` | Adapter explanation, pp. 6–15 |
| `Slides/EECS3311-08-Behavioral Patterns.pdf` | State explanation, pp. 34–35 |

Seven corresponding PNGs are available (ClassDiagram, UseCaseDiagram, SD01–SD05). The class PNG was inspected as an overview; exact signatures and relation endpoints were read from editable UXF, not inferred from an unreadable scaled screenshot. No re-rendering or changes to Stage 1 were performed. Original source fingerprints are in `source-hashes.json`.

No essential source is missing or inaccessible. AI memory and prior conversations are not architectural evidence. Public Maven, JavaFX and JDK documentation was used only for tool bootstrap/dependency configuration, never to replace the local design.

## Preserved gaps and implementation implications

| Evidence | Issue | Smallest future correction / current treatment |
| --- | --- | --- |
| Report §8 F01; SD01 ARCHITECTURE LIMITS | Session location/notes exist but lack a mutation route | Preserve fields and specified service signatures; future approved narrow mutation API is needed. No invented user flow. |
| Report §8 F02, §9.2; SD01 | Discovery select/save/dismiss exist but Facade exposes no route | Keep methods on DiscoveryService. A future approved discovery accessor/forwarder is the smallest proposal; not added now. |
| Report §8 F07, §9.7; SD03 | No selected-loadout attachment/persistence operation | Preserve matching contracts and Loadout; selected-loadout workflow remains blocked pending a narrow approved API decision. |
| Report §9.7; SD03 | Strategy selection wiring unspecified | Use the existing constructor/setRanking contract; bootstrap preference selects one of the two approved strategies. No new ranking strategy. |
| Report §4.4 versus SD04 approval | PlanEditor.applyApproved(base, change) exists; approval of a reviewed preview must not reapply a command | Preserve both signatures. applyApproved remains unimplemented and is not invoked by bootstrap. Future approval must follow SD04 validation and atomic-save sequence. |
| UML run(task, context, Class<T>) and SD01/SD03 List.class | Erasure cannot capture ActivitySuggestion/Loadout members | Preserve generic Java method; future schema/member checks required. No unchecked fake implementation. |
| SD04 PlanCommand.class | Interface needs concrete subtype decoding/allowlist | Preserve interface and three commands; defer deserializer until agent behavior is implemented. |
| UML package-private ShootPlan mutations / SanitizedImage constructor | Naive separate Java packages would make designed calls inaccessible | Co-locate receiver and commands; co-locate privacy helper and approved-image type. Keep package-private visibility. |
| UML ownership/multiplicities | Java fields alone cannot enforce deep copy, aggregate ownership, 1..* or approved immutability | Preserve typed fields and documented ownership edges. Mutation/copy/persistence behavior remains unimplemented; constructors are storage scaffolding, not domain validation. |
| UML provider/EXIF notes, report §4.3 | Provider brands/protocols/library and concrete DeviceLocationProvider are unspecified | Preserve interfaces/adapters; absent optional device adapter, disabled endpoints, no selected EXIF dependency. |

The Stage 2 course PDF permits documented changes, while the user requires prior approval for substantial changes. The stricter task boundary governs this work; none was introduced. The course requires working final features, patterns, AI, videos and Git activity; this local scaffolding task intentionally does not claim to satisfy those final deliverables.
