package com.taskflow.api.security;

import com.taskflow.api.util.JsonResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Respuestas 401 y 403 de Spring Security con el formato común de error.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonResponseWriter writer;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String message = request.getAttribute(JwtAuthFilter.INVALID_TOKEN_ATTRIBUTE) != null
                ? "Token inválido o expirado"
                : "Debes iniciar sesión";
        writer.writeError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writer.writeError(response, HttpServletResponse.SC_FORBIDDEN, "No tienes permiso para realizar esta acción");
    }
}
