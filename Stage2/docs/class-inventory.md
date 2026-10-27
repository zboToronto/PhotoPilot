# Class and relationship inventory

87 unique Stage 1 classifiers, excluding repeated reference boxes. The frozen JSON records exact original fields, visibility and operation signatures, plus all 83 edges. `tools/verify_sources.py` audits Java declarations against that baseline and rechecks original-source hashes. This is structural verification, not runtime verification of domain behavior.

## com.photopilot.agent

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PhotoPilotAgent (class) | 13 | 4 | `run()`, `selectTool()`, `validateOutput()` | [PhotoPilotAgent.java](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java) |
| ToolRegistry (class) | 16 | 1 | `execute()`, `schemas()`, `register()` | [ToolRegistry.java](../src/main/java/com/photopilot/agent/ToolRegistry.java) |
| AgentTool (interface) | 17 | 0 | `name()`, `inputSchema()`, `execute()` | [AgentTool.java](../src/main/java/com/photopilot/agent/AgentTool.java) |
| HistoryRetriever (class) | 18 | 1 | `retrieve()`, `contextFor()` | [HistoryRetriever.java](../src/main/java/com/photopilot/agent/HistoryRetriever.java) |
| AgentContext (class) | 19 | 5 | `appendToolResult()` | [AgentContext.java](../src/main/java/com/photopilot/agent/AgentContext.java) |
| ModelReply (class) | 20 | 3 | `requiresTool()` | [ModelReply.java](../src/main/java/com/photopilot/agent/ModelReply.java) |
| ToolCall (class) | 21 | 2 | `validateAgainst()` | [ToolCall.java](../src/main/java/com/photopilot/agent/ToolCall.java) |
| CalculationTool (class) | 94 | 1 | `name()`, `inputSchema()`, `execute()` | [CalculationTool.java](../src/main/java/com/photopilot/agent/CalculationTool.java) |
| GearMatchingTool (class) | 95 | 2 | `name()`, `inputSchema()`, `execute()` | [GearMatchingTool.java](../src/main/java/com/photopilot/agent/GearMatchingTool.java) |
| EnvironmentTool (class) | 96 | 1 | `name()`, `inputSchema()`, `execute()` | [EnvironmentTool.java](../src/main/java/com/photopilot/agent/EnvironmentTool.java) |
| SpecificationTool (class) | 97 | 1 | `name()`, `inputSchema()`, `execute()` | [SpecificationTool.java](../src/main/java/com/photopilot/agent/SpecificationTool.java) |

## com.photopilot.application

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PhotoPilotFacade (class) | 3 | 6 | `createSession()`, `suggest()`, `parse()`, `recognize()`, `saveGear()`, `analyzeNeeds()`, `matchGear()`, `calculate()`, `assessEnvironment()`, `generatePlan()`, `proposeChange()`, `analyze()`, `review()`, `sessions()`, `inventory()`, `planning()`, `reviews()` | [PhotoPilotFacade.java](../src/main/java/com/photopilot/application/PhotoPilotFacade.java) |

## com.photopilot.command

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PlanEditor (class) | 68 | 1 | `preview()`, `applyApproved()` | [PlanEditor.java](../src/main/java/com/photopilot/command/PlanEditor.java) |
| PlanCommand (interface) | 69 | 0 | `applyTo()` | [PlanCommand.java](../src/main/java/com/photopilot/command/PlanCommand.java) |
| ReplaceLoadoutCommand (class) | 70 | 1 | `applyTo()` | [ReplaceLoadoutCommand.java](../src/main/java/com/photopilot/command/ReplaceLoadoutCommand.java) |
| ReviseScheduleCommand (class) | 71 | 1 | `applyTo()` | [ReviseScheduleCommand.java](../src/main/java/com/photopilot/command/ReviseScheduleCommand.java) |
| ReviseStrategyCommand (class) | 72 | 4 | `applyTo()` | [ReviseStrategyCommand.java](../src/main/java/com/photopilot/command/ReviseStrategyCommand.java) |
| ShootPlan (class) | 73 | 12 | `copyForRevision()`, `replaceLoadout()`, `reviseSchedule()`, `reviseStrategy()` | [ShootPlan.java](../src/main/java/com/photopilot/command/ShootPlan.java) |

