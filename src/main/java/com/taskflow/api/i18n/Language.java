package com.taskflow.api.i18n;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * Idiomas en los que responde la API. El español es el de origen: los textos del código están en español y son la
 * clave de su traducción ({@link Translations}).
 */
public enum Language {
    ES("es", Locale.forLanguageTag("es")),
    EN("en", Locale.ENGLISH);

    private final String code;
    private final Locale locale;

    Language(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    /** «es» o «en»: lo que se guarda en la cuenta y viaja en {@code Accept-Language}. */
    public String code() {
        return code;
    }

    public Locale locale() {
        return locale;
    }

    /** El texto (escrito en español) en este idioma; tal cual si es español o no tiene traducción. */
    public String t(String spanish) {
        return Translations.translate(spanish, this);
    }

    /** El de una cabecera {@code Accept-Language}: lo que empiece por «en» es inglés; lo demás (o sin cabecera), español. */
    public static Language fromHeader(String acceptLanguage) {
        if (acceptLanguage == null) {
            return ES;
        }
        return acceptLanguage.strip().toLowerCase(Locale.ROOT).startsWith(EN.code) ? EN : ES;
    }

    /** El de un código guardado («es» o «en»); {@code null} si no es ninguno de los dos. */
    public static Language fromCode(String code) {
        for (Language language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return null;
    }

    public static Language of(Locale locale) {
        return locale != null && EN.code.equals(locale.getLanguage()) ? EN : ES;
    }

    /**
     * El de la petición en curso ({@link RequestLanguageFilter} lo deja en {@link LocaleContextHolder}). Fuera de una
     * petición (tareas programadas, pruebas) es español, sin mirar el idioma del sistema.
     */
    public static Language current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        return of(context == null ? null : context.getLocale());
    }
}
