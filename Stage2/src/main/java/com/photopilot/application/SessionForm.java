package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.model.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Shared GUI/CLI parsing; no locale-dependent interpretation of dates or user text. */
public final class SessionForm {
    private SessionForm() { }
    public record Details(String title, LocalDate date, Optional<GeoLocation> location, String notes) { }
    public static Result<Details> parse(Map<String, String> input, PhotographySession original) {
        try {
            String title = input.getOrDefault("title", original == null ? "" : original.title());
            LocalDate date = date(input.getOrDefault("date", original == null ? "" : original.date().toString()));
            String notes = input.getOrDefault("notes", original == null ? "" : original.notes());
            Optional<GeoLocation> location = original == null ? Optional.empty() : original.location();
            if (input.containsKey("location")) {
                String label = input.get("location");
                if (location.isEmpty() || !location.get().label().equals(label))
                    location = label.isBlank() ? Optional.empty() : Optional.of(GeoLocation.manual(label));
            }
            if (title.isBlank() || title.length() > 200 || notes.length() > 20000) return Result.failureKey("error.session.details");
            if (location.isPresent() && !location.get().validate().isSuccess()) return Result.failure(location.get().validate());
            return Result.success(new Details(title, date, location, notes));
        } catch (IllegalArgumentException error) { return Result.failure(error); }
    }
    public static LocalDate date(String text) {
        try { return LocalDate.parse(text); }
        catch (DateTimeParseException error) { throw new LocalizedException("error.session.date"); }
    }
    public static UUID id(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw new LocalizedException("error.session.id"); }
    }
}