## com.photopilot.common

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| Result (class) | 10 | 3 | `isSuccess()` | [Result.java](../src/main/java/com/photopilot/common/Result.java) |

## com.photopilot.integration

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| LLMClient (interface) | 14 | 0 | `complete()` | [LLMClient.java](../src/main/java/com/photopilot/integration/LLMClient.java) |
| GeminiAdapter (class) | 15 | 4 | `complete()` | [GeminiAdapter.java](../src/main/java/com/photopilot/integration/GeminiAdapter.java) |
| EquipmentSpecificationProvider (interface) | 43 | 0 | `lookup()` | [EquipmentSpecificationProvider.java](../src/main/java/com/photopilot/integration/EquipmentSpecificationProvider.java) |
| EnvironmentProvider (interface) | 80 | 0 | `fetch()` | [EnvironmentProvider.java](../src/main/java/com/photopilot/integration/EnvironmentProvider.java) |
| EnvironmentApiAdapter (class) | 81 | 3 | `fetch()` | [EnvironmentApiAdapter.java](../src/main/java/com/photopilot/integration/EnvironmentApiAdapter.java) |
| DeviceLocationProvider (interface) | 87 | 0 | `current()` | [DeviceLocationProvider.java](../src/main/java/com/photopilot/integration/DeviceLocationProvider.java) |
| EquipmentCatalogAdapter (class) | 98 | 2 | `lookup()` | [EquipmentCatalogAdapter.java](../src/main/java/com/photopilot/integration/EquipmentCatalogAdapter.java) |
| MetadataExtractor (interface) | 101 | 0 | `supports()`, `extract()` | [MetadataExtractor.java](../src/main/java/com/photopilot/integration/MetadataExtractor.java) |
| ExifLibraryAdapter (class) | 102 | 1 | `supports()`, `extract()` | [ExifLibraryAdapter.java](../src/main/java/com/photopilot/integration/ExifLibraryAdapter.java) |

