package com.taskflow.api.config;

import com.resend.Resend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResendConfig {

    @Bean
    public Resend resend(AppProperties properties) {
        checkMailSettings(properties);
        String apiKey = properties.mail().resendApiKey();
        return new Resend(apiKey == null ? "" : apiKey);
    }

    /**
     * Fuera de desarrollo (un {@code FRONTEND_URL} que no es localhost), Resend sin API key no arranca: los correos de
     * verificación, contraseña e invitación no saldrían y nadie podría registrarse ni recuperar su cuenta.
     */
    static void checkMailSettings(AppProperties properties) {
        AppProperties.Mail mail = properties.mail();
        if (mail != null && !mail.usesSmtp() && !mail.enabled() && !properties.localFrontend()) {
            throw new IllegalStateException("MAIL_PROVIDER=resend necesita RESEND_API_KEY cuando FRONTEND_URL no es "
                    + "localhost (" + properties.frontendUrl() + "). Configura la API key o usa MAIL_PROVIDER=smtp.");
        }
    }
}
