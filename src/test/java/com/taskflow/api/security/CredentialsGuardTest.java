package com.taskflow.api.security;

import com.taskflow.api.repository.UserCredentialsState;
import com.taskflow.api.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un access token emitido antes de cambiar o restablecer la contraseña deja de valer enseguida: solo vale el que lleva
 * la versión actual de las credenciales.
 */
class CredentialsGuardTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AtomicLong clock = new AtomicLong();
    private final CredentialsGuard guard = new CredentialsGuard(users, clock::get);
    private final UUID ana = UUID.randomUUID();

    @Test
    void soloValeLaVersionActual() {
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, 3)));

        assertThat(guard.accepts(ana, 3)).isTrue();
        // Emitido antes del último cambio, aunque fuera en el mismo segundo
        assertThat(guard.accepts(ana, 2)).isFalse();
        assertThat(guard.accepts(ana, 0)).isFalse();
    }

    @Test
    void sinCambiosDeContrasenaValeYUnaCuentaInactivaOEliminadaNo() {
        UUID luis = UUID.randomUUID();
        UUID borrado = UUID.randomUUID();
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, 0)));
        when(users.findCredentialsState(luis)).thenReturn(Optional.of(new UserCredentialsState(false, 0)));
        when(users.findCredentialsState(borrado)).thenReturn(Optional.empty());

        assertThat(guard.accepts(ana, 0)).isTrue();
        assertThat(guard.accepts(luis, 0)).isFalse();
        assertThat(guard.accepts(borrado, 0)).isFalse();
    }

    @Test
    void consultaLaBaseDeDatosUnaVezPorMinutoYAlCambiarLaContrasenaEnseguida() {
        when(users.findCredentialsState(ana))
                .thenReturn(Optional.of(new UserCredentialsState(true, 0)))
                .thenReturn(Optional.of(new UserCredentialsState(true, 1)));

        assertThat(guard.accepts(ana, 0)).isTrue();
        assertThat(guard.accepts(ana, 0)).isTrue();
        verify(users, times(1)).findCredentialsState(ana);

        guard.evict(ana);

        assertThat(guard.accepts(ana, 0)).isFalse();
        verify(users, times(2)).findCredentialsState(ana);
    }

    @Test
    void unTokenConUnaVersionMasNuevaQueLaGuardadaVuelveALeerla() {
        // El estado guardado es de antes del cambio (otra petición lo leyó justo antes): el token nuevo no se rechaza
        when(users.findCredentialsState(ana))
                .thenReturn(Optional.of(new UserCredentialsState(true, 0)))
                .thenReturn(Optional.of(new UserCredentialsState(true, 1)));
        assertThat(guard.accepts(ana, 0)).isTrue();

        assertThat(guard.accepts(ana, 1)).isTrue();
        // Y desde ahí el viejo ya no vale, sin otra consulta
        assertThat(guard.accepts(ana, 0)).isFalse();
        verify(users, times(2)).findCredentialsState(ana);
    }

    @Test
    void elEstadoGuardadoVence() {
        when(users.findCredentialsState(ana)).thenReturn(Optional.of(new UserCredentialsState(true, 0)));
        guard.accepts(ana, 0);

        clock.addAndGet(CredentialsGuard.TTL.plus(Duration.ofSeconds(1)).toNanos());
        guard.accepts(ana, 0);

        verify(users, times(2)).findCredentialsState(ana);
    }
}
