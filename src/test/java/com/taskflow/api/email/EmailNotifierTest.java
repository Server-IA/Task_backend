package com.taskflow.api.email;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.entity.User;
import com.taskflow.api.util.ClientInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** Renderiza cada correo con las plantillas reales, para que un error en una plantilla falle aquí y no al enviar. */
@ExtendWith(MockitoExtension.class)
class EmailNotifierTest {

    private static final String APP = "http://localhost:5173";

    @Mock
    private EmailService emailService;

    private EmailNotifier notifier;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("secreto-de-pruebas-con-mas-de-32-caracteres", 15, 7),
                new AppProperties.Mail("smtp", "", "TaskFlow <test@test.com>"),
                APP,
                "America/Bogota",
                new AppProperties.Tokens(24, 60, 7));
        notifier = new EmailNotifier(emailService, templateEngine, properties);
    }

    private static User user() {
        User user = new User();
        user.setEmail("ana@test.com");
        user.setFullName("Ana Pérez");
        return user;
    }

    private String sentHtml(String to, String subject) {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq(to), eq(subject), html.capture());
        return html.getValue();
    }

    @Test
    void verificacionLlevaElEnlaceElVencimientoYElLogo() {
        notifier.sendEmailVerification(user(), "tok-123");

        String html = sentHtml("ana@test.com", "Verifica tu cuenta de TaskFlow");
        assertThat(html)
                .contains("Verifica tu correo", "Ana Pérez", "vence en 24 h")
                .contains(APP + "/verify-email?token=tok-123")
                .contains(APP + "/email/logo.png");
    }

    @Test
    void restablecerLlevaElEnlaceYLosMinutos() {
        notifier.sendPasswordReset(user(), "tok-456");

        String html = sentHtml("ana@test.com", "Restablece tu contraseña de TaskFlow");
        assertThat(html).contains(APP + "/reset-password?token=tok-456", "vence en 60 min");
    }

    @Test
    void contrasenaCambiadaEnlazaARecuperarElAcceso() {
        notifier.sendPasswordChanged(user());

        String html = sentHtml("ana@test.com", "Tu contraseña de TaskFlow cambió");
        assertThat(html).contains("Tu contraseña cambió", APP + "/forgot-password");
    }

    @Test
    void alertaDeInicioDeSesionMuestraIpYDispositivo() {
        ClientInfo client = new ClientInfo("181.52.103.27", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0");
        notifier.sendLoginAlert(user(), client, Instant.parse("2026-10-27T04:42:00Z"));

        String html = sentHtml("ana@test.com", "Nuevo inicio de sesión en TaskFlow");
        assertThat(html).contains("181.52.103.27", "Chrome en Windows", "26 de octubre de 2026");
    }

    @Test
    void invitacionMuestraElWorkspaceConSuInicial() {
        notifier.sendWorkspaceInvitation("luis@test.com", "Ana Pérez", "equipo diseño", "tok-789");

        String html = sentHtml("luis@test.com", "Ana Pérez te invitó a equipo diseño en TaskFlow");
        assertThat(html)
                .contains(APP + "/invitations/tok-789", "vence en 7 días")
                .contains(">E</td>");
    }
}
