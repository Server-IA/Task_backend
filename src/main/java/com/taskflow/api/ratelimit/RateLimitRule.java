package com.taskflow.api.ratelimit;

import lombok.Getter;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Límites de peticiones. Los que tienen {@code path} los aplica {@link RateLimitFilter} por IP a esa ruta (con su método,
 * POST salvo que se indique otro);
 * los demás los aplican los servicios con su propia clave (el correo de la cuenta, quien invita, el usuario…).
 */
@Getter
public enum RateLimitRule {

    LOGIN("/api/v1/auth/login", 5, Duration.ofMinutes(1)),
    REGISTER("/api/v1/auth/register", 3, Duration.ofMinutes(10)),
    FORGOT_PASSWORD("/api/v1/auth/forgot-password", 3, Duration.ofMinutes(15)),
    RESEND_VERIFICATION("/api/v1/auth/resend-verification", 3, Duration.ofMinutes(15)),
    /**
     * Comprobar un enlace de restablecer la contraseña sin usarlo: frena probar tokens. Algo más holgado que
     * {@link #FORGOT_PASSWORD} porque la página lo pide cada vez que se abre el enlace.
     */
    VALIDATE_RESET_TOKEN("GET", "/api/v1/auth/reset-password/validate", 10, Duration.ofMinutes(15)),

    /**
     * Intentos fallidos de entrar a una cuenta desde una misma IP (o prefijo IPv6 /64). Quien prueba contraseñas desde
     * su IP se bloquea solo a sí mismo: la persona dueña de la cuenta sigue entrando desde la suya.
     */
    LOGIN_ACCOUNT_IP(null, 10, Duration.ofMinutes(15)),
    /**
     * Techo de intentos fallidos por cuenta desde cualquier IP: frena adivinar la contraseña repartiendo los intentos
     * entre muchas IPs. Es más alto que {@link #LOGIN_ACCOUNT_IP} para que una sola IP no deje fuera a la dueña.
     */
    LOGIN_ACCOUNT(null, 50, Duration.ofMinutes(15)),
    /** Invitaciones que envía cada persona. */
    INVITE_SENDER(null, 20, Duration.ofHours(1)),
    /** Invitaciones que recibe cada correo, de cualquier workspace: nadie recibe una avalancha de correos. */
    INVITE_RECIPIENT(null, 3, Duration.ofHours(1)),
    /** Intentos de cambiar la contraseña (adivinar la actual con una sesión robada). */
    CHANGE_PASSWORD(null, 5, Duration.ofMinutes(15)),
    /** Subidas que pide cada persona (adjuntos, imágenes y foto de perfil): frena llenar el bucket con un script. */
    UPLOADS(null, 300, Duration.ofHours(1));

    private final String method;
    private final String path;
    private final int capacity;
    private final Duration period;

    RateLimitRule(String path, int capacity, Duration period) {
        this(path == null ? null : "POST", path, capacity, period);
    }

    RateLimitRule(String method, String path, int capacity, Duration period) {
        this.method = method;
        this.path = path;
        this.capacity = capacity;
        this.period = period;
    }

    /** La regla por IP de ese método y esa ruta (ya decodificada y sin la barra final), si tiene. */
    public static Optional<RateLimitRule> forRequest(String method, String path) {
        return Arrays.stream(values())
                .filter(rule -> rule.path != null && rule.path.equals(path) && rule.method.equalsIgnoreCase(method))
                .findFirst();
    }
}
