package com.handoffly.attachment.storage;

import org.springframework.core.io.Resource;

/**
 * Blob storage abstraction. Files live here (local disk in dev, object storage such as
 * S3 in production); only metadata lives in the database. Business logic depends on
 * this interface, so swapping the backend never touches the handoff/attachment logic.
 */
public interface StorageService {

    /**
     * Persists the given bytes and returns an opaque, system-generated storage key.
     * The key is never derived from user input, avoiding path-traversal risk.
     */
    String store(byte[] data, String contentType);

    /** Loads previously stored content as a readable resource. */
    Resource load(String key);

    /** Deletes stored content; no-op if it does not exist. */
    void delete(String key);
}
