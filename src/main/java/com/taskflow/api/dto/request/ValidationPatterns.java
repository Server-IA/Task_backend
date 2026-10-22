package com.taskflow.api.dto.request;

/**
 * Expresiones regulares compartidas por varios DTOs.
 */
public final class ValidationPatterns {

    /** Color hexadecimal, por ejemplo {@code #3F76FF}. */
    public static final String HEX_COLOR = "^#[0-9A-Fa-f]{6}$";

    /** Identificador del proyecto: empieza por letra, solo letras y números, hasta 12 caracteres. */
    public static final String PROJECT_IDENTIFIER = "^[A-Za-z][A-Za-z0-9]{0,11}$";

    private ValidationPatterns() {
    }
}
