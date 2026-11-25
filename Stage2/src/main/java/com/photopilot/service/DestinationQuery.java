package com.photopilot.service;

import com.photopilot.model.PlaceCandidate;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded database queries retain the original qualifiers as constraints on every returned match. */
record DestinationQuery(String original, String entity, List<String> qualifiers) {
    private static final Pattern SEPARATOR = Pattern.compile("(?iu)\\s+(?:in|near)\\s+|\\s*[,，]\\s*");
    // Only destination text belongs in a provider request. Coordinate fields bypass this lookup entirely.
    private static final Pattern PATH_OR_CREDENTIAL = Pattern.compile(
        "(?iu)\\\\|[a-z]:/|file://|https?://|AIza[\\w-]{25,}|(?:^|\\s)(?:~|\\.{1,2})?/\\S|"
        + "(?:^|\\s)\\S+/[^\\s/]+\\.[a-z0-9]{1,10}(?:\\s|$)");
    private static final Pattern COORDINATES = Pattern.compile(
        "(?iu)(?<![\\d.])[+-]?\\d{1,3}(?:\\.\\d+)?\\s*[,;]\\s*[+-]?\\d{1,3}(?:\\.\\d+)?(?![\\d.])|"
        + "[+-]?\\d{1,3}\\.\\d+\\s+[+-]?\\d{1,3}\\.\\d+|\\d\\s*[°º]|"
        + "(?<!\\w)\\d{1,2}(?:\\.\\d+)?\\s*[NS]\\s*[,;\\s]+\\d{1,3}(?:\\.\\d+)?\\s*[EW](?!\\w)");

    static String normalize(String text) { return Objects.toString(text, "").replaceAll("(?U)\\s+", " ").strip(); }
    static boolean privateInput(String text) { return PATH_OR_CREDENTIAL.matcher(text).find() || COORDINATES.matcher(text).find(); }
    static DestinationQuery of(String text) {
        var parts = SEPARATOR.split(normalize(text));
        if (parts.length == 0) return new DestinationQuery(text, "", List.of());
        return new DestinationQuery(normalize(text), parts[0], Arrays.stream(parts).skip(1).filter(s -> !s.isBlank()).toList());
    }
    List<String> attempts() {
        if (qualifiers.isEmpty()) return List.of(original);
        return List.of(String.join(", ", entity, String.join(", ", qualifiers)), entity).stream().distinct().toList();
    }
    private static String key(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
    boolean matches(PlaceCandidate candidate) {
        if (!candidate.validPoint() || !qualifiersMatch(candidate)) return false;
        return key(entity).equals(key(candidate.name()));
    }
    boolean qualifiersMatch(PlaceCandidate candidate) {
        var names = List.of(candidate.name(), candidate.city(), candidate.region(), candidate.country());
        return qualifiers.stream().allMatch(q -> names.stream().anyMatch(part -> key(q).equals(key(part))));
    }
    boolean broaderArea(PlaceCandidate candidate) {
        return candidate.validPoint() && (candidate.scope() == PlaceCandidate.Scope.CITY_LEVEL || candidate.scope() == PlaceCandidate.Scope.REGION_LEVEL)
            && !matches(candidate) && qualifiersMatch(candidate) && qualifiers.stream().anyMatch(q -> key(q).equals(key(candidate.name())));
    }
    boolean plausibleEstimate(PlaceCandidate candidate) {
        if (!qualifiersMatch(candidate)) return false;
        boolean broad = candidate.scope() == PlaceCandidate.Scope.CITY_LEVEL || candidate.scope() == PlaceCandidate.Scope.REGION_LEVEL;
        boolean namesQualifier = qualifiers.stream().anyMatch(q -> key(q).equals(key(candidate.name())));
        if (namesQualifier && !key(entity).equals(key(candidate.name()))) return broad;
        // A short unqualified name is an identity, not a free-text description. Require at least a shared name word.
        var words = key(entity).split(" ");
        if (words.length == 1 && key(candidate.name()).chars().allMatch(ch -> ch < 128) && key(entity).chars().allMatch(ch -> ch < 128))
            return (" " + key(candidate.name()) + " ").contains(" " + words[0] + " ");
        return true;
    }
    boolean normalizedMatch(PlaceCandidate estimate, PlaceCandidate db) {
        boolean broad = estimate.scope() == PlaceCandidate.Scope.CITY_LEVEL || estimate.scope() == PlaceCandidate.Scope.REGION_LEVEL;
        if (!db.validPoint() || broad && !matches(db)) return false;
        if (qualifiers.isEmpty() && estimate.city().isBlank() && estimate.region().isBlank() && estimate.country().isBlank()) return false;
        return key(estimate.name()).equals(key(db.name())) && qualifiersMatch(db)
            && compatible(estimate.country(), db.country()) && compatible(estimate.region(), db.region())
            && compatible(estimate.city(), db.city())
            && (estimate.scope() != PlaceCandidate.Scope.LANDMARK_VICINITY
                || (db.scope() != PlaceCandidate.Scope.CITY_LEVEL && db.scope() != PlaceCandidate.Scope.REGION_LEVEL));
    }
    private static boolean compatible(String wanted, String actual) {
        return wanted.isEmpty() || key(wanted).equals(key(actual));
    }
}
