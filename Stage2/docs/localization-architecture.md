# Localization implementation UML (Stage 2 addition)

This supplements the unchanged Stage 1 diagrams with implemented localization participants. It does not claim that the original AI business workflows are operational.

```mermaid
classDiagram
    class ApplicationBootstrap {
        +localization() LocalizationService
        +facade() PhotoPilotFacade
    }
    class LocalizationService {
        +language() Language
        +text(key, arguments) String
        +selectLanguage(tag) boolean
        +useLanguage(tag)
        +listen(listener) Subscription
    }
    class LocalePreferenceRepository {
        <<interface>>
        +load() String
        +save(tag)
    }
    class FileLocalePreferenceRepository
    class FxLocalizer {
        ~bind(property, supplier)
        ~text(property, key, arguments)
        ~inputMenu(input)
        +close()
    }
    class SettingsDialog
    class PhotoPilotWorkspace
    class InventoryPane
    class CalculatorPane
    class PhotoPilotCLI
    class LocalizedMessage {
        +key() String
        +arguments() List
        +render(localization) String
    }
    class Result {
        +failureKey(key, arguments) Result
        +errors(localization) List
        +hasError(key) boolean
    }
    class LLMClient {
        <<interface>>
        +complete(context) Result
    }
    class LanguageAwareLLMClient {
        +complete(context) Result
    }
    class GeminiAdapter {
        +complete(context) Result
    }
    class AgentContext {
        +withResponseLanguageInstruction(instruction) AgentContext
        +responseLanguageInstruction() String
    }
    ApplicationBootstrap *-- LocalizationService
    ApplicationBootstrap ..> LanguageAwareLLMClient : constructs
    LocalizationService --> LocalePreferenceRepository
    FileLocalePreferenceRepository ..|> LocalePreferenceRepository
    SettingsDialog --> LocalizationService : select and persist
    SettingsDialog *-- FxLocalizer
    PhotoPilotWorkspace *-- FxLocalizer
    InventoryPane *-- FxLocalizer
    CalculatorPane *-- FxLocalizer
    FxLocalizer --> LocalizationService : visible-window subscription
    PhotoPilotCLI --> LocalizationService
    Result o-- LocalizedMessage
    LocalizedMessage ..> LocalizationService : render
    LanguageAwareLLMClient ..|> LLMClient
    GeminiAdapter ..|> LLMClient
    LanguageAwareLLMClient --> LLMClient : delegate once
    LanguageAwareLLMClient --> LocalizationService : current preference
    LanguageAwareLLMClient ..> AgentContext : request copy
```

`GeminiAdapter.complete` and `PhotoPilotAgent.run` remain unavailable. The implemented decorator is verified through the existing port using a capturing test double. Provider serialization of the separate response-language instruction awaits Gemini implementation.

```mermaid
sequenceDiagram
    actor User
    participant Settings as SettingsDialog
    participant L as LocalizationService
    participant P as LocalePreferenceRepository
    participant FX as FxLocalizer
    participant UI as Existing controls
    User->>Settings: Select Language enum value
    Settings->>L: selectLanguage(tag)
    L->>P: save(tag)
    P-->>L: saved or storage failure
    L->>L: set active language (even on write failure)
    L-->>FX: notify visible-window subscribers
    FX->>UI: refresh properties on JavaFX thread
    Note over FX,UI: No navigation, form rebuild, data mutation or model call
    L-->>Settings: persistence success/failure
    Settings->>UI: localized saved/retry feedback
    User->>Settings: Close window
    Settings->>FX: close / detach window subscription
```

F05 (UC05/SD02), F08 (UC08/SD03), existing Facade/service contracts and deterministic tool evidence remain unchanged. Localization adds Observer, a localization Facade and a narrow LLM port Decorator; it does not satisfy the minimum business feature count or certify five completed operational course patterns.
