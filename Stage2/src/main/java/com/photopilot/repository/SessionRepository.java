package com.photopilot.repository;

import java.util.List;
import java.util.UUID;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;

/**
 * Stage 1 UML element 31 (zero-based): {persistence port; atomic aggregate save}
 * Session persistence port; save/append/delete reject stale revisions. Each instance addresses
 * one fixed storage scope; linked records inherit their session's scope. Reads return
 * detached aggregates, and failures must preserve existing stored data.
 */
public interface SessionRepository {

    public Result<PhotographySession> save(PhotographySession session);

    public Result<PhotographySession> find(UUID id);

    public Result<List<PhotographySession>> search(String query);

    public Result<List<UUID>> referencingGear(UUID id);

    /**
     * Like {@link #save}, which never changes the stored plan and review history, but the history may grow by exactly one
     * approved plan (F10 approval) or one saved review (F11 review save). Only those workflows call it.
     */
    default Result<PhotographySession> append(PhotographySession session) { return Result.failureKey("error.session.repositoryOperation"); }
    default Result<PhotographySession> insert(PhotographySession session) { return Result.failureKey("error.session.repositoryOperation"); }
    default Result<Boolean> delete(UUID id, String revision) { return Result.failureKey("error.session.repositoryOperation"); }
}
