package com.taskflow.api.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * El idioma de cada petición dentro de Spring MVC: el de {@code Accept-Language} con la regla de
 * {@link Language#fromHeader} (sin cabecera es español, no el idioma del sistema). Y los textos de los correos.
 */
@Configuration
public class I18nConfig {

    /**
     * Textos de las plantillas de correo: {@code i18n/email.properties} (español, el idioma por defecto) y
     * {@code i18n/email_en.properties}. Sin recurrir al idioma del sistema: un correo en español no sale en inglés
     * porque el servidor esté en inglés.
     */
    public static MessageSource emailMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/email");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean
    public MessageSource messageSource() {
        return emailMessages();
    }

    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    public LocaleResolver localeResolver() {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return RequestLanguageFilter.of(request).locale();
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                throw new UnsupportedOperationException("El idioma lo fija la cabecera Accept-Language");
            }
        };
    }
}
