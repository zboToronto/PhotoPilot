package com.photopilot.integration;

import java.nio.file.Path;
import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.MetadataScan;

/**
 * Stage 1 UML element 101 (zero-based): {Adapter target; extension point for formats}
 * Local, read-only metadata extraction. Implementations never modify, copy or upload the files.
 */
public interface MetadataExtractor {

    public boolean supports(Path file);

    public Result<List<ExifRecord>> extract(List<Path> files);

    /** F11: the records together with one outcome per selected path; {@link #extract} keeps the Stage 1 contract. */
    public Result<MetadataScan> scan(List<Path> files);
}
