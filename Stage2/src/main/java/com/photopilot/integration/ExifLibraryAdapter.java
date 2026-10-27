package com.photopilot.integration;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.photopilot.common.Result;
import com.photopilot.model.ExifRecord;

/**
 * Stage 1 UML element 102 (zero-based): {Adapter; JPEG EXIF library and exported metadata reader}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ExifLibraryAdapter implements MetadataExtractor {
    private Set<String> supportedFormats;

    public ExifLibraryAdapter(Set<String> supportedFormats) {
        this.supportedFormats = Set.copyOf(supportedFormats);
    }

    public boolean supports(Path file) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ExifLibraryAdapter.supports");
    }

    public Result<List<ExifRecord>> extract(List<Path> files) {
        return Result.failure("NOT IMPLEMENTED: ExifLibraryAdapter.extract");
    }
}
