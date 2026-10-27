package com.photopilot.i18n;

/** Validation error with a stable key; English getMessage preserves diagnostic/API compatibility. */
public final class LocalizedException extends IllegalArgumentException {
    private final LocalizedMessage message;
    public LocalizedException(String key, Object... arguments) {
        this(LocalizedMessage.of(key, arguments));
    }
    public LocalizedException(LocalizedMessage message) {
        super(EnglishMessages.text(message.key(), message.arguments().toArray())); this.message = message;
    }
    public LocalizedMessage localizedMessage() { return message; }
}