## com.photopilot.model

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PhotographySession (class) | 24 | 14 | `apply()`, `attachRequirement()`, `attachProfile()`, `attachStatistics()`, `appendApprovedPlan()`, `appendReview()` | [PhotographySession.java](../src/main/java/com/photopilot/model/PhotographySession.java) |
| ShootRequirement (class) | 36 | 14 | `validate()`, `confirm()` | [ShootRequirement.java](../src/main/java/com/photopilot/model/ShootRequirement.java) |
| EquipmentRequirementProfile (class) | 37 | 2 | `validate()`, `confirm()` | [EquipmentRequirementProfile.java](../src/main/java/com/photopilot/model/EquipmentRequirementProfile.java) |
| GearImportDraft (class) | 39 | 4 | `correct()`, `confirm()` | [GearImportDraft.java](../src/main/java/com/photopilot/model/GearImportDraft.java) |
| CapabilityNeed (class) | 40 | 7 | `validate()` | [CapabilityNeed.java](../src/main/java/com/photopilot/model/CapabilityNeed.java) |
| GearInventory (class) | 46 | 1 | `add()`, `update()`, `search()`, `remove()` | [GearInventory.java](../src/main/java/com/photopilot/model/GearInventory.java) |
| Equipment (abstract class) | 47 | 8 | `validate()`, `snapshot()` | [Equipment.java](../src/main/java/com/photopilot/model/Equipment.java) |
| Camera (class) | 48 | 9 |  | [Camera.java](../src/main/java/com/photopilot/model/Camera.java) |
| Lens (class) | 49 | 7 |  | [Lens.java](../src/main/java/com/photopilot/model/Lens.java) |
| Filter (class) | 50 | 2 |  | [Filter.java](../src/main/java/com/photopilot/model/Filter.java) |
| Battery (class) | 51 | 3 |  | [Battery.java](../src/main/java/com/photopilot/model/Battery.java) |
| StorageMedia (class) | 52 | 3 |  | [StorageMedia.java](../src/main/java/com/photopilot/model/StorageMedia.java) |
| CompatibilityReport (class) | 59 | 4 | `isUsable()` | [CompatibilityReport.java](../src/main/java/com/photopilot/model/CompatibilityReport.java) |
| Loadout (class) | 63 | 6 | `totalWeightGrams()` | [Loadout.java](../src/main/java/com/photopilot/model/Loadout.java) |
| GearSnapshot (class) | 64 | 9 |  | [GearSnapshot.java](../src/main/java/com/photopilot/model/GearSnapshot.java) |
| ShootingWindow (class) | 75 | 5 | `validate()` | [ShootingWindow.java](../src/main/java/com/photopilot/model/ShootingWindow.java) |
| EnvironmentAssessment (class) | 76 | 3 |  | [EnvironmentAssessment.java](../src/main/java/com/photopilot/model/EnvironmentAssessment.java) |
| EnvironmentContext (class) | 83 | 7 | `staleFields()` | [EnvironmentContext.java](../src/main/java/com/photopilot/model/EnvironmentContext.java) |
| GeoLocation (class) | 84 | 6 | `validate()` | [GeoLocation.java](../src/main/java/com/photopilot/model/GeoLocation.java) |
| DiscoveryContext (class) | 85 | 8 |  | [DiscoveryContext.java](../src/main/java/com/photopilot/model/DiscoveryContext.java) |
| ActivitySuggestion (class) | 86 | 6 | `toRequirement()` | [ActivitySuggestion.java](../src/main/java/com/photopilot/model/ActivitySuggestion.java) |
| CalculationInput (class) | 91 | 3 | `validate()` | [CalculationInput.java](../src/main/java/com/photopilot/model/CalculationInput.java) |
| CalculationResult (class) | 92 | 5 |  | [CalculationResult.java](../src/main/java/com/photopilot/model/CalculationResult.java) |
| CalculationKind (enum) | 93 | 0 |  | [CalculationKind.java](../src/main/java/com/photopilot/model/CalculationKind.java) |
| ExifRecord (class) | 104 | 9 | `validate()` | [ExifRecord.java](../src/main/java/com/photopilot/model/ExifRecord.java) |
| ExifStatistics (class) | 105 | 10 |  | [ExifStatistics.java](../src/main/java/com/photopilot/model/ExifStatistics.java) |
| PostShootReview (class) | 106 | 8 |  | [PostShootReview.java](../src/main/java/com/photopilot/model/PostShootReview.java) |

## com.photopilot.repository

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| SessionRepository (interface) | 31 | 0 | `save()`, `find()`, `search()`, `referencingGear()` | [SessionRepository.java](../src/main/java/com/photopilot/repository/SessionRepository.java) |
| JsonSessionRepository (class) | 32 | 1 | `save()`, `find()`, `search()`, `referencingGear()` | [JsonSessionRepository.java](../src/main/java/com/photopilot/repository/JsonSessionRepository.java) |
| InventoryRepository (interface) | 53 | 0 | `load()`, `save()` | [InventoryRepository.java](../src/main/java/com/photopilot/repository/InventoryRepository.java) |
| JsonInventoryRepository (class) | 54 | 1 | `load()`, `save()` | [JsonInventoryRepository.java](../src/main/java/com/photopilot/repository/JsonInventoryRepository.java) |

