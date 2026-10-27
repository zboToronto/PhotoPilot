package com.photopilot.service;

import java.util.List;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.ExifStatistics;

/**
 * Stage 1 UML element 103 (zero-based): {F11; deterministic statistics and plan comparison}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class StatisticsCalculator {

    public StatisticsCalculator() {
    }

    public Result<ExifStatistics> summarize(List<ExifRecord> records) {
        return Result.failureKey("error.notImplemented", "StatisticsCalculator.summarize");
    }

    public List<String> compare(ExifStatistics stats, ShootPlan plan) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: StatisticsCalculator.compare");
    }
}
