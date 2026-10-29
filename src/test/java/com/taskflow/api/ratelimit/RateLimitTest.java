package com.taskflow.api.ratelimit;

import com.taskflow.api.exception.TooManyRequestsException;
import com.taskflow.api.util.JsonResponseWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El límite del login no se salta codificando la ruta, y los buckets no crecen sin límite.
 */
class RateLimitTest {

    private static MockHttpServletRequest post(String rawUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", rawUri);
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void laRutaSeComparaDecodificadaYSinBarraFinal() {
        assertThat(RateLimitFilter.normalizedPath(post("/api/v1/auth/%6Cogin"))).isEqualTo("/api/v1/auth/login");
        assertThat(RateLimitFilter.normalizedPath(post("/api/v1/auth/login/"))).isEqualTo("/api/v1/auth/login");
        assertThat(RateLimitRule.forPath("/api/v1/auth/login")).contains(RateLimitRule.LOGIN);
        assertThat(RateLimitRule.forPath("/api/v1/auth/refresh")).isEmpty();
    }

    @Test
    void codificarLaRutaNoDaIntentosExtra() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitService(),
                new JsonResponseWriter(JsonMapper.builder().build()));
        FilterChain chain = mock(FilterChain.class);
        int capacity = RateLimitRule.LOGIN.getCapacity();

        for (int i = 0; i < capacity; i++) {
            filter.doFilter(post(i % 2 == 0 ? "/api/v1/auth/login" : "/api/v1/auth/%6C%6Fgin"),
                    new MockHttpServletResponse(), chain);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(post("/api/v1/auth/%6Cogin"), blocked, chain);

        verify(chain, times(capacity)).doFilter(any(), any());
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void consumeLanza429ConLosSegundosDeEspera() {
        RateLimitService service = new RateLimitService();
        for (int i = 0; i < RateLimitRule.INVITE_RECIPIENT.getCapacity(); i++) {
            service.consume(RateLimitRule.INVITE_RECIPIENT, "ana@test.com");
        }

        assertThatThrownBy(() -> service.consume(RateLimitRule.INVITE_RECIPIENT, "ana@test.com"))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(error -> assertThat(((TooManyRequestsException) error).getRetryAfterSeconds())
                        .isPositive());
        // Otra clave tiene su propio límite
        service.consume(RateLimitRule.INVITE_RECIPIENT, "luis@test.com");
    }

    @Test
    void refundDevuelveUnIntentoGastado() {
        RateLimitService service = new RateLimitService();
        for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT_IP.getCapacity(); i++) {
            assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT_IP, "ana@test.com|10.0.0.1").isConsumed())
                    .isTrue();
        }
        assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT_IP, "ana@test.com|10.0.0.1").isConsumed()).isFalse();

        service.refund(RateLimitRule.LOGIN_ACCOUNT_IP, "ana@test.com|10.0.0.1");

        assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT_IP, "ana@test.com|10.0.0.1").isConsumed()).isTrue();
        // Devolver un intento de una clave sin bucket no hace nada
        service.refund(RateLimitRule.LOGIN_ACCOUNT_IP, "nadie");
        assertThat(service.size(RateLimitRule.LOGIN_ACCOUNT_IP)).isEqualTo(1);
    }

    @Test
    void elFiltroCuentaLasIpv6PorSuPrefijo64() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitService(),
                new JsonResponseWriter(JsonMapper.builder().build()));
        FilterChain chain = mock(FilterChain.class);
        int capacity = RateLimitRule.LOGIN.getCapacity();

        for (int i = 0; i < capacity; i++) {
            MockHttpServletRequest request = post("/api/v1/auth/login");
            request.setRemoteAddr("2001:db8:aa:bb::" + Integer.toHexString(i + 1));
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest sameNetwork = post("/api/v1/auth/login");
        sameNetwork.setRemoteAddr("2001:db8:aa:bb:1234:5678:9abc:def0");
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(sameNetwork, blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        verify(chain, times(capacity)).doFilter(any(), any());
    }

    @Test
    void losBucketsSinUsoSeDescartan() {
        AtomicLong now = new AtomicLong();
        RateLimitService service = new RateLimitService(now::get);
        for (int i = 0; i < 100; i++) {
            service.tryConsume(RateLimitRule.LOGIN, "10.0.0." + i);
        }
        assertThat(service.size()).isEqualTo(100);

        // Pasado el periodo de la regla, un bucket sin usar ya estaría lleno: descartarlo no cambia nada
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        for (int i = 0; i < 3; i++) {
            service.tryConsume(RateLimitRule.LOGIN, "10.1.0." + i);
        }

        assertThat(service.size()).isLessThan(100);
    }

    @Test
    void unBucketRecienteDeOtraReglaNoFrenaLaLimpieza() {
        AtomicLong now = new AtomicLong();
        RateLimitService service = new RateLimitService(now::get);
        // El más antiguo de todos es de una regla de una hora; detrás, cien de la regla de un minuto
        service.tryConsume(RateLimitRule.INVITE_SENDER, "ana");
        for (int i = 0; i < 100; i++) {
            service.tryConsume(RateLimitRule.LOGIN, "10.0.0." + i);
        }

        now.addAndGet(Duration.ofMinutes(2).toNanos());
        for (int i = 0; i < 2; i++) {
            service.tryConsume(RateLimitRule.LOGIN, "10.1.0." + i);
        }

        // Cada regla tiene su mapa: los de un minuto se limpian aunque el de una hora siga vigente
        assertThat(service.size(RateLimitRule.LOGIN)).isLessThan(100);
        assertThat(service.size(RateLimitRule.INVITE_SENDER)).isEqualTo(1);
    }

    @Test
    void alLlenarseSoloSeDescartanBucketsSinIntentosGastados() {
        AtomicLong now = new AtomicLong();
        RateLimitService service = new RateLimitService(now::get, 3);
        // La víctima gasta todos sus intentos; dos claves más llenan la regla
        for (int i = 0; i < RateLimitRule.LOGIN_ACCOUNT.getCapacity(); i++) {
            service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "victima@test.com");
        }
        service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "a@test.com");
        service.refund(RateLimitRule.LOGIN_ACCOUNT, "a@test.com");
        service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "b@test.com");

        // Una clave nueva ocupa el sitio del único bucket lleno («a», que devolvió su intento)…
        assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "c@test.com").isConsumed()).isTrue();
        assertThat(service.size(RateLimitRule.LOGIN_ACCOUNT)).isEqualTo(3);
        // …y cuando no queda ninguno lleno, la clave nueva se rechaza (no se descarta un bucket con intentos gastados)
        assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "d@test.com").isConsumed()).isFalse();
        assertThatThrownBy(() -> service.consume(RateLimitRule.LOGIN_ACCOUNT, "e@test.com"))
                .isInstanceOf(TooManyRequestsException.class);
        // La víctima sigue sin intentos: inundar el mapa no se los devolvió
        assertThat(service.tryConsume(RateLimitRule.LOGIN_ACCOUNT, "victima@test.com").isConsumed()).isFalse();
    }
}
