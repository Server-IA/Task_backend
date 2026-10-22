package com.taskflow.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Petición inválida por una regla de negocio (400).
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
