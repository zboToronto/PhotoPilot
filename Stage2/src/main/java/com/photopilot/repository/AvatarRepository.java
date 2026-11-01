package com.photopilot.repository;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded local avatar codec and atomic storage, serialized with the user registry lifecycle. */
public final class AvatarRepository {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JsonProfileRepository profiles;
    public AvatarRepository(JsonProfileRepository profiles) { this.profiles = profiles; }

    public static byte[] prepare(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Select a PNG or JPEG file.");
        return normalize(AtomicFiles.read(source, MAX_BYTES).orElseThrow(() -> new IOException("Image is missing.")));
    }
    private static byte[] normalize(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Image is too large.");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image.");
            var reader = readers.next();
            try {
                if (!Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                    throw new IOException("Select a PNG or JPEG file.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000)
                    throw new IOException("Image dimensions exceed the limit.");
                BufferedImage decoded = reader.read(0), output = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                var graphics = output.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int side = Math.min(width, height), x = (width - side) / 2, y = (height - side) / 2;
                    graphics.drawImage(decoded, 0, 0, 256, 256, x, y, x + side, y + side, null);
                } finally { graphics.dispose(); }
                var encoded = new ByteArrayOutputStream();
                if (!ImageIO.write(output, "png", encoded)) throw new IOException("PNG encoding unavailable.");
                return encoded.toByteArray();
            } finally { reader.dispose(); }
        }
    }
    private Path file(UUID id) throws IOException {
        Path file = StorageScope.forProfile(profiles.root(), id).directory().resolve("avatar.png");
        ProfilePaths.safe(file); return file;
    }
    public Optional<byte[]> read(UUID id) throws IOException {
        return profiles.withProfile(id, () -> {
            var bytes = AtomicFiles.read(file(id), MAX_BYTES);
            return bytes.isEmpty() ? Optional.empty() : Optional.of(normalize(bytes.orElseThrow()));
        });
    }
    public void save(UUID id, byte[] bytes) throws IOException {
        byte[] normalized = normalize(bytes);
        profiles.withProfile(id, () -> {
            Path target = file(id);
            AtomicFiles.write(target, "avatar-", normalized, MAX_BYTES, temporary -> AtomicFiles.replace(temporary, target));
            return true;
        });
    }
    public void remove(UUID id) throws IOException {
        profiles.withProfile(id, () -> { Files.deleteIfExists(file(id)); return true; });
    }
}
