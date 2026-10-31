# Feature to skeleton mapping

Authoritative source: Stage1.docx section 8, checked against corresponding report PDF pp. 18–22 and UXF. F05 is implemented with [verified GUI/CLI evidence and additive design decisions](f05-inventory.md); F08 has [verified calculator evidence](f08-calculators.md). F01 has [verified session evidence and implementation diagrams](f01-sessions.md). Other feature behavior remains **NOT IMPLEMENTED**; its structural status is **SCAFFOLDED**. Bootstrap status is separate. UC14 is included validation, not a new GUI feature.

## Cross-cutting addition: bilingual localization (2026-10-27)

**Original design → implementation → reason → impact:** English-only presentation → shared resource/locale facade, durable preference port, JavaFX Observer bindings and an LLM-port Decorator → switch GUI/CLI language without duplicating domain logic or resetting state → bilingual F01–F11 navigation and status; complete current F05/UC05/SD02 and F08/UC08/SD03 presentation, validation and CLI output. Original business-feature status is unchanged; this usability capability does not count toward the ten-feature minimum.

**Participants/methods:** `ApplicationBootstrap.localization`; `LocalizationService.text/selectLanguage/useLanguage/listen`; `FileLocalePreferenceRepository.load/save`; `FxLocalizer.bind/close`; `SettingsDialog`; existing GUI/CLI adapters; `Result.failureKey/errors(localization)`; `CalculationForm.format(result, localization)`; `LanguageAwareLLMClient.complete`; `AgentContext.withResponseLanguageInstruction`. Facade/Observer/Decorator solve resource access, in-place refresh/disposal and request language policy respectively. Existing business Facade, Strategy, Adapter, Command and State boundaries are preserved.

**Evidence and limits:** [implementation/file map/71-test evidence](localization.md), [updated class and sequence UML](localization-architecture.md). AI policy is mock-verified at the model port; actual Gemini and agent execution remain unavailable. No submission demonstration video is claimed.

## F01 Photography Session and History Management

**Description:** Create, save, search and reopen photography sessions and linked history.

**Type:** Deterministic

**Use cases:** UC01

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; SessionService; PhotographySession; SessionRepository; JsonSessionRepository; SessionState; DraftState; PlannedState; CompletedState; ReviewedState

**Relevant methods:** PhotoPilotFacade:; createSession(title, date); sessions(); SessionService:; create(title, date); save(session); search(query); open(id); transition(id, event); PhotographySession:; apply(event); SessionRepository:; save(session); find(id); search(query)

