package com.taskflow.api.exception;

import com.taskflow.api.util.JsonResponseWriter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /error} (URLs que rechaza el firewall, excepciones en filtros) responde el formato común de error, con
 * {@code message}, y no el de Spring Boot ({@code error}, {@code path}).
 */
class ApiErrorControllerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApiErrorController controller = new ApiErrorController(new JsonResponseWriter(mapper));

    private MockHttpServletResponse errorFor(Object statusCode, String accept) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.addHeader("Accept", accept);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusCode);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1//users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);
        return response;
    }

    private JsonNode body(MockHttpServletResponse response) throws IOException {
        return mapper.readTree(response.getContentAsString());
    }

    @Test
    void unaUrlRechazadaPorElFirewallResponde400ConElFormatoComun() throws IOException {
        MockHttpServletResponse response = errorFor(400, "application/json");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = body(response);
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("message").asString()).isEqualTo("La petición no es válida");
        assertThat(body.has("timestamp")).isTrue();
        assertThat(body.has("error")).isFalse();
        assertThat(body.has("path")).isFalse();
    }

    @Test
    void enJsonAunqueElNavegadorPidaHtml() throws IOException {
        MockHttpServletResponse response = errorFor(400, "text/html");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    void conservaElEstadoDeCadaError() throws IOException {
        assertThat(body(errorFor(401, "*/*")).get("message").asString()).isEqualTo("Debes iniciar sesión");
        assertThat(body(errorFor(403, "*/*")).get("message").asString())
                .isEqualTo("No tienes permiso para realizar esta acción");
        assertThat(body(errorFor(404, "*/*")).get("message").asString()).isEqualTo("Ruta no encontrada");
        assertThat(errorFor(413, "*/*").getStatus()).isEqualTo(413);
        assertThat(body(errorFor(418, "*/*")).get("message").asString()).isEqualTo("La petición no es válida");
        assertThat(body(errorFor(503, "*/*")).get("message").asString()).isEqualTo("Ocurrió un error inesperado");
    }

    @Test
    void sinEstadoOConUnoQueNoEsErrorEs500() throws IOException {
        assertThat(errorFor(null, "*/*").getStatus()).isEqualTo(500);
        assertThat(errorFor(200, "*/*").getStatus()).isEqualTo(500);
        assertThat(errorFor("x", "*/*").getStatus()).isEqualTo(500);
    }
}
