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

    private MentionParser() {
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
