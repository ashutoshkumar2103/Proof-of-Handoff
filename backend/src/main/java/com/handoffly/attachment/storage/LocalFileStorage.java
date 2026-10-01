package com.handoffly.attachment.storage;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Local-filesystem {@link StorageService} for development. Keys are random UUIDs stored
 * in a two-level fan-out directory. Every resolved path is verified to stay within the
 * configured base directory to defend against path traversal.
 */
@Component
@ConditionalOnProperty(name = "handoffly.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements StorageService {

    private final Path baseDir;

    public LocalFileStorage(HandOfflyProperties properties) {
        this.baseDir = Paths.get(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory: " + baseDir, e);
        }
    }

    @Override
    public String store(byte[] data, String contentType) {
        String key = UUID.randomUUID().toString().replace("-", "");
        Path target = resolveKey(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file", e);
        }
        return key;
    }

    @Override
    public Resource load(String key) {
        Path path = resolveKey(key);
        if (!Files.exists(path)) {
            throw new NotFoundException("File content not found.");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolveKey(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file", e);
        }
    }

    /** Two-level fan-out (aa/bb/aabb...) with a strict containment check. */
    private Path resolveKey(String key) {
        String safeKey = key.replaceAll("[^a-zA-Z0-9]", "");
        if (safeKey.length() < 4) {
            throw new NotFoundException("File content not found.");
        }
        Path resolved = baseDir
                .resolve(safeKey.substring(0, 2))
                .resolve(safeKey.substring(2, 4))
                .resolve(safeKey)
                .normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new NotFoundException("File content not found.");
        }
        return resolved;
    }
}
