package com.taskflow.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los enlaces de los correos (secretos) solo van al log en desarrollo, y en producción Resend sin API key no arranca.
 */
class MailSettingsTest {

    private static AppProperties properties(String provider, String apiKey, Boolean logLinks, String frontendUrl) {
        return new AppProperties(null, new AppProperties.Mail(provider, apiKey, "TaskFlow <a@b.c>", logLinks),
                frontendUrl, "America/Bogota", null);
    }

    @Test
    void sinMailLogLinksLosEnlacesSoloVanAlLogConElFrontendEnLocalhost() {
        assertThat(properties("resend", "", null, "http://localhost:5173").logMailLinks()).isTrue();
        assertThat(properties("resend", "", null, "http://127.0.0.1:5174").logMailLinks()).isTrue();
        assertThat(properties("resend", "", null, "https://taskflow.example.com").logMailLinks()).isFalse();
    }

    @Test
    void mailLogLinksExplicitoGana() {
        assertThat(properties("smtp", "", false, "http://localhost:5173").logMailLinks()).isFalse();
        assertThat(properties("smtp", "", true, "https://taskflow.example.com").logMailLinks()).isTrue();
    }

    @Test
    void soloMailLogLinksTrueExplicitoEscribeElEnlaceAunqueElCorreoSalga() {
        assertThat(properties("smtp", "", true, "http://localhost:5173").logMailLinksAlways()).isTrue();
        assertThat(properties("smtp", "", null, "http://localhost:5173").logMailLinksAlways()).isFalse();
        assertThat(properties("smtp", "", false, "http://localhost:5173").logMailLinksAlways()).isFalse();
    }

    @Test
    void resendSinApiKeyFueraDeLocalhostNoArranca() {
        assertThatThrownBy(() -> ResendConfig.checkMailSettings(
                properties("resend", "", null, "https://taskflow.example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
        assertThatThrownBy(() -> ResendConfig.checkMailSettings(
                properties("resend", "re_xxxxxxxxx", null, "https://taskflow.example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enLocalConApiKeyOConSmtpArranca() {
        assertThatCode(() -> ResendConfig.checkMailSettings(properties("resend", "", null, "http://localhost:5173")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ResendConfig.checkMailSettings(
                properties("resend", "re_real_key", null, "https://taskflow.example.com")))
                .doesNotThrowAnyException();
        assertThatCode(() -> ResendConfig.checkMailSettings(
                properties("smtp", "", null, "https://taskflow.example.com")))
                .doesNotThrowAnyException();
    }
}
