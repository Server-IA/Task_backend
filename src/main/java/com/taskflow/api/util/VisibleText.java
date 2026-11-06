package com.taskflow.api.util;

/**
 * Textos que escribe una persona (títulos, comentarios, elementos de checklist, notas): {@code @NotBlank},
 * {@link String#isBlank()} y {@link String#trim()} solo conocen los espacios de Java, así que un texto de puros espacios
 * duros (U+00A0) o de ancho cero (U+200B) pasaba la validación y se guardaba una tarea, un comentario o una nota que se
 * ve vacía. Aquí cuentan como espacios los mismos que en {@link HumanNames}.
 */
public final class VisibleText {

    /**
     * Clase de caracteres (para un {@code [...]} con {@code UNICODE_CHARACTER_CLASS}) de los espacios: los de Unicode
     * ({@code \s}: también el duro U+00A0, U+2007, U+202F y U+0085) más los invisibles que no lo son para Unicode:
     * espacio de ancho cero (U+200B), unión de palabras (U+2060) y BOM (U+FEFF). Los de unión (U+200C, U+200D) no:
     * arman emojis compuestos.
     */
    static final String SPACES = "\\s\\u200B\\u2060\\uFEFF";

    private VisibleText() {
    }

    /** El texto sin espacios (también los invisibles) al principio ni al final; {@code null} si es {@code null}. */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        // Con un recorrido y no con una expresión regular: «espacios$» sobre una descripción larga llena de espacios
        // probaba cada racha hasta el final (cuadrático)
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** {@code null}, vacío o hecho solo de espacios (también los invisibles). */
    public static boolean isBlank(String text) {
        return text == null || strip(text).isEmpty();
    }

    /** Un espacio de {@link #SPACES} (todos están en el plano básico: basta un {@code char}). */
    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || c == '\u0085' || c == '​' || c == '⁠' || c == '﻿';
    }
}
