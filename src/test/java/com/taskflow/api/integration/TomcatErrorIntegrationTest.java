package com.taskflow.api.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bug hunt 3: las URLs que Tomcat rechaza antes de llegar a la aplicación (un escape {@code %} mal formado, un
 * {@code %00}, un {@code |} sin escapar) respondían la página HTML de Tomcat; ahora, el JSON de la API. Con un servidor de
 * verdad: estas peticiones no pasan por MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TomcatErrorIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("app.jwt.secret", () -> "secreto-de-integracion-con-mas-de-32-caracteres");
        registry.add("app.mail.provider", () -> "resend");
        registry.add("app.mail.resend-api-key", () -> "");
        registry.add("app.frontend-url", () -> "http://localhost:5173");
        registry.add("app.maintenance.cron", () -> "-");
    }

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(TestDatabase.available(), "PostgreSQL no disponible (DB_URL)");
    }

    @LocalServerPort
    private int port;

    /** Petición HTTP cruda (un cliente HTTP normal no deja mandar estas URLs); devuelve la respuesta completa. */
    private String raw(String path) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            in.transferTo(response);
            return response.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void unaUrlMalFormadaRespondeElJsonDeLaApi() throws IOException {
        for (String path : new String[] {"/api/v1/workspaces/%zz", "/api/v1/workspaces/a%", "/api/v1/x%00y",
                "/api/v1/workspaces/a|b"}) {
            String response = raw(path);
            assertThat(response).as(path).startsWith("HTTP/1.1 400");
            assertThat(response).as(path).containsIgnoringCase("Content-Type: application/json")
                    .contains("\"status\":400").contains("\"message\":\"La petición no es válida\"")
                    .doesNotContain("<html");
        }
    }

    @Test
    void losErroresDeLaAplicacionNoCambian() throws IOException {
        String unauthenticated = raw("/api/v1/users/me");
        assertThat(unauthenticated).startsWith("HTTP/1.1 401").contains("\"message\":\"Debes iniciar sesión\"");
        String notFound = raw("/api/v1/no-existe");
        assertThat(notFound).startsWith("HTTP/1.1 401").doesNotContain("<html");
    }
}