## com.photopilot.service

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| SessionService (class) | 5 | 1 | `create()`, `save()`, `open()`, `search()`, `transition()` | [SessionService.java](../src/main/java/com/photopilot/service/SessionService.java) |
| PlanningService (class) | 6 | 9 | `parse()`, `confirm()`, `analyzeNeeds()`, `confirmProfile()`, `matchGear()`, `assessEnvironment()`, `generate()`, `proposeChange()`, `approve()` | [PlanningService.java](../src/main/java/com/photopilot/service/PlanningService.java) |
| InventoryService (class) | 7 | 3 | `list()`, `save()`, `duplicates()`, `affectedSessions()`, `remove()`, `recognize()`, `confirm()` | [InventoryService.java](../src/main/java/com/photopilot/service/InventoryService.java) |
| DiscoveryService (class) | 8 | 4 | `suggest()`, `save()`, `select()`, `dismiss()` | [DiscoveryService.java](../src/main/java/com/photopilot/service/DiscoveryService.java) |
| ReviewService (class) | 9 | 4 | `analyze()`, `review()`, `save()` | [ReviewService.java](../src/main/java/com/photopilot/service/ReviewService.java) |
| RequirementService (class) | 35 | 1 | `parse()`, `clarify()`, `confirm()`, `deriveProfile()`, `validateProfile()` | [RequirementService.java](../src/main/java/com/photopilot/service/RequirementService.java) |
| GearRecognitionService (class) | 38 | 2 | `recognize()`, `verify()`, `confirm()` | [GearRecognitionService.java](../src/main/java/com/photopilot/service/GearRecognitionService.java) |
| GearMatchingService (class) | 57 | 2 | `match()`, `setRanking()`, `compatibleCandidates()` | [GearMatchingService.java](../src/main/java/com/photopilot/service/GearMatchingService.java) |
| EnvironmentService (class) | 79 | 4 | `retrieve()`, `assess()` | [EnvironmentService.java](../src/main/java/com/photopilot/service/EnvironmentService.java) |
| LocationService (class) | 82 | 1 | `manual()`, `current()` | [LocationService.java](../src/main/java/com/photopilot/service/LocationService.java) |
| TechnicalCalculator (class) | 90 | 0 | `calculate()`, `depthOfField()`, `hyperfocal()`, `fieldOfView()`, `exposureAndND()`, `storage()`, `batteryCapacity()` | [TechnicalCalculator.java](../src/main/java/com/photopilot/service/TechnicalCalculator.java) |
| StatisticsCalculator (class) | 103 | 0 | `summarize()`, `compare()` | [StatisticsCalculator.java](../src/main/java/com/photopilot/service/StatisticsCalculator.java) |

## com.photopilot.state

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| SessionState (interface) | 25 | 0 | `next()`, `canEditPlan()` | [SessionState.java](../src/main/java/com/photopilot/state/SessionState.java) |
| DraftState (class) | 26 | 0 | `next()`, `canEditPlan()` | [DraftState.java](../src/main/java/com/photopilot/state/DraftState.java) |
| PlannedState (class) | 27 | 0 | `next()`, `canEditPlan()` | [PlannedState.java](../src/main/java/com/photopilot/state/PlannedState.java) |
| CompletedState (class) | 28 | 0 | `next()`, `canEditPlan()` | [CompletedState.java](../src/main/java/com/photopilot/state/CompletedState.java) |
| ReviewedState (class) | 29 | 0 | `next()`, `canEditPlan()` | [ReviewedState.java](../src/main/java/com/photopilot/state/ReviewedState.java) |
| SessionEvent (enum) | 30 | 0 |  | [SessionEvent.java](../src/main/java/com/photopilot/state/SessionEvent.java) |

## com.photopilot.strategy

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| LoadoutRankingStrategy (interface) | 60 | 0 | `rank()` | [LoadoutRankingStrategy.java](../src/main/java/com/photopilot/strategy/LoadoutRankingStrategy.java) |
| PortabilityRanking (class) | 61 | 1 | `rank()` | [PortabilityRanking.java](../src/main/java/com/photopilot/strategy/PortabilityRanking.java) |
| CoverageRanking (class) | 62 | 1 | `rank()` | [CoverageRanking.java](../src/main/java/com/photopilot/strategy/CoverageRanking.java) |

## com.photopilot.ui.cli

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PhotoPilotCLI (class) | 4 | 2 | `run()`, `execute()` | [PhotoPilotCLI.java](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java) |