**Sequence and changes:** SD01 creation, history and completion are implemented. Optional location/notes use SessionService create/update overloads; separate insert/delete and expected revisions protect CRUD. [Updated implementation diagrams and design rationale](f01-sessions.md#design-change-record-and-implementation-diagrams).

**Patterns:** Facade: shared access.; State: session lifecycle.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [SessionService](../src/main/java/com/photopilot/service/SessionService.java), [PhotographySession](../src/main/java/com/photopilot/model/PhotographySession.java), [SessionState](../src/main/java/com/photopilot/state/SessionState.java), [DraftState](../src/main/java/com/photopilot/state/DraftState.java), [PlannedState](../src/main/java/com/photopilot/state/PlannedState.java), [CompletedState](../src/main/java/com/photopilot/state/CompletedState.java), [ReviewedState](../src/main/java/com/photopilot/state/ReviewedState.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [JsonSessionRepository](../src/main/java/com/photopilot/repository/JsonSessionRepository.java).

**Status:** F01 verified through GUI/CLI with atomic JSON, retained drafts, search/filter, guarded lifecycle and F05 history references. Final full suite 95/95, packaged build and GUI_SMOKE_OK (2026-10-31). F10 approval and F11 review creation remain unavailable; valid linked-record transitions use controlled fixtures in tests. See [F01 evidence](f01-sessions.md).

## F02 AI Photography Inspiration and Activity Recommendation

**Description:** Suggest feasible shooting activities from permitted context; support choosing, saving or dismissing ideas.

**Type:** Hybrid

**Use cases:** UC02

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; DiscoveryService; DiscoveryContext; ActivitySuggestion; FeasibilityValidator; EnvironmentService; EnvironmentProvider; EnvironmentApiAdapter; SessionRepository; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; suggest(context); DiscoveryService:; suggest(context); select(id, idea); save(id, idea); dismiss(idea); FeasibilityValidator:; validate(idea, context); EnvironmentService:; retrieve(place, date)

**Sequence and gaps:** SD01: suggestions and feasibility.; Gap: select/save/dismiss lack a Facade route and are not sequenced.

**Patterns:** Facade: suggestions.; Adapter: Gemini and environment data.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [DiscoveryService](../src/main/java/com/photopilot/service/DiscoveryService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [FeasibilityValidator](../src/main/java/com/photopilot/validation/FeasibilityValidator.java), [EnvironmentService](../src/main/java/com/photopilot/service/EnvironmentService.java), [EnvironmentProvider](../src/main/java/com/photopilot/integration/EnvironmentProvider.java), [EnvironmentApiAdapter](../src/main/java/com/photopilot/integration/EnvironmentApiAdapter.java), [DiscoveryContext](../src/main/java/com/photopilot/model/DiscoveryContext.java), [ActivitySuggestion](../src/main/java/com/photopilot/model/ActivitySuggestion.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F03 Natural-Language Photography Requirement Parsing

**Description:** Parse a brief, clarify or edit fields, validate and save confirmed shoot requirements.

**Type:** Hybrid

**Use cases:** UC03

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; PlanningService; RequirementService; ShootRequirement; PhotographySession; SessionRepository; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; parse(id, text); planning(); PlanningService:; parse(id, text); confirm(id, edited); RequirementService:; parse(text, context); clarify(draft, answers); confirm(edited); ShootRequirement:; validate(); confirm(); PhotographySession:; attachRequirement(requirement)

**Sequence and gaps:** SD01: parsing and explicit confirmation; clarification via resubmitted text.

**Patterns:** Facade: shared workflow.; Adapter: Gemini parsing.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [PlanningService](../src/main/java/com/photopilot/service/PlanningService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [PhotographySession](../src/main/java/com/photopilot/model/PhotographySession.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [RequirementService](../src/main/java/com/photopilot/service/RequirementService.java), [ShootRequirement](../src/main/java/com/photopilot/model/ShootRequirement.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F04 AI Gear Recognition and Automatic Import

**Description:** Recognize gear from an approved, redacted image; verify specifications and import only after confirmation.

**Type:** Hybrid

**Use cases:** UC04

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; ImagePrivacyBoundary; SanitizedImage; InventoryService; GearRecognitionService; GearImportDraft; Equipment; EquipmentSpecificationProvider; EquipmentCatalogAdapter; InventoryRepository; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** ImagePrivacyBoundary:; approveRedacted(copy, confirmed); discard(image); PhotoPilotFacade:; recognize(image); inventory(); GearRecognitionService:; recognize(image); verify(draft); confirm(edited); EquipmentSpecificationProvider:; lookup(model); InventoryService:; confirm(draft); save(item)

**Sequence and gaps:** SD02: privacy, recognition, lookup and confirmed import.

**Patterns:** Facade: import access.; Adapter: Gemini and equipment lookup.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [InventoryService](../src/main/java/com/photopilot/service/InventoryService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [GearRecognitionService](../src/main/java/com/photopilot/service/GearRecognitionService.java), [GearImportDraft](../src/main/java/com/photopilot/model/GearImportDraft.java), [ImagePrivacyBoundary](../src/main/java/com/photopilot/ui/privacy/ImagePrivacyBoundary.java), [SanitizedImage](../src/main/java/com/photopilot/ui/privacy/SanitizedImage.java), [EquipmentSpecificationProvider](../src/main/java/com/photopilot/integration/EquipmentSpecificationProvider.java), [Equipment](../src/main/java/com/photopilot/model/Equipment.java), [InventoryRepository](../src/main/java/com/photopilot/repository/InventoryRepository.java), [EquipmentCatalogAdapter](../src/main/java/com/photopilot/integration/EquipmentCatalogAdapter.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F05 Photography Gear Inventory Management

**Description:** Maintain confirmed gear records; validate entries, flag duplicates and warn before removal while preserving history.

**Type:** Deterministic

**Use cases:** UC05;; UC04 (resulting inventory entry)

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; InventoryService; GearInventory; Equipment; Camera; Lens; Filter; Battery; StorageMedia; InventoryRepository; JsonInventoryRepository; SessionRepository; GearSnapshot

**Relevant methods:** PhotoPilotFacade:; saveGear(item); inventory(); InventoryService:; list(query); save(item); duplicates(item); affectedSessions(id); remove(id, warningAccepted); GearInventory:; add(item); update(item); search(query); remove(id); SessionRepository:; referencingGear(id)

**Sequence and gaps:** SD02: inventory operations and confirmed-import persistence.

**Patterns:** Facade: common inventory access.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [InventoryService](../src/main/java/com/photopilot/service/InventoryService.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [GearInventory](../src/main/java/com/photopilot/model/GearInventory.java), [Equipment](../src/main/java/com/photopilot/model/Equipment.java), [Camera](../src/main/java/com/photopilot/model/Camera.java), [Lens](../src/main/java/com/photopilot/model/Lens.java), [Filter](../src/main/java/com/photopilot/model/Filter.java), [Battery](../src/main/java/com/photopilot/model/Battery.java), [StorageMedia](../src/main/java/com/photopilot/model/StorageMedia.java), [InventoryRepository](../src/main/java/com/photopilot/repository/InventoryRepository.java), [JsonInventoryRepository](../src/main/java/com/photopilot/repository/JsonInventoryRepository.java), [GearSnapshot](../src/main/java/com/photopilot/model/GearSnapshot.java).

**Status:** IMPLEMENTED / GUI + CLI VERIFIED (2026-10-26). InventoryPane and InventoryCommands use the existing Facade accessor and InventoryService; EquipmentForm maps shared inputs. EquipmentType/EquipmentData retain typed domain values and persistence metadata. Add/update and duplicate-acceptance overloads prevent ID collisions and stale edits; repository transactions serialize writes. InventoryTest, InventoryCliTest and InventoryGuiTest verify the workflow. F04 recognition and nonempty F01 session-reference storage remain outside this implementation; deletion fails safely if references cannot be checked. [Detailed traceability, tests and design decisions](f05-inventory.md).

## F06 AI Photography Equipment Capability Requirement Analysis

**Description:** Derive capability needs without product names; validate, review and save the confirmed profile.

**Type:** Hybrid

**Use cases:** UC06

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; PlanningService; RequirementService; ShootRequirement; EquipmentRequirementProfile; CapabilityNeed; PhotographySession; SessionRepository; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; analyzeNeeds(id); planning(); PlanningService:; analyzeNeeds(id); confirmProfile(id, edited); RequirementService:; deriveProfile(req); EquipmentRequirementProfile:; validate(req); confirm(); PhotographySession:; attachProfile(profile)

**Sequence and gaps:** SD02: analysis plus revalidated profile confirmation and atomic save.

**Patterns:** Facade: shared workflow.; Adapter: Gemini analysis.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [PlanningService](../src/main/java/com/photopilot/service/PlanningService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [PhotographySession](../src/main/java/com/photopilot/model/PhotographySession.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [RequirementService](../src/main/java/com/photopilot/service/RequirementService.java), [ShootRequirement](../src/main/java/com/photopilot/model/ShootRequirement.java), [EquipmentRequirementProfile](../src/main/java/com/photopilot/model/EquipmentRequirementProfile.java), [CapabilityNeed](../src/main/java/com/photopilot/model/CapabilityNeed.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F07 Owned Gear Matching and Compatibility Validation

**Description:** Check owned-gear compatibility, match capability needs and rank verified usable loadouts with trade-offs.

**Type:** Hybrid

**Use cases:** UC07;; UC14 (included)

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; PlanningService; GearMatchingService; CompatibilityValidator; CompatibilityReport; LoadoutRankingStrategy; PortabilityRanking; CoverageRanking; EquipmentRequirementProfile; GearInventory; Loadout; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; matchGear(id); GearMatchingService:; match(profile, gear); compatibleCandidates(gear); setRanking(strategy); CompatibilityValidator:; validate(loadout); matchNeeds(loadout, profile); CompatibilityReport:; isUsable(); LoadoutRankingStrategy:; rank(candidates, profile)

**Sequence and gaps:** SD03: compatibility and ranking.; Gap: no operation attaches a selected loadout; ranking strategy is assumed configured.

**Patterns:** Facade: matching access.; Strategy: ranking policy.; Adapter: Gemini ranking.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [PlanningService](../src/main/java/com/photopilot/service/PlanningService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [EquipmentRequirementProfile](../src/main/java/com/photopilot/model/EquipmentRequirementProfile.java), [GearInventory](../src/main/java/com/photopilot/model/GearInventory.java), [GearMatchingService](../src/main/java/com/photopilot/service/GearMatchingService.java), [CompatibilityValidator](../src/main/java/com/photopilot/validation/CompatibilityValidator.java), [CompatibilityReport](../src/main/java/com/photopilot/model/CompatibilityReport.java), [LoadoutRankingStrategy](../src/main/java/com/photopilot/strategy/LoadoutRankingStrategy.java), [PortabilityRanking](../src/main/java/com/photopilot/strategy/PortabilityRanking.java), [CoverageRanking](../src/main/java/com/photopilot/strategy/CoverageRanking.java), [Loadout](../src/main/java/com/photopilot/model/Loadout.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F08 Photography Technical Calculation Tool Suite

**Implementation update (2026-10-26):** All six deterministic operations are verified through the shared Facade, GUI and CLI; CalculationTool's strict JSON adapter is tested independently. Optional read-only F05 prefill, explicit units/assumptions, infinity and validation are operational. 53/53 regression tests and GUI smoke pass. F10 orchestration remains scaffolded. [Detailed formulas, source/test mapping, decisions and limits](f08-calculators.md).

**Description:** Calculate depth of field, hyperfocal distance, field of view, exposure/ND, storage and battery estimates with units.

**Type:** Deterministic

**Use cases:** UC08

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; TechnicalCalculator; CalculationInput; CalculationResult; CalculationTool

**Relevant methods:** PhotoPilotFacade:; calculate(input); CalculationInput:; validate(); TechnicalCalculator:; calculate(input); depthOfField(input); hyperfocal(input); fieldOfView(input); exposureAndND(input); storage(input); batteryCapacity(input); CalculationTool:; execute(argumentsJson)

**Sequence and gaps:** SD03: all six calculations.; SD04: agent invocation through CalculationTool.

**Patterns:** Facade: direct calculation access. Tool invocation does not make calculation AI-based.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [TechnicalCalculator](../src/main/java/com/photopilot/service/TechnicalCalculator.java), [CalculationInput](../src/main/java/com/photopilot/model/CalculationInput.java), [CalculationResult](../src/main/java/com/photopilot/model/CalculationResult.java), [CalculationTool](../src/main/java/com/photopilot/agent/CalculationTool.java).

**Status:** IMPLEMENTED / GUI + CLI VERIFIED for the standalone deterministic suite and tool adapter. F10 orchestration remains scaffolded. Bilingual presentation is verified with the current 71-test suite; see [localization evidence](localization.md).

## F09 Location, Time, and Environment-Aware Photography Planning

**Description:** Retrieve weather/sunlight evidence and recommend shooting windows and arrival times, labeling stale or missing data.

**Type:** Hybrid

**Use cases:** UC09

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; PlanningService; EnvironmentService; EnvironmentProvider; EnvironmentApiAdapter; LocationService; DeviceLocationProvider; EnvironmentContext; EnvironmentAssessment; ShootingWindow; FeasibilityValidator; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; assessEnvironment(id); EnvironmentService:; assess(req); retrieve(place, date); EnvironmentProvider:; fetch(place, date); LocationService:; manual(label, lat, lon); current(explicitConsent); EnvironmentContext:; staleFields(now, maxAge); FeasibilityValidator:; checkTiming(assessment)

**Sequence and gaps:** SD03: consent/manual location, retrieval, AI assessment and timing validation.

**Patterns:** Facade: assessment access.; Adapter: environmental data and Gemini.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [PlanningService](../src/main/java/com/photopilot/service/PlanningService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [FeasibilityValidator](../src/main/java/com/photopilot/validation/FeasibilityValidator.java), [ShootingWindow](../src/main/java/com/photopilot/model/ShootingWindow.java), [EnvironmentAssessment](../src/main/java/com/photopilot/model/EnvironmentAssessment.java), [EnvironmentService](../src/main/java/com/photopilot/service/EnvironmentService.java), [EnvironmentProvider](../src/main/java/com/photopilot/integration/EnvironmentProvider.java), [EnvironmentApiAdapter](../src/main/java/com/photopilot/integration/EnvironmentApiAdapter.java), [LocationService](../src/main/java/com/photopilot/service/LocationService.java), [EnvironmentContext](../src/main/java/com/photopilot/model/EnvironmentContext.java), [DeviceLocationProvider](../src/main/java/com/photopilot/integration/DeviceLocationProvider.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F10 AI Shoot Plan Generation and Interactive Modification

**Description:** Generate, validate and preview a shoot plan; apply controlled revisions and save only the approved, revalidated version.

**Type:** Hybrid

**Use cases:** UC10; UC11;; UC14 (included by both)

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; PlanningService; PhotoPilotAgent; LLMClient; GeminiAdapter; HistoryRetriever; ToolRegistry; CalculationTool; GearMatchingService; PlanEditor; PlanCommand; ReplaceLoadoutCommand; ReviseScheduleCommand; ReviseStrategyCommand; ShootPlan; PlanValidator; CompatibilityValidator; TechnicalCalculator; PhotographySession; SessionState; DraftState; PlannedState; SessionRepository

**Relevant methods:** PlanningService:; generate(id); proposeChange(id, text); approve(id, plan); PhotoPilotAgent:; run(task, context, type); ToolRegistry:; execute(call); PlanEditor:; preview(base, change); PlanCommand:; applyTo(draft); ShootPlan:; copyForRevision(); PlanValidator:; validate(plan, req); validateChange(base, next); PhotographySession:; appendApprovedPlan(plan); apply(event); SessionRepository:; save(session)

**Sequence and gaps:** SD04: generation, tool loop, loadout-change preview and approval. Schedule/strategy commands are not separately sequenced.

**Patterns:** Facade; Adapter (Gemini); Command (revision); State (approval).; Strategy indirectly via matched loadouts.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [PlanningService](../src/main/java/com/photopilot/service/PlanningService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [ToolRegistry](../src/main/java/com/photopilot/agent/ToolRegistry.java), [HistoryRetriever](../src/main/java/com/photopilot/agent/HistoryRetriever.java), [PhotographySession](../src/main/java/com/photopilot/model/PhotographySession.java), [SessionState](../src/main/java/com/photopilot/state/SessionState.java), [DraftState](../src/main/java/com/photopilot/state/DraftState.java), [PlannedState](../src/main/java/com/photopilot/state/PlannedState.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [GearMatchingService](../src/main/java/com/photopilot/service/GearMatchingService.java), [CompatibilityValidator](../src/main/java/com/photopilot/validation/CompatibilityValidator.java), [PlanEditor](../src/main/java/com/photopilot/command/PlanEditor.java), [PlanCommand](../src/main/java/com/photopilot/command/PlanCommand.java), [ReplaceLoadoutCommand](../src/main/java/com/photopilot/command/ReplaceLoadoutCommand.java), [ReviseScheduleCommand](../src/main/java/com/photopilot/command/ReviseScheduleCommand.java), [ReviseStrategyCommand](../src/main/java/com/photopilot/command/ReviseStrategyCommand.java), [ShootPlan](../src/main/java/com/photopilot/command/ShootPlan.java), [PlanValidator](../src/main/java/com/photopilot/validation/PlanValidator.java), [TechnicalCalculator](../src/main/java/com/photopilot/service/TechnicalCalculator.java), [CalculationTool](../src/main/java/com/photopilot/agent/CalculationTool.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.

## F11 AI Post-Shoot Analysis and Review

**Description:** Import metadata, compute objective statistics, compare with the plan and save an evidence-based AI review; no aesthetic scoring.

**Type:** Hybrid

**Use cases:** UC12; UC13

**Planned participants:** PhotoPilotGUI; PhotoPilotCLI; PhotoPilotFacade; ReviewService; MetadataExtractor; ExifLibraryAdapter; ExifRecord; ExifStatistics; StatisticsCalculator; PostShootReview; HistoryRetriever; PhotographySession; SessionState; CompletedState; ReviewedState; SessionRepository; PhotoPilotAgent; LLMClient; GeminiAdapter

**Relevant methods:** PhotoPilotFacade:; analyze(id, files); review(id); reviews(); ReviewService:; analyze(id, files); review(id); save(id, review); MetadataExtractor:; supports(file); extract(files); StatisticsCalculator:; summarize(records); compare(stats, plan); PhotographySession:; attachStatistics(stats); appendReview(review); apply(event); SessionRepository:; save(session)

**Sequence and gaps:** SD05: independent statistics/review requests; statistics persistence and saved-review state transition.

**Patterns:** Facade: review access.; Adapter: EXIF and Gemini.; State: Completed to Reviewed.

**Skeleton files:** [PhotoPilotGUI](../src/main/java/com/photopilot/ui/gui/PhotoPilotGUI.java), [PhotoPilotFacade](../src/main/java/com/photopilot/application/PhotoPilotFacade.java), [PhotoPilotCLI](../src/main/java/com/photopilot/ui/cli/PhotoPilotCLI.java), [ReviewService](../src/main/java/com/photopilot/service/ReviewService.java), [PhotoPilotAgent](../src/main/java/com/photopilot/agent/PhotoPilotAgent.java), [LLMClient](../src/main/java/com/photopilot/integration/LLMClient.java), [GeminiAdapter](../src/main/java/com/photopilot/integration/GeminiAdapter.java), [HistoryRetriever](../src/main/java/com/photopilot/agent/HistoryRetriever.java), [PhotographySession](../src/main/java/com/photopilot/model/PhotographySession.java), [SessionState](../src/main/java/com/photopilot/state/SessionState.java), [CompletedState](../src/main/java/com/photopilot/state/CompletedState.java), [ReviewedState](../src/main/java/com/photopilot/state/ReviewedState.java), [SessionRepository](../src/main/java/com/photopilot/repository/SessionRepository.java), [MetadataExtractor](../src/main/java/com/photopilot/integration/MetadataExtractor.java), [ExifLibraryAdapter](../src/main/java/com/photopilot/integration/ExifLibraryAdapter.java), [StatisticsCalculator](../src/main/java/com/photopilot/service/StatisticsCalculator.java), [ExifRecord](../src/main/java/com/photopilot/model/ExifRecord.java), [ExifStatistics](../src/main/java/com/photopilot/model/ExifStatistics.java), [PostShootReview](../src/main/java/com/photopilot/model/PostShootReview.java).

**Status:** SCAFFOLDED / NOT IMPLEMENTED. No successful feature run is claimed.
