package com.taskflow.api.util;

import com.taskflow.api.dto.response.ErrorResponse;
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

    public void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(status, message));
    }
}
