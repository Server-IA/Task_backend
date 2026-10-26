package com.taskflow.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public record Jwt(String secret, long accessExpirationMinutes, long refreshExpirationDays) {
    }

    /**
     * @param provider {@code resend} (producción, API de Resend) o {@code smtp} (Mailpit en local, Brevo, Gmail…)
     */
    public record Mail(String provider, String resendApiKey, String from) {

        public boolean usesSmtp() {
            return "smtp".equalsIgnoreCase(provider);
        }

        /**
         * Si los correos salen de verdad. Con SMTP siempre (el servidor lo define spring.mail.*);
         * con Resend solo si hay una API key real. Si no, solo se registran en el log.
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
}
