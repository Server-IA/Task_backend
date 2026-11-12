package com.taskflow.api.util;

import com.taskflow.api.dto.response.ErrorResponse;
import com.taskflow.api.i18n.Language;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Escribe un {@link ErrorResponse} desde los filtros, donde no llega el {@code @RestControllerAdvice}.
 */
@Component
@RequiredArgsConstructor
public class JsonResponseWriter {

    private final ObjectMapper objectMapper;

    /** En el idioma de la petición en curso. */
    public void writeError(HttpServletResponse response, int status, String message) throws IOException {
        writeError(response, status, message, Language.current());
    }

    /** En el idioma dado: para lo que responde antes de los filtros (el valve de Tomcat). */
    public void writeError(HttpServletResponse response, int status, String message, Language language)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(status, message, null, null, language));
    }
}
