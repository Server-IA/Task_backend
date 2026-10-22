package com.taskflow.api.ratelimit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Endpoints con límite de peticiones por IP.
 */
@Getter
@RequiredArgsConstructor
public enum RateLimitRule {

    LOGIN("/api/v1/auth/login", 5, Duration.ofMinutes(1)),
    REGISTER("/api/v1/auth/register", 3, Duration.ofMinutes(10)),
    FORGOT_PASSWORD("/api/v1/auth/forgot-password", 3, Duration.ofMinutes(15)),
    RESEND_VERIFICATION("/api/v1/auth/resend-verification", 3, Duration.ofMinutes(15));

    private final String path;
    private final int capacity;
    private final Duration period;

    public static Optional<RateLimitRule> forPath(String path) {
        return Arrays.stream(values())
                .filter(rule -> rule.path.equals(path))
                .findFirst();
    }
}
