package com.taskflow.api.security;

import com.taskflow.api.repository.UserCredentialsState;
import com.taskflow.api.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un access token emitido antes de cambiar o restablecer la contraseña deja de valer enseguida.
 */
class CredentialsGuardTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AtomicLong clock = new AtomicLong();
    private final CredentialsGuard guard = new CredentialsGuard(users, clock::get);
    private final UUID ana = UUID.randomUUID();
    private final Instant changedAt = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void losTokensEmitidosAntesDelCambioDeContrasenaNoValen() {
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, changedAt)));

        assertThat(guard.accepts(ana, changedAt.minusSeconds(1))).isFalse();
        // El mismo segundo vale: el token de la sesión que cambió la contraseña se emite justo después
        assertThat(guard.accepts(ana, changedAt)).isTrue();
        assertThat(guard.accepts(ana, changedAt.plusSeconds(60))).isTrue();
    }

    @Test
    void sinCambiosDeContrasenaValeYUnaCuentaInactivaOEliminadaNo() {
        UUID luis = UUID.randomUUID();
        UUID borrado = UUID.randomUUID();
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, null)));
        when(users.findCredentialsState(luis)).thenReturn(Optional.of(new UserCredentialsState(false, null)));
        when(users.findCredentialsState(borrado)).thenReturn(Optional.empty());

        assertThat(guard.accepts(ana, Instant.now())).isTrue();
        assertThat(guard.accepts(luis, Instant.now())).isFalse();
        assertThat(guard.accepts(borrado, Instant.now())).isFalse();
    }

    @Test
    void consultaLaBaseDeDatosUnaVezPorMinutoYAlCambiarLaContrasenaEnseguida() {
        when(users.findCredentialsState(ana))
                .thenReturn(Optional.of(new UserCredentialsState(true, null)))
                .thenReturn(Optional.of(new UserCredentialsState(true, changedAt)));

        assertThat(guard.accepts(ana, changedAt.minusSeconds(5))).isTrue();
        assertThat(guard.accepts(ana, changedAt.minusSeconds(5))).isTrue();
        verify(users, times(1)).findCredentialsState(ana);

        guard.evict(ana);

        assertThat(guard.accepts(ana, changedAt.minusSeconds(5))).isFalse();
        verify(users, times(2)).findCredentialsState(ana);
    }

    @Test
    void elEstadoGuardadoVence() {
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, null)));
        guard.accepts(ana, Instant.now());

        clock.addAndGet(CredentialsGuard.TTL.plus(Duration.ofSeconds(1)).toNanos());
        guard.accepts(ana, Instant.now());

        verify(users, times(2)).findCredentialsState(ana);
    }
}
