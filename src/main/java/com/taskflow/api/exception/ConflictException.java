package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Conflicto con el estado actual, por ejemplo un dato duplicado (409).
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
