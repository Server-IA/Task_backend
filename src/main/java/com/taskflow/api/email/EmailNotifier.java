package com.taskflow.api.email;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.entity.User;
import com.taskflow.api.util.ClientInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Arma cada correo de la aplicación con su plantilla Thymeleaf y lo entrega a {@link EmailService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotifier {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, h:mm a", ES);

    private final EmailService emailService;
    private final TemplateEngine templateEngine;
    private final AppProperties properties;

    public void sendEmailVerification(User user, String token) {
        String link = properties.frontendUrl() + "/verify-email?token=" + token;
        send(user.getEmail(), "Verifica tu cuenta de TaskFlow", "verify-email", link, Map.of(
                "name", user.getFullName(),
                "link", link,
                "hours", properties.tokens().emailVerificationHours()));
    }

    public void sendLoginAlert(User user, ClientInfo client, Instant when) {
        String link = properties.frontendUrl() + "/forgot-password";
        send(user.getEmail(), "Nuevo inicio de sesión en TaskFlow", "login-alert", null, Map.of(
                "name", user.getFullName(),
                "date", DATE_FORMAT.format(when.atZone(ZoneId.of(properties.timezone()))),
                "ip", client.ip(),
                "device", client.device(),
                "link", link));
    }

    public void sendPasswordReset(User user, String token) {
        String link = properties.frontendUrl() + "/reset-password?token=" + token;
        send(user.getEmail(), "Restablece tu contraseña de TaskFlow", "reset-password", link, Map.of(
                "name", user.getFullName(),
                "link", link,
                "minutes", properties.tokens().passwordResetMinutes()));
    }

    public void sendPasswordChanged(User user) {
        String link = properties.frontendUrl() + "/forgot-password";
        send(user.getEmail(), "Tu contraseña de TaskFlow cambió", "password-changed", null, Map.of(
                "name", user.getFullName(),
                "date", DATE_FORMAT.format(Instant.now().atZone(ZoneId.of(properties.timezone()))),
                "link", link));
    }

    public void sendWorkspaceInvitation(String email, String inviterName, String workspaceName, String token) {
        String link = properties.frontendUrl() + "/invitations/" + token;
        send(email, inviterName + " te invitó a " + workspaceName + " en TaskFlow", "invitation", link, Map.of(
                "inviter", inviterName,
                "workspace", workspaceName,
                "link", link,
                "days", properties.tokens().invitationDays()));
    }

    private void send(String to, String subject, String template, String actionLink, Map<String, Object> variables) {
        if (!properties.mail().enabled() && actionLink != null) {
            // Sin Resend configurado, el enlace se muestra en el log para poder probar el flujo en local.
            log.info("[Desarrollo] Enlace del correo '{}' para {}: {}", subject, to, actionLink);
        }
        Context context = new Context(ES);
        context.setVariables(variables);
        String html = templateEngine.process("email/" + template, context);
        emailService.send(to, subject, html);
    }
}
