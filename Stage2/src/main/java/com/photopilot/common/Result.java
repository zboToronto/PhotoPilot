package com.photopilot.common;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.i18n.*;

/**
 * Stage 1 UML element 10 (zero-based): {shared return type; preserves failures}
 * Optional value and diagnostic messages; localizable descriptors preserve the English diagnostic API.
 */
public class Result<T> {
    private Optional<T> value;
    private List<String> errors;
    private List<String> warnings;
    private List<LocalizedMessage> messages;

    public Result(Optional<T> value, List<String> errors, List<String> warnings) {
        this.value = Objects.requireNonNull(value, "value");
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
        this.messages = this.errors.stream().map(error -> LocalizedMessage.of("error.detail", error)).toList();
    }

    public boolean isSuccess() {
        return errors.isEmpty() && value.isPresent();
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(Optional.empty(), List.of(Objects.requireNonNull(message)), List.of());
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(Optional.of(value), List.of(), List.of());
    }

    public static <T> Result<T> failureKey(String key, Object... arguments) {
        return failure(LocalizedMessage.of(key, arguments));
    }
    public static <T> Result<T> failure(LocalizedMessage message) {
        Result<T> result = failure(EnglishMessages.text(message.key(), message.arguments().toArray()));
        result.messages = List.of(message);
        return result;
    }
    public static <T> Result<T> failure(Throwable error) {
        return error instanceof LocalizedException localized ? failure(localized.localizedMessage())
            : failureKey("error.detail", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
    }
    public static <T> Result<T> failure(Result<?> source) {
        Result<T> result = new Result<>(Optional.empty(), source.errors, source.warnings);
        result.messages = source.messages;
        return result;
    }
    public List<String> errors(LocalizationService localization) { return messages.stream().map(message -> message.render(localization)).toList(); }
    public boolean hasError(String key) { return messages.stream().anyMatch(message -> message.key().equals(key)); }

    public Optional<T> value() { return value; }
    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
}
