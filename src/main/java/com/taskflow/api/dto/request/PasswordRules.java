package com.taskflow.api.dto.request;

/**
 * Reglas comunes de contraseña: al menos una letra y un número.
 */
final class PasswordRules {

    static final String REGEX = "^(?=.*[A-Za-z])(?=.*[0-9]).+$";
    static final String MESSAGE = "La contraseña debe tener al menos una letra y un número";

    /** Límite de BCrypt, en bytes (UTF-8). */
    static final int MAX_BYTES = 72;
    static final String BYTES_MESSAGE =
            "La contraseña es demasiado larga: con tildes, ñ o emojis caben menos de 72 caracteres";

    private PasswordRules() {
    }
}
