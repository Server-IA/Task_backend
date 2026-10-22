package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Credenciales o token inválidos (401).
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
