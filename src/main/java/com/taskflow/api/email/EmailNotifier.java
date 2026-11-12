package com.taskflow.api.email;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.entity.User;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.util.ClientInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
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
 *   <li>Cada correo va en el idioma de quien lo recibe: el de su cuenta ({@code user.language}) o, si nunca lo eligió,
 *       el de la petición que lo dispara. Hay una sola plantilla por correo; sus textos y el asunto salen de
 *       {@code i18n/email*.properties}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotifier {

    /** Español de Colombia: las fechas salen «26 de octubre de 2026, 11:42 p. m.». */
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_FORMAT_ES =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, h:mm a", ES);
    private static final DateTimeFormatter DATE_FORMAT_EN =
            DateTimeFormatter.ofPattern("MMMM d, yyyy, h:mm a", Language.EN.locale());

    private final EmailService emailService;
    private final TemplateEngine templateEngine;
    private final AppProperties properties;
    /** Textos de los correos ({@code i18n/email*.properties}): de aquí sale el asunto. */
    private final MessageSource messages;

    public void sendEmailVerification(User user, String token) {
        String link = properties.frontendUrl() + "/verify-email?token=" + token;
        send(user.getEmail(), languageOf(user), "verify.subject", "verify-email", link, Map.of(
                "name", user.getFullName(),
                "link", link,
                "hours", properties.tokens().emailVerificationHours()));
    }

    public void sendLoginAlert(User user, ClientInfo client, Instant when) {
        String link = properties.frontendUrl() + "/forgot-password";
        Language language = languageOf(user);
        send(user.getEmail(), language, "login.subject", "login-alert", null, Map.of(
                "name", user.getFullName(),
                "date", date(when, language),
                "ip", client.ip(),
                // «Chrome en Windows» / «Chrome on Windows»
                "device", language.t(client.device()),
                "link", link));
    }

    public void sendPasswordReset(User user, String token) {
        String link = properties.frontendUrl() + "/reset-password?token=" + token;
        send(user.getEmail(), languageOf(user), "reset.subject", "reset-password", link, Map.of(
                "name", user.getFullName(),
                "link", link,
                "minutes", properties.tokens().passwordResetMinutes()));
    }

    public void sendPasswordChanged(User user) {
        String link = properties.frontendUrl() + "/forgot-password";
        Language language = languageOf(user);
        send(user.getEmail(), language, "changed.subject", "password-changed", null, Map.of(
                "name", user.getFullName(),
                "date", date(Instant.now(), language),
                "link", link));
    }

    /**
     * @param language idioma de quien recibe la invitación: el de su cuenta si la tiene y lo eligió; {@code null} si
     *                 no (sale en el de la petición de quien invita). El asunto no lleva el nombre de quien invita ni
     *                 el del workspace (los escribe el usuario)
     */
    public void sendWorkspaceInvitation(String email, String inviterName, String workspaceName, String token,
                                        Language language) {
        String link = properties.frontendUrl() + "/invitations/" + token;
        send(email, language != null ? language : Language.current(), "invitation.subject", "invitation", link, Map.of(
                "inviter", inviterName,
                "workspace", workspaceName,
                "link", link,
                "days", properties.tokens().invitationDays()));
    }

    /** El idioma que eligió la persona o, si nunca eligió uno, el de la petición en curso (español fuera de una). */
    static Language languageOf(User user) {
        Language chosen = Language.fromCode(user.getLanguage());
        return chosen != null ? chosen : Language.current();
    }

    private String date(Instant when, Language language) {
        DateTimeFormatter format = language == Language.EN ? DATE_FORMAT_EN : DATE_FORMAT_ES;
        return format.format(when.atZone(ZoneId.of(properties.timezone())));
    }

    private void send(String to, Language language, String subjectKey, String template, String actionLink,
                      Map<String, Object> variables) {
        // Con este locale las plantillas resuelven sus textos (#{…}) en el idioma del correo
        Locale locale = language == Language.EN ? Language.EN.locale() : ES;
        String subject = messages.getMessage(subjectKey, null, locale);
        boolean logLink = properties.logMailLinksAlways() || (!properties.mail().enabled() && properties.logMailLinks());
        if (actionLink != null && logLink) {
            // Solo en desarrollo: sin proveedor de correo (o con MAIL_LOG_LINKS=true, por si el SMTP falla), el
            // enlace se muestra en el log para probar el flujo.
            log.info("[Desarrollo] Enlace del correo '{}': {}", subject, actionLink);
        }
        Context context = new Context(locale);
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
