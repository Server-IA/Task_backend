package com.taskflow.api.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee las menciones que el frontend escribe en el cuerpo de un comentario con el formato
 * {@code @[Nombre Visible](id-del-usuario)}.
 */
public final class MentionParser {

    /** Máximo de usuarios distintos que se tienen en cuenta por comentario. */
    public static final int MAX_MENTIONS = 50;

    private static final Pattern MENTION = Pattern.compile(
            "@\\[([^\\]\\r\\n]{1,200})]\\(([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

    /** Imagen subida dentro del texto: {@code ![nombre](/files/<id>)}. */
    private static final Pattern IMAGE = Pattern.compile(
            "!\\[([^\\]\\r\\n]{0,255})]\\(/files/[0-9a-fA-F-]{36}\\)");

    private MentionParser() {
    }

    /**
     * El texto en una línea, con cada mención como {@code @Nombre}, cada imagen subida como {@code [imagen: nombre]}
     * y los espacios seguidos reducidos a uno, cortado con «…» si supera {@code maxLength} caracteres; {@code null} si
     * el texto es {@code null}.
     */
    public static String excerpt(String body, int maxLength) {
        if (body == null) {
            return null;
        }
        String withImages = IMAGE.matcher(body).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1).isBlank() ? "[imagen]" : "[imagen: " + match.group(1).strip() + "]"));
        String text = MENTION.matcher(withImages)
                .replaceAll(match -> Matcher.quoteReplacement("@" + match.group(1)))
                .replaceAll("\\s+", " ")
                .strip();
        return text.length() <= maxLength ? text : TextCut.prefix(text, maxLength).stripTrailing() + "…";
    }

    /** Ids mencionados, sin repetir y en orden de aparición (como máximo {@link #MAX_MENTIONS}). */
    public static Set<UUID> userIds(String body) {
        if (body == null || body.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find() && ids.size() < MAX_MENTIONS) {
            ids.add(UUID.fromString(matcher.group(2)));
        }
        return ids;
    }
}
