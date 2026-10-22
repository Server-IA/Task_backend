package com.taskflow.api.ratelimit;

import com.taskflow.api.util.JsonResponseWriter;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Limita los intentos en login, registro, olvidé mi contraseña y reenvío de verificación.
 * Se ejecuta antes del filtro JWT y responde 429 con la cabecera {@code Retry-After}.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final JsonResponseWriter writer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RateLimitRule> rule = HttpMethod.POST.matches(request.getMethod())
                ? RateLimitRule.forPath(request.getRequestURI())
                : Optional.empty();

        if (rule.isPresent()) {
            ConsumptionProbe probe = rateLimitService.tryConsume(rule.get(), request.getRemoteAddr());
            if (!probe.isConsumed()) {
                long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
                writer.writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Demasiados intentos. Intenta de nuevo en " + retryAfter + " segundos");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
