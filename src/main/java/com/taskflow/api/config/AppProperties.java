package com.taskflow.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
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

    public record Tokens(long emailVerificationHours, long passwordResetMinutes, long invitationDays) {
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
