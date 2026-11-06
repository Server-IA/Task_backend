package com.taskflow.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug hunt 3: con el frontend en una subruta ({@code FRONTEND_URL=https://example.com/app}) CORS comparaba esa URL con
 * el {@code Origin} del navegador (que nunca lleva ruta) y rechazaba todas las peticiones.
 */
class FrontendOriginTest {

    private static AppProperties withFrontend(String url) {
        return new AppProperties(null, null, url, "America/Bogota", null);
    }

    @Test
    void elOrigenEsEsquemaHostYPuertoSinLaRuta() {
        assertThat(withFrontend("https://example.com/app/").frontendOrigin()).isEqualTo("https://example.com");
        assertThat(withFrontend("https://Example.com:8443/taskflow/web").frontendOrigin())
                .isEqualTo("https://example.com:8443");
        assertThat(withFrontend("http://localhost:5173").frontendOrigin()).isEqualTo("http://localhost:5173");
        // Los enlaces de los correos siguen usando la URL completa
        assertThat(withFrontend("https://example.com/app/").frontendUrl()).isEqualTo("https://example.com/app");
    }

    @Test
    void corsAceptaElOrigenDelFrontendEnUnaSubruta() {
        CorsConfigurationSource source = new CorsConfig().corsConfigurationSource(
                withFrontend("https://example.com/app"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://example.com")).isEqualTo("https://example.com");
        assertThat(config.checkOrigin("https://evil.example")).isNull();
    }
}
