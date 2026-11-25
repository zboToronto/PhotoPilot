package com.photopilot.progress;

import com.photopilot.i18n.LocalizedMessage;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic guidance shown before, during or after an operation: a short localized sentence and, when the next
 * action lives on another page, that page's stable feature identifier (F01-F11 or HOME). Never produced by a model.
 */
public record NextStep(LocalizedMessage message, Optional<String> target) {
    public NextStep { Objects.requireNonNull(message); Objects.requireNonNull(target); }

    public static NextStep of(String key, Object... arguments) { return new NextStep(LocalizedMessage.of(key, arguments), Optional.empty()); }
    public static NextStep to(String feature, String key, Object... arguments) {
        return new NextStep(LocalizedMessage.of(key, arguments), Optional.of(feature));
    }
}