## com.photopilot.ui.gui

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| PhotoPilotGUI (class) | 2 | 3 | `showSession()`, `submitBrief()`, `showFailure()` | [PhotoPilotGUI.java](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java) |

## com.photopilot.ui.privacy

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| ImagePrivacyBoundary (class) | 41 | 0 | `approveRedacted()`, `discard()` | [ImagePrivacyBoundary.java](../src/main/java/com/photopilot/ui/privacy/ImagePrivacyBoundary.java) |
| SanitizedImage (class) | 42 | 3 | `SanitizedImage()`, `clear()` | [SanitizedImage.java](../src/main/java/com/photopilot/ui/privacy/SanitizedImage.java) |

## com.photopilot.validation

| Type | UML element (zero-based) | Fields | Operations | Source file |
| --- | --- | --- | --- | --- |
| CompatibilityValidator (class) | 58 | 0 | `validate()`, `matchNeeds()` | [CompatibilityValidator.java](../src/main/java/com/photopilot/validation/CompatibilityValidator.java) |
| FeasibilityValidator (class) | 65 | 0 | `validate()`, `checkTiming()` | [FeasibilityValidator.java](../src/main/java/com/photopilot/validation/FeasibilityValidator.java) |
| PlanValidator (class) | 74 | 2 | `validate()`, `validateChange()` | [PlanValidator.java](../src/main/java/com/photopilot/validation/PlanValidator.java) |

## UML relationships

Notation is copied from the verified UMLet source: `lt=->` association, `lt=.>` dependency, `lt=.>>` realization, `lt=->>` generalization, `lt=<<<<-` aggregation, `lt=<<<<<-` composition; multiplicity 1 is the owner/source end, 2 the target end. Java constructors/fields retain wiring. Runtime ownership and multiplicity enforcement remain deferred.

