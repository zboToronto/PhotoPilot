package com.photopilot.integration;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import java.util.Objects;

/** Request policy at the existing model port; performs exactly one delegate call. */
public final class LanguageAwareLLMClient implements LLMClient {
    private final LLMClient delegate;
    private final LocalizationService localization;
    public LanguageAwareLLMClient(LLMClient delegate, LocalizationService localization) {
        this.delegate = Objects.requireNonNull(delegate); this.localization = Objects.requireNonNull(localization);
    }
    @Override public Result<ModelReply> complete(AgentContext context) {
        if (context == null) return delegate.complete(null);
        String preferred = localization.language() == Language.SIMPLIFIED_CHINESE ? "Simplified Chinese (zh-CN)" : "English (en)";
        String instruction = "Default response language preference: " + preferred + ". "
            + "Use this language for new natural-language responses unless the user explicitly requests another language. "
            + "This is a default preference only: higher-priority system instructions, safety constraints, tool requirements, "
            + "structured output schemas, schema keys, enum values, API contracts and programming-language syntax take precedence. "
            + "Never translate or rewrite existing conversation messages, evidence or tool parameters.";
        return delegate.complete(context.withResponseLanguageInstruction(instruction));
    }
}
