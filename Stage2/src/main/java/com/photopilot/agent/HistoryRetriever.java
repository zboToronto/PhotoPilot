package com.photopilot.agent;

import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.ShootRequirement;
import com.photopilot.repository.SessionRepository;
import com.photopilot.validation.CompatibilityValidator;

/**
 * Stage 1 UML element 18 (zero-based): {F01, F02, F10, F11; application memory}
 * Deterministic history retrieval. contextFor(id) finds other sessions with an approved plan whose confirmed subject
 * shares a word with this session's subject and summarizes only what was actually saved (subject, date, state,
 * planned equipment and shot priorities). No identifiers, notes or locations; nothing is invented when none match.
 */
public class HistoryRetriever {
    public static final int MAX_RELATED = 3;
    private SessionRepository repository;

    public HistoryRetriever(SessionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Result<List<PhotographySession>> retrieve(String query) {
        return repository.search(query);
    }

    public Result<AgentContext> contextFor(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var current = repository.find(id);
        if (!current.isSuccess()) return Result.failure(current);
        var words = words(current.value().orElseThrow().requirements().map(ShootRequirement::subject).orElse(""));
        var all = repository.search("");
        if (!all.isSuccess()) return Result.failure(all);
        var related = new ArrayList<PhotographySession>();
        for (var session : all.value().orElseThrow()) {
            if (session.id().equals(id) || related.size() >= MAX_RELATED) continue;
            if (session.plans().stream().noneMatch(ShootPlan::approved)) continue;
            var subject = session.requirements().filter(ShootRequirement::confirmed).map(ShootRequirement::subject).orElse("");
            if (!Collections.disjoint(words, words(subject))) related.add(session);
        }
        var items = new JsonArray();
        for (var session : related) {
            var item = new JsonObject();
            item.addProperty("subject", session.requirements().map(ShootRequirement::subject).orElse(""));
            item.addProperty("date", session.date().toString());
            item.addProperty("state", session.status());
            var latest = session.plans().stream().filter(ShootPlan::approved).max(Comparator.comparingInt(ShootPlan::revision)).orElseThrow();
            item.addProperty("approvedRevision", latest.revision());
            var gear = new JsonArray();
            latest.loadout().ifPresent(l -> l.gear().forEach(g -> gear.add(g.selectedQuantity() + " x " + CompatibilityValidator.name(g))));
            item.add("plannedEquipment", gear);
            var priorities = new JsonArray(); latest.shotPriorities().stream().limit(3).forEach(priorities::add);
            item.add("shotPriorities", priorities);
            items.add(item);
        }
        var evidence = new JsonObject(); evidence.add("relatedHistory", items);
        return Result.success(new AgentContext(id, List.of(), evidence.toString(), related, Optional.empty()));
    }

    private static Set<String> words(String text) {
        var found = new HashSet<String>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) if (word.length() >= 4 || word.codePoints().anyMatch(Character::isIdeographic) && word.length() >= 2) found.add(word);
        return found;
    }
}
