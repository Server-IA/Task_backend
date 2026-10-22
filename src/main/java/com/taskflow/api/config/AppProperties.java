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

    public record Mail(String resendApiKey, String from) {

        /** Si no hay API key real de Resend, los correos solo se registran en el log. */
        public boolean enabled() {
            return resendApiKey != null && !resendApiKey.isBlank() && !resendApiKey.startsWith("re_xxx");
        }
    }

    public record Tokens(long emailVerificationHours, long passwordResetMinutes, long invitationDays) {
    }
}
