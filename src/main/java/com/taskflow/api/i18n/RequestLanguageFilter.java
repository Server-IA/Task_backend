package com.taskflow.api.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Deja el idioma de la petición ({@code Accept-Language}) disponible en {@link LocaleContextHolder} desde antes de la
 * cadena de seguridad: así los 401, 403 y 429 que se responden en los filtros salen en el idioma pedido. Dentro de
 * Spring MVC lo resuelve igual el {@code LocaleResolver} de {@link I18nConfig}.
 * <p>
 * Va después de {@code RequestContextFilter} (-105), que pone el idioma según el contenedor (sin cabecera, el del
 * sistema), y antes de Spring Security (-100).
 */
@Component
@Order(RequestLanguageFilter.ORDER)
public class RequestLanguageFilter extends OncePerRequestFilter {

    static final int ORDER = -103;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LocaleContext previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(of(request).locale());
        try {
            chain.doFilter(request, response);
        } finally {
            LocaleContextHolder.setLocaleContext(previous);
        }
    }

    /** El idioma que pide la petición. */
    public static Language of(HttpServletRequest request) {
        return Language.fromHeader(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }
}
