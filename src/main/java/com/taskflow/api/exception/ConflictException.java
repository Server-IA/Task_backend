package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Conflicto con el estado actual, por ejemplo un dato duplicado (409).
 */
public class ConflictException extends ApiException {

    /** Agregar una tarea a una historia cerrada sin reabrirla. */
    public static final String STORY_CLOSED = "STORY_CLOSED";

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public ConflictException(String message, String code) {
        super(HttpStatus.CONFLICT, message, code);
    }
}