| Source | Target | Original notation |
| --- | --- | --- |
| PhotoPilotGUI | PhotoPilotFacade | `lt=->` |
| PhotoPilotCLI | PhotoPilotFacade | `lt=->` |
| PhotoPilotFacade | SessionService | `lt=->` |
| PhotoPilotFacade | PlanningService | `lt=->` |
| PhotoPilotFacade | InventoryService | `lt=->` |
| PhotoPilotFacade | DiscoveryService | `lt=->` |
| PhotoPilotFacade | ReviewService | `lt=->` |
| PhotoPilotAgent | LLMClient | `lt=->` |
| GeminiAdapter | LLMClient | `lt=.>>` |
| PhotoPilotAgent | ToolRegistry | `lt=->` |
| PhotoPilotAgent | HistoryRetriever | `lt=->` |
| ToolRegistry | AgentTool | `lt=<<<<-`; `m1=1`; `m2=0..*` |
| PhotoPilotAgent | AgentContext | `lt=.>` |
| ModelReply | ToolCall | `lt=<<<<<-`; `m1=1`; `m2=0..*` |
| PhotographySession | SessionState | `lt=->`; `m1=0..*`; `m2=1` |
| DraftState | SessionState | `lt=.>>` |
| PlannedState | SessionState | `lt=.>>` |
| CompletedState | SessionState | `lt=.>>` |
| ReviewedState | SessionState | `lt=.>>` |
| JsonSessionRepository | SessionRepository | `lt=.>>` |
| SessionRepository | PhotographySession | `lt=.>` |
| RequirementService | ShootRequirement | `lt=.>` |
| RequirementService | EquipmentRequirementProfile | `lt=.>` |
| EquipmentRequirementProfile | CapabilityNeed | `lt=<<<<<-`; `m1=1`; `m2=1..*` |
| GearRecognitionService | EquipmentSpecificationProvider | `lt=->` |
| GearRecognitionService | GearImportDraft | `lt=.>` |
| ImagePrivacyBoundary | SanitizedImage | `lt=.>` |
| GearRecognitionService | SanitizedImage | `lt=.>` |
| GearInventory | Equipment | `lt=<<<<-`; `m1=0..1`; `m2=0..*` |
| Camera | Equipment | `lt=->>` |
| Lens | Equipment | `lt=->>` |
| Filter | Equipment | `lt=->>` |
| Battery | Equipment | `lt=->>` |
| StorageMedia | Equipment | `lt=->>` |
| JsonInventoryRepository | InventoryRepository | `lt=.>>` |
| InventoryRepository | GearInventory | `lt=.>` |
| GearMatchingService | CompatibilityValidator | `lt=->` |
| CompatibilityValidator | CompatibilityReport | `lt=.>` |
| GearMatchingService | LoadoutRankingStrategy | `lt=->` |
| PortabilityRanking | LoadoutRankingStrategy | `lt=.>>` |
| CoverageRanking | LoadoutRankingStrategy | `lt=.>>` |
| Loadout | GearSnapshot | `lt=<<<<<-`; `m1=1`; `m2=1..*` |
| Loadout | CompatibilityReport | `lt=<<<<<-`; `m1=1`; `m2=1` |
| PlanEditor | PlanCommand | `lt=.>` |
| PlanEditor | PlanValidator | `lt=->` |
| ReplaceLoadoutCommand | PlanCommand | `lt=.>>` |
| ReplaceLoadoutCommand | ShootPlan | `lt=.>` |
| ReviseScheduleCommand | PlanCommand | `lt=.>>` |
| ReviseScheduleCommand | ShootPlan | `lt=.>` |
| ReviseStrategyCommand | PlanCommand | `lt=.>>` |
| ReviseStrategyCommand | ShootPlan | `lt=.>` |
| ShootPlan | ShootingWindow | `lt=<<<<<-`; `m1=1`; `m2=0..*` |
| PlanValidator | ShootPlan | `lt=.>` |
| EnvironmentService | EnvironmentProvider | `lt=->` |
| EnvironmentApiAdapter | EnvironmentProvider | `lt=.>>` |
| EnvironmentService | LocationService | `lt=->` |
| LocationService | DeviceLocationProvider | `lt=->`; `m1=1`; `m2=0..1` |
| EnvironmentService | EnvironmentContext | `lt=.>` |
| TechnicalCalculator | CalculationInput | `lt=.>` |
| TechnicalCalculator | CalculationResult | `lt=.>` |
| CalculationTool | TechnicalCalculator | `lt=->` |
| CalculationInput | CalculationKind | `lt=.>` |
| ExifLibraryAdapter | MetadataExtractor | `lt=.>>` |
| MetadataExtractor | ExifRecord | `lt=.>` |
| StatisticsCalculator | ExifRecord | `lt=.>` |
| StatisticsCalculator | ExifStatistics | `lt=.>` |
| PostShootReview | ExifStatistics | `lt=<<<<<-`; `m1=1`; `m2=1` |
| SessionService | SessionRepository | `lt=->` |
| HistoryRetriever | SessionRepository | `lt=->` |
| InventoryService | InventoryRepository | `lt=->` |
| InventoryService | GearRecognitionService | `lt=->` |
| PlanningService | PhotoPilotAgent | `lt=->` |
| ReviewService | PhotoPilotAgent | `lt=->` |
| PhotographySession | ShootPlan | `lt=<<<<<-`; `m1=1`; `m2=0..*` |
| PhotographySession | PostShootReview | `lt=<<<<<-`; `m1=1`; `m2=0..*` |
| CalculationTool | AgentTool | `lt=.>>` |
| EnvironmentTool | AgentTool | `lt=.>>` |
| GearMatchingTool | AgentTool | `lt=.>>` |
| SpecificationTool | AgentTool | `lt=.>>` |
| ShootPlan | Loadout | `lt=<<<<<-`; `m1=1`; `m2=0..1` |
| EquipmentCatalogAdapter | EquipmentSpecificationProvider | `lt=.>>` |
| ReviewService | MetadataExtractor | `lt=->` |
| ReviewService | StatisticsCalculator | `lt=->` |
