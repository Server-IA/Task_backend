package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Conflicto con el estado actual, por ejemplo un dato duplicado (409).
 */
public class ConflictException extends ApiException {

    /**
     * Dejar una tarea como trabajo abierto de una historia cerrada sin reabrirla: agregársela, convertir en tarea un
     * hijo suyo o sacar a una tarea suya de un estado completado o cancelado.
     */
    public static final String STORY_CLOSED = "STORY_CLOSED";

    /** Crear un workspace más de los que permite {@code WorkspaceServiceImpl.MAX_OWNED_WORKSPACES}. */
    public static final String WORKSPACE_LIMIT = "WORKSPACE_LIMIT";

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    public ConflictException(String message, String code) {
        super(HttpStatus.CONFLICT, message, code);
    }
}
