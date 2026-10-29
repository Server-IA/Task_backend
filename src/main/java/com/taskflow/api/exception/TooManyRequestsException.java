package com.taskflow.api.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Demasiados intentos en poco tiempo (429). La respuesta lleva la cabecera {@code Retry-After}.
 */
@Getter
public class TooManyRequestsException extends ApiException {

    /** Segundos hasta poder intentarlo de nuevo. */
    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static String message(long retryAfterSeconds) {
        return "Demasiados intentos. Intenta de nuevo en " + retryAfterSeconds + " segundos";
    }
}
