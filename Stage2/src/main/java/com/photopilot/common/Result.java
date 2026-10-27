package com.photopilot.common;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 10 (zero-based): {shared return type; preserves failures}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Result<T> {
    private Optional<T> value;
    private List<String> errors;
    private List<String> warnings;

    public Result(Optional<T> value, List<String> errors, List<String> warnings) {
        this.value = Objects.requireNonNull(value, "value");
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    public boolean isSuccess() {
        return errors.isEmpty() && value.isPresent();
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(Optional.empty(), List.of(Objects.requireNonNull(message)), List.of());
    }

    public Optional<T> value() { return value; }
    public List<String> errors() { return errors; }
    public List<String> warnings() { return warnings; }
}
