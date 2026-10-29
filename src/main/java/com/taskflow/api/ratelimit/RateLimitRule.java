package com.taskflow.api.ratelimit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Límites de peticiones. Los que tienen {@code path} los aplica {@link RateLimitFilter} por IP a esa ruta (POST);
 * los demás los aplican los servicios con su propia clave (el correo de la cuenta, quien invita, el usuario…).
 */
@Getter
@RequiredArgsConstructor
public enum RateLimitRule {

    LOGIN("/api/v1/auth/login", 5, Duration.ofMinutes(1)),
    REGISTER("/api/v1/auth/register", 3, Duration.ofMinutes(10)),
    FORGOT_PASSWORD("/api/v1/auth/forgot-password", 3, Duration.ofMinutes(15)),
    RESEND_VERIFICATION("/api/v1/auth/resend-verification", 3, Duration.ofMinutes(15)),

    /** Contraseñas incorrectas por cuenta (desde cualquier IP): frena adivinarla repartiendo los intentos. */
    LOGIN_ACCOUNT(null, 10, Duration.ofMinutes(15)),
    /** Invitaciones que envía cada persona. */
    INVITE_SENDER(null, 20, Duration.ofHours(1)),
    /** Invitaciones que recibe cada correo, de cualquier workspace: nadie recibe una avalancha de correos. */
    INVITE_RECIPIENT(null, 3, Duration.ofHours(1)),
    /** Intentos de cambiar la contraseña (adivinar la actual con una sesión robada). */
    CHANGE_PASSWORD(null, 5, Duration.ofMinutes(15));

    private final String path;
    private final int capacity;
    private final Duration period;

    /** La regla por IP de esa ruta (ya decodificada y sin la barra final), si tiene. */
    public static Optional<RateLimitRule> forPath(String path) {
        return Arrays.stream(values())
                .filter(rule -> rule.path != null && rule.path.equals(path))
                .findFirst();
    }
}
