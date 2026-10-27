package com.photopilot.ui.privacy;

import java.nio.file.Path;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 41 (zero-based): {presentation helper; user crops/redacts first}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ImagePrivacyBoundary {

    public ImagePrivacyBoundary() {
    }

    public Result<SanitizedImage> approveRedacted(Path copy, boolean confirmed) {
        return Result.failureKey("error.notImplemented", "ImagePrivacyBoundary.approveRedacted");
    }

    public void discard(SanitizedImage image) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ImagePrivacyBoundary.discard");
    }
}
