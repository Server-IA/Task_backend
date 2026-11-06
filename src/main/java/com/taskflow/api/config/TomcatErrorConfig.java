package com.taskflow.api.config;

import com.taskflow.api.exception.JsonErrorReportValve;
import com.taskflow.api.util.JsonResponseWriter;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Los errores que Tomcat resuelve antes de la aplicación (URL mal formada) responden el JSON de la API y no la página
 * HTML de Tomcat (ver {@link JsonErrorReportValve}).
 */
@Configuration
public class TomcatErrorConfig {

    /**
     * Va después del {@code ErrorReportValve} que agrega Spring Boot (este customizer corre después del suyo): el de
     * más adentro informa primero y el otro ya no escribe nada.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonErrorReportValve(JsonResponseWriter writer) {
        return factory -> factory.addContextCustomizers(context ->
                context.getParent().getPipeline().addValve(new JsonErrorReportValve(writer)));
    }
}
