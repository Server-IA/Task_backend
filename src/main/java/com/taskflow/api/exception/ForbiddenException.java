package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * El usuario no tiene permiso para la acción (403).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
