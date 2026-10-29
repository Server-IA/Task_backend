package com.taskflow.api.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Excepción base de la aplicación: cada subclase indica el código HTTP con el que se responde. {@code code} (opcional)
 * distingue un error de otros con el mismo estado, por ejemplo {@code STORY_CLOSED}; viaja en la respuesta.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    protected ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
