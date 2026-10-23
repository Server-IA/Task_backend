package com.taskflow.api.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public final class SlugUtils {

    /** Minúsculas, números y guiones simples entre palabras, por ejemplo {@code mi-equipo-2}. */
    public static final String SLUG_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";

    private static final int MAX_LENGTH = 48;

    /** Slugs que chocan con rutas del frontend ({@code /login}, {@code /profile}…). */
    private static final Set<String> RESERVED = Set.of(
            "login", "register", "verify-email", "forgot-password", "reset-password", "invitations",
            "onboarding", "profile", "notifications", "settings", "api", "admin", "assets", "new");

    private SlugUtils() {
    }

    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }

    /** Convierte un texto en slug: «Equipo Diseño» → «equipo-diseno». */
    public static String slugify(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder withoutAccents = new StringBuilder(normalized.length());
        for (char c : normalized.toCharArray()) {
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                withoutAccents.append(c);
            }
        }
        String slug = withoutAccents.toString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }
}
