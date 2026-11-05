package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;

import java.util.regex.Pattern;

/**
 * Nombres que escribe una persona (de usuario, workspace, proyecto, vista…): sin espacios al principio ni al final y
 * con los espacios repetidos de adentro (también tabulaciones, saltos de línea, espacios duros o espacios de ancho
 * cero) como uno solo. Así «Ana   Núñez» no se ve distinto de «Ana Núñez» ni cuenta como otro nombre al buscar
 * duplicados.
 */
public final class HumanNames {

    static final String EMPTY = "El nombre no puede estar vacío";

    /**
     * Los espacios de Unicode ({@code \s} con {@code UNICODE_CHARACTER_CLASS}: también el duro U+00A0, U+2007, U+202F
     * y U+0085) más los invisibles que no lo son para Unicode: espacio de ancho cero (U+200B), unión de palabras
     * (U+2060) y BOM (U+FEFF). Los de unión (U+200C, U+200D) no: arman emojis compuestos.
     */
    private static final Pattern INNER_SPACES = Pattern.compile("[\\s\\u200B\\u2060\\uFEFF]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private HumanNames() {
    }

    /** El nombre limpio; {@code null} si es {@code null}. */
    public static String clean(String name) {
        if (name == null) {
            return null;
        }
        return INNER_SPACES.matcher(name).replaceAll(" ").strip();
    }

    /**
     * El nombre limpio, que no puede quedar vacío (400 del campo {@code field}). {@code @NotBlank} y
     * {@link String#isBlank()} solo miran los espacios de Java: un nombre de puros espacios duros (U+00A0) o de ancho
     * cero los pasaba y se guardaba vacío.
     */
    public static String required(String name, String field) {
        String cleaned = clean(name);
        if (cleaned == null || cleaned.isEmpty()) {
            throw BadRequestException.forField(field, EMPTY);
        }
        return cleaned;
    }
}
