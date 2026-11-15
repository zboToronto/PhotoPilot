package com.photopilot.integration;

import com.photopilot.model.CameraMetadata;
import java.nio.file.Path;

/** Reads the camera metadata a shutter count check needs from one local photo, without modifying it. */
public interface CameraMetadataReader {
    /**
     * @param file an absolute path to an existing, readable photo
     * @return the metadata, or ExifTool's per-file error in {@link CameraMetadata#error()}
     * @throws ExifToolException when the reader itself is unavailable, fails or times out
     */
    CameraMetadata read(Path file) throws ExifToolException;
}
