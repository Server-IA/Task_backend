package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Recurso no encontrado (404).
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
