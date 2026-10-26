package com.taskflow.api.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.taskflow.api.config.AppProperties;
import com.taskflow.api.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Envío con la API de Resend (producción). Es el proveedor por defecto ({@code MAIL_PROVIDER=resend}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "resend", matchIfMissing = true)
public class ResendEmailService implements EmailService {

    private final Resend resend;
    private final AppProperties properties;

    @Async(AsyncConfig.MAIL_EXECUTOR)
    @Override
    public void send(String to, String subject, String html) {
        if (!properties.mail().enabled()) {
            log.info("[Correo no enviado: RESEND_API_KEY sin configurar] Para: {} | Asunto: {}", to, subject);
            return;
        }

        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(properties.mail().from())
                .to(to)
                .subject(subject)
                .html(html)
                .build();
        try {
            resend.emails().send(params);
            log.info("Correo enviado a {} ({})", to, subject);
        } catch (ResendException e) {
            log.error("Error enviando correo a {}", to, e);
        }
    }
}
