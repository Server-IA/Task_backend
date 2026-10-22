package com.taskflow.api.email;

public interface EmailService {

    /** Envía un correo HTML. La implementación es asíncrona y nunca lanza excepciones al llamador. */
    void send(String to, String subject, String html);
}
