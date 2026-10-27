package com.photopilot.model;

import java.time.ZonedDateTime;
import java.util.Objects;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 75 (zero-based): {F09/F10; copied into approved revisions}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ShootingWindow {
    private ZonedDateTime start;
    private ZonedDateTime end;
    private ZonedDateTime arrival;
    private String conditions;
    private boolean estimated;

    public ShootingWindow(ZonedDateTime start, ZonedDateTime end, ZonedDateTime arrival, String conditions, boolean estimated) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        this.arrival = Objects.requireNonNull(arrival, "arrival");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.estimated = estimated;
    }

    public Result<ShootingWindow> validate() {
        return Result.failure("NOT IMPLEMENTED: ShootingWindow.validate");
    }
}
