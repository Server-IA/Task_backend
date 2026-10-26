package com.taskflow.api.email;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.config.AsyncConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Envío por SMTP. En local apunta a Mailpit (todos los correos se ven en http://localhost:8025);
 * también sirve para Brevo o Gmail cambiando solo las variables SMTP_*.
 * Se activa con {@code MAIL_PROVIDER=smtp}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @Override
    public void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Correo enviado por SMTP a {} ({})", to, subject);
        } catch (MessagingException | MailException e) {
            log.error("Error enviando correo por SMTP a {}", to, e);
        }
    }
}
