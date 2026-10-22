package com.taskflow.api.dto.request;

/**
 * Reglas comunes de contraseña: al menos una letra y un número.
 */
final class PasswordRules {

    static final String REGEX = "^(?=.*[A-Za-z])(?=.*[0-9]).+$";
    static final String MESSAGE = "La contraseña debe tener al menos una letra y un número";

    private PasswordRules() {
    }
}
