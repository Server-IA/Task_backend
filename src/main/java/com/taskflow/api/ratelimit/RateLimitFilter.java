package com.taskflow.api.ratelimit;

import com.taskflow.api.util.ClientKeys;
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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * Limita los intentos en login, registro, olvidé mi contraseña y reenvío de verificación, por IP.
 * Se ejecuta antes del filtro JWT y responde 429 con la cabecera {@code Retry-After}.
 * <p>
 * La ruta se compara ya decodificada (como la resuelve Spring MVC): {@code /api/v1/auth/%6Cogin} también es el
 * login. La IP es la del cliente (una IPv6 cuenta por su prefijo /64, {@link ClientKeys#ip}); detrás de un proxy hay
 * que activar {@code FORWARD_HEADERS_STRATEGY=native}.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final RateLimitService rateLimitService;
    private final JsonResponseWriter writer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<RateLimitRule> rule = HttpMethod.POST.matches(request.getMethod())
                ? RateLimitRule.forPath(normalizedPath(request))
                : Optional.empty();

        if (rule.isPresent()) {
            ConsumptionProbe probe = rateLimitService.tryConsume(rule.get(), ClientKeys.ip(request.getRemoteAddr()));
            if (!probe.isConsumed()) {
                long retryAfter = RateLimitService.seconds(probe.getNanosToWaitForRefill());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
                writer.writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Demasiados intentos. Intenta de nuevo en " + retryAfter + " segundos");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** La ruta dentro de la aplicación, decodificada, sin parámetros «;…» y sin barras al final. */
    static String normalizedPath(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
