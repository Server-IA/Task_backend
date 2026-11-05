package com.taskflow.api.exception;

import com.taskflow.api.dto.response.ErrorResponse;
import com.taskflow.api.util.JsonResponseWriter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Los errores que no pasan por {@link GlobalExceptionHandler} (los que se resuelven con {@code sendError} antes de
 * llegar a un controlador: una URL que rechaza el firewall de Spring Security como {@code //}, {@code ;x=1} o
 * {@code %2e%2e}, o una excepción en un filtro) los atiende {@code /error}. El de Spring Boot respondía otro formato
 * ({@code timestamp, status, error, path}, sin {@code message}); este responde el común {@link ErrorResponse}, siempre en
 * JSON (también si el navegador pide {@code text/html}: sin negociar el tipo, que daría 406).
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class ApiErrorController implements ErrorController {

    private final JsonResponseWriter writer;

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = status(request);
        writer.writeError(response, status.value(), message(status));
    }

    /**
     * El estado del error que llegó aquí. Sin él, alguien pidió {@code /error} directamente (no es un error reenviado):
     * esa ruta no existe para la API (404; antes respondía 500, un error del servidor que no ocurrió).
     */
    static HttpStatus status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code == null && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) == null) {
            return HttpStatus.NOT_FOUND;
        }
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    static String message(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "Debes iniciar sesión";
            case FORBIDDEN -> "No tienes permiso para realizar esta acción";
            case NOT_FOUND -> "Ruta no encontrada";
            case METHOD_NOT_ALLOWED -> "Método no permitido en esta ruta";
            case CONTENT_TOO_LARGE -> "La petición es demasiado grande";
            case TOO_MANY_REQUESTS -> "Demasiadas solicitudes. Inténtalo más tarde";
            default -> status.is4xxClientError() ? "La petición no es válida" : "Ocurrió un error inesperado";
        };
    }
}
