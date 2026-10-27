package com.photopilot.integration;

import java.nio.file.Path;
import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.ExifRecord;

/**
 * Stage 1 UML element 101 (zero-based): {Adapter target; extension point for formats}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface MetadataExtractor {

    public boolean supports(Path file);

    public Result<List<ExifRecord>> extract(List<Path> files);
}
