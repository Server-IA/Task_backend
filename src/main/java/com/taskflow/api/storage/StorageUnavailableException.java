package com.taskflow.api.storage;

import com.taskflow.api.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * El almacenamiento de archivos no está configurado o no responde (503).
 */
public class StorageUnavailableException extends ApiException {

    public static final String NOT_CONFIGURED = "La subida de archivos no está disponible en este momento";

    public StorageUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, "STORAGE_UNAVAILABLE");
    }
}
