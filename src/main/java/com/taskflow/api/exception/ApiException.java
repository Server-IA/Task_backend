package com.taskflow.api.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Excepción base de la aplicación: cada subclase indica el código HTTP con el que se responde.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
