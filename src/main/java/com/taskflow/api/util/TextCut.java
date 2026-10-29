package com.taskflow.api.util;

/**
 * Recorta textos para extractos y títulos sin partir un carácter en dos. Un emoji (y cualquier carácter fuera del plano
 * básico) ocupa dos {@code char} en Java: cortar entre ellos deja medio carácter, que PostgreSQL guarda como «?» y el
 * JSON envía como un {@code \ud83d} suelto («�» en el navegador).
 */
public final class TextCut {

    private TextCut() {
    }

    /**
     * Los primeros {@code maxLength} {@code char} de {@code text} (o uno menos si el corte cae en medio de un par
     * sustituto); el texto tal cual si ya cabe.
     */
    public static String prefix(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }
}
