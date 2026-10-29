package com.taskflow.api.security;

import com.taskflow.api.repository.UserCredentialsState;
import com.taskflow.api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decide si un access token (válido por firma y fecha) sigue sirviendo: la cuenta existe, está activa y el token lleva
 * la versión actual de sus credenciales (claim {@code cv}, que sube con cada cambio o restablecimiento de la
 * contraseña). Así, quien restablece su contraseña porque se la robaron corta también los access tokens robados, sin
 * esperar a que venzan (15 minutos), incluidos los emitidos en el mismo segundo que el cambio.
 * <p>
 * El estado de cada usuario se guarda en memoria {@link #TTL} para no consultar la base de datos en cada petición; al
 * cambiar la contraseña se descarta enseguida ({@link #evict}). Las versiones solo suben: un token con una versión
 * mayor que la guardada significa que la guardada está vieja, y se vuelve a leer.
 */
@Component
public class CredentialsGuard {

    static final Duration TTL = Duration.ofSeconds(60);
    private static final int MAX_ENTRIES = 10_000;

    private record Cached(Optional<UserCredentialsState> state, long loadedAtNanos) {
    }

    private final UserRepository userRepository;
    private final LongSupplier clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public CredentialsGuard(UserRepository userRepository) {
        this(userRepository, System::nanoTime);
    }

    CredentialsGuard(UserRepository userRepository, LongSupplier clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** El token de {@code userId} emitido con la versión de credenciales {@code credentialsVersion} sigue valiendo. */
    public boolean accepts(UUID userId, int credentialsVersion) {
        Optional<UserCredentialsState> state = state(userId, false);
        if (state.isPresent() && credentialsVersion > state.get().credentialsVersion()) {
            state = state(userId, true);
        }
        return state.isPresent() && state.get().active() && state.get().credentialsVersion() == credentialsVersion;
    }

    /** Descarta el estado guardado del usuario, ahora y otra vez al confirmar la transacción actual. */
    public void evict(UUID userId) {
        cache.remove(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.remove(userId);
                }
            });
        }
    }

    private Optional<UserCredentialsState> state(UUID userId, boolean reload) {
        long now = clock.getAsLong();
        Cached cached = reload ? null : cache.get(userId);
        if (cached != null && now - cached.loadedAtNanos() < TTL.toNanos()) {
            return cached.state();
        }
        Optional<UserCredentialsState> state = userRepository.findCredentialsState(userId);
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, new Cached(state, now));
        return state;
    }
}
