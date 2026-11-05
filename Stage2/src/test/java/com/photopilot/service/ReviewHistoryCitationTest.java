package com.photopilot.service;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F11 "compare with previous sessions": figures of earlier shoots may be cited only from their saved EXIF facts. */
class ReviewHistoryCitationTest {
    @TempDir Path root;
    static final String CURRENT = "{\"usablePhotos\":12}";

    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    /** Earlier Reviewed session: 400 mm on 3 of 4 photos, ISO 1600; its saved advice mentions 1/3200 s. */
    static PhotographySession earlier() {
        var completed = SessionFixtures.linked(new CompletedState(), List.of(), false);
        var stats = new ExifStatistics(4, 4, Map.of(), Map.of(400.0, 3, 200.0, 1), Map.of(), Map.of(8.0, 4), Map.of(1600, 3, 800, 1), Map.of(), Map.of(), List.of("Fixture evidence"));
        var review = new PostShootReview(UUID.randomUUID(), Instant.parse("2026-10-20T18:00:00Z"), stats, completed.plans().getFirst().id(), 1, List.of(),
            List.of("Try 1/3200 s on banking passes"), List.of("Fixture"), Optional.empty(), "Summary", List.of(), List.of(), List.of(), List.of(), List.of(), PostShootReview.SAVED);
        return ok(ok(completed.appendReview(review)).apply(SessionEvent.REVIEW_SAVED));
    }

    @Test void earlierSessionsAreComparedOnlyThroughTheirSavedExifFactsNeverThroughEarlierAdvice() {
        var sessions = new JsonSessionRepository(root.resolve("s"));
        var current = ok(sessions.insert(SessionFixtures.linked(new DraftState(), List.of(), false)));
        ok(sessions.insert(earlier()));
        String reply = "{\"step\":\"final\",\"result\":{\"summary\":\"12 photos had usable metadata.\",\"technicalObservations\":["
            + "\"The earlier session used 400 mm for 3 of 4 photos.\",\"Earlier advice suggested 1/3200 s.\",\"This shoot used 250 mm.\"],"
            + "\"equipmentObservations\":[],\"exposureObservations\":[\"The earlier session recorded ISO 1600 most often.\"],"
            + "\"lessons\":[],\"improvements\":[],\"limitations\":[]}}";
        var agent = new PhotoPilotAgent(context -> Result.success(new ModelReply(Optional.of(reply), List.of(), List.of())),
            new ToolRegistry(Map.of()), new HistoryRetriever(sessions), 4);
        var run = ok(agent.runWithTools(ReviewEvidence.TASK, new AgentContext(current.id(), List.of("Review."), CURRENT, List.of(), Optional.empty())
            .withResponseSchema(ReviewInterpretation.SCHEMA), ReviewInterpretation.class, Set.of()));
        assertEquals(1, run.historySessions());
        assertTrue(run.historyEvidence().contains("pastAdvice"), "the earlier advice was sent as background");

        var checked = ReviewEvidence.check(run.output(), CURRENT, run.historyEvidence());
        assertEquals("12 photos had usable metadata.", checked.summary());
        assertEquals(List.of("The earlier session used 400 mm for 3 of 4 photos."), checked.technical(),
            "facts of the earlier session may be cited; a figure only in earlier advice or nowhere is removed");
        assertEquals(List.of("The earlier session recorded ISO 1600 most often."), checked.exposure());
        assertEquals(2, checked.removed());

        var withoutHistory = ReviewEvidence.check(run.output(), CURRENT, "");
        assertTrue(withoutHistory.technical().isEmpty() && withoutHistory.exposure().isEmpty(), "without related history no earlier figure is accepted");
        assertTrue(ReviewEvidence.historyFacts("{not json").isEmpty(), "unreadable history allows nothing");
    }
}
