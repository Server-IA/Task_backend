package com.taskflow.api.util;

import java.util.regex.Pattern;

/**
 * Nombres que escribe una persona (de usuario, workspace, proyecto, vista…): sin espacios al principio ni al final y
 * con los espacios repetidos de adentro (también tabulaciones, saltos de línea o espacios duros) como uno solo. Así
 * «Ana   Núñez» no se ve distinto de «Ana Núñez» ni cuenta como otro nombre al buscar duplicados.
 */
public final class HumanNames {

    private static final Pattern INNER_SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private HumanNames() {
    }

    /** El nombre limpio; {@code null} si es {@code null}. */
    public static String clean(String name) {
        if (name == null) {
            return null;
        }
        return INNER_SPACES.matcher(name).replaceAll(" ").strip();
    }
}
