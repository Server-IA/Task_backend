package com.taskflow.api.report;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * Texto que se puede escribir con las fuentes estándar de un PDF (Helvetica y Courier, codificación Windows-1252):
 * tienen las tildes, la ñ, «», – y …, pero no flechas, emojis ni otros alfabetos. Lo que no se puede escribir se
 * reemplaza por algo parecido o se quita: sin esto, un título con un emoji dejaba un carácter roto en el reporte.
 */
public final class PdfText {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private PdfText() {
    }

    /** El texto en una línea, solo con caracteres que el PDF puede mostrar; {@code ""} si es {@code null}. */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        CharsetEncoder encoder = WINDOWS_1252.newEncoder();
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String replacement = replacement(codePoint);
            if (replacement != null) {
                result.append(replacement);
            } else if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                result.append(' ');
            } else if (Character.isBmpCodePoint(codePoint) && !Character.isISOControl(codePoint)
                    && encoder.canEncode((char) codePoint)) {
                result.append((char) codePoint);
            }
            // Lo demás (emojis, otros alfabetos, caracteres de control) no se puede dibujar: se omite
        });
        return result.toString().replaceAll(" {2,}", " ").strip();
    }

    /** Equivalente legible de los símbolos más comunes que la fuente no tiene; {@code null} si no hay uno. */
    private static String replacement(int codePoint) {
        return switch (codePoint) {
            case 0x2192, 0x21D2, 0x279C, 0x2794 -> "->";
            case 0x2190, 0x21D0 -> "<-";
            case 0x2194 -> "<->";
            case 0x2713, 0x2714, 0x2705 -> "[x]";
            case 0x2717, 0x2718, 0x274C -> "[ ]";
            case 0x2264 -> "<=";
            case 0x2265 -> ">=";
            case 0x2260 -> "!=";
            case 0x2212 -> "-";
            case 0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0xFE0F -> "";
            default -> null;
        };
    }
}
