package com.taskflow.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Propiedades propias de la aplicación (prefijo {@code app} en application.yml).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Mail mail,
        String frontendUrl,
        String timezone,
        Tokens tokens
) {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /**
     * {@code FRONTEND_URL} sin espacios ni barra final: los enlaces de los correos se arman como
     * {@code frontendUrl + "/verify-email?…"}, y con {@code https://app.example.com/} salían con doble barra
     * ({@code https://app.example.com//verify-email}), una ruta que el frontend no reconoce.
     */
    public AppProperties {
        if (frontendUrl != null) {
            frontendUrl = frontendUrl.strip();
            while (frontendUrl.endsWith("/")) {
                frontendUrl = frontendUrl.substring(0, frontendUrl.length() - 1);
            }
        }
    }

    public record Jwt(String secret, long accessExpirationMinutes, long refreshExpirationDays) {
    }

    /**
     * @param provider {@code resend} (producción, API de Resend) o {@code smtp} (Mailpit en local, Brevo, Gmail…)
     * @param logLinks escribir en el log los enlaces de los correos que no salen (verificar, restablecer, invitar):
     *                 son secretos, así que solo para desarrollo. {@code null} (sin {@code MAIL_LOG_LINKS}) = solo si
     *                 {@code FRONTEND_URL} apunta a localhost
     */
    public record Mail(String provider, String resendApiKey, String from, Boolean logLinks) {

        @ConstructorBinding
        public Mail {
        }

        public Mail(String provider, String resendApiKey, String from) {
            this(provider, resendApiKey, from, null);
        }

        public boolean usesSmtp() {
            return "smtp".equalsIgnoreCase(provider);
        }

        /**
         * Si los correos salen de verdad. Con SMTP siempre (el servidor lo define spring.mail.*);
         * con Resend solo si hay una API key real. Si no, no se envían.
         */
        public boolean enabled() {
            if (usesSmtp()) {
                return true;
            }
            return resendApiKey != null && !resendApiKey.isBlank() && !resendApiKey.startsWith("re_xxx");
        }
    }

    /**
     * @param refreshReuseGraceSeconds margen para volver a usar un refresh token recién rotado sin que cuente como robo
     *                                 (dos recargas seguidas de la página). 0 lo desactiva
     */
    public record Tokens(long emailVerificationHours, long passwordResetMinutes, long invitationDays,
                         long refreshReuseGraceSeconds) {

        public Duration refreshReuseGrace() {
            return Duration.ofSeconds(Math.max(0, refreshReuseGraceSeconds));
        }
    }

    /**
     * El origen de {@code FRONTEND_URL} ({@code esquema://host[:puerto]}), el único que CORS deja llamar a la API. Un
     * origen nunca lleva ruta: con el frontend en una subruta ({@code https://example.com/app}) se comparaba
     * {@code https://example.com/app} con el {@code Origin: https://example.com} del navegador y se rechazaban todas las
     * peticiones. Los enlaces de los correos sí usan la URL completa.
     */
    public String frontendOrigin() {
        if (frontendUrl == null || frontendUrl.isBlank()) {
            return frontendUrl;
        }
        try {
            URI uri = URI.create(frontendUrl);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return frontendUrl;
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return frontendUrl;
        }
    }

    /** {@code FRONTEND_URL} apunta a esta máquina (desarrollo local). */
    public boolean localFrontend() {
        if (frontendUrl == null || frontendUrl.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(frontendUrl.trim()).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Si se escriben en el log los enlaces de los correos que no salen (ver {@link Mail#logLinks()}). */
    public boolean logMailLinks() {
        Boolean explicit = mail == null ? null : mail.logLinks();
        return explicit != null ? explicit : localFrontend();
    }

    /**
     * Si los enlaces van al log aunque haya proveedor de correo: solo con {@code MAIL_LOG_LINKS=true} explícito (en
     * desarrollo, para no perder el enlace si el SMTP falla). Vacío no basta.
     */
    public boolean logMailLinksAlways() {
        return mail != null && Boolean.TRUE.equals(mail.logLinks());
    }
}
