package com.taskflow.api.email;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.entity.User;
import com.taskflow.api.util.ClientInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Arma cada correo de la aplicación con su plantilla Thymeleaf y lo entrega a {@link EmailService}.
 * <ul>
 *   <li>Dentro de una transacción, el correo sale después del commit: si la operación falla (un registro duplicado,
 *       un cambio de contraseña que no se guarda), no se envía nada.</li>
 *   <li>Un fallo al encolar el correo (la cola llena porque el proveedor está caído) se registra y no rompe el login,
 *       el registro ni la invitación.</li>
 *   <li>Los enlaces solo se escriben en el log si los correos no salen y {@code app.mail.log-links} lo permite
 *       (desarrollo local): son secretos.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotifier {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, h:mm a", ES);

    /** Asunto de la invitación: sin el nombre de quien invita ni del workspace (los escribe el usuario). */
    static final String INVITATION_SUBJECT = "Te invitaron a un workspace en TaskFlow";

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
        send(email, INVITATION_SUBJECT, "invitation", link, Map.of(
                "inviter", inviterName,
                "workspace", workspaceName,
                "link", link,
                "days", properties.tokens().invitationDays()));
    }

    private void send(String to, String subject, String template, String actionLink, Map<String, Object> variables) {
        boolean logLink = properties.logMailLinksAlways() || (!properties.mail().enabled() && properties.logMailLinks());
        if (actionLink != null && logLink) {
            // Solo en desarrollo: sin proveedor de correo (o con MAIL_LOG_LINKS=true, por si el SMTP falla), el
            // enlace se muestra en el log para probar el flujo.
            log.info("[Desarrollo] Enlace del correo '{}': {}", subject, actionLink);
        }
        Context context = new Context(ES);
        context.setVariables(variables);
        // La plantilla base enlaza al front y toma de ahí el logo (public/email/logo.png)
        context.setVariable("appUrl", properties.frontendUrl());
        String html = templateEngine.process("email/" + template, context);
        afterCommit(() -> deliver(to, subject, html));
    }

    private void deliver(String to, String subject, String html) {
        try {
            emailService.send(to, subject, html);
        } catch (RuntimeException ex) {
            // Por ejemplo TaskRejectedException con la cola de correos llena: el correo se pierde, la operación no
            log.warn("No se pudo encolar el correo '{}': {}", subject, ex.toString());
        }
    }

    /** Ejecuta la acción al confirmar la transacción actual, o enseguida si no hay ninguna. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
