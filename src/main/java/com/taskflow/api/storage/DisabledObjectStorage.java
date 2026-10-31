package com.taskflow.api.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Sin credenciales de R2: la API funciona, pero no se puede subir ni ver archivos (503) y no se borra nada (las claves
 * por borrar esperan en {@code storage_deletions}).
 */
public class DisabledObjectStorage implements ObjectStorage {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public PresignedRequest presignUpload(String key, String contentType, long size, Duration ttl) {
        throw unavailable();
    }

    @Override
    public PresignedRequest presignDownload(String key, String contentType, String fileName, boolean inline,
                                            Duration ttl) {
        throw unavailable();
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        throw unavailable();
    }

    @Override
    public byte[] readPrefix(String key, int bytes) {
        throw unavailable();
    }

    @Override
    public void delete(String key) {
        throw unavailable();
    }

    private static StorageUnavailableException unavailable() {
        return new StorageUnavailableException(StorageUnavailableException.NOT_CONFIGURED);
    }
}
