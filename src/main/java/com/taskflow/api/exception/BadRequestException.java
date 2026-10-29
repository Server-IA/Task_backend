package com.taskflow.api.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Petición inválida por una regla de negocio (400). Con {@code errors}, el error es de campos concretos y la
 * respuesta los lleva en el mismo formato que los de validación ({@code errors: {campo: mensaje}}).
 */
@Getter
public class BadRequestException extends ApiException {

    private final Map<String, String> errors;

    public BadRequestException(String message) {
        this(message, null);
    }

    public BadRequestException(String message, Map<String, String> errors) {
        super(HttpStatus.BAD_REQUEST, message);
        this.errors = errors == null ? null : Map.copyOf(errors);
    }

    /** Error de un solo campo: el mensaje general y el del campo son el mismo. */
    public static BadRequestException forField(String field, String message) {
        return new BadRequestException(message, Map.of(field, message));
    }
}
