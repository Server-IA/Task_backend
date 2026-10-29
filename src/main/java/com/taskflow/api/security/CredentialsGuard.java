package com.taskflow.api.security;

import com.taskflow.api.repository.UserCredentialsState;
import com.taskflow.api.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decide si un access token (válido por firma y fecha) sigue sirviendo: la cuenta existe, está activa y el token se
 * emitió después del último cambio o restablecimiento de la contraseña. Así, quien restablece su contraseña porque se
 * la robaron corta también los access tokens robados, sin esperar a que venzan (15 minutos).
 * <p>
 * El estado de cada usuario se guarda en memoria {@link #TTL} para no consultar la base de datos en cada petición; al
 * cambiar la contraseña se descarta enseguida ({@link #evict}).
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

    /** El token de {@code userId} emitido en {@code issuedAt} (al segundo, como el «iat» del JWT) sigue valiendo. */
    public boolean accepts(UUID userId, Instant issuedAt) {
        Optional<UserCredentialsState> state = state(userId);
        if (state.isEmpty() || !state.get().active()) {
            return false;
        }
        Instant changedAt = state.get().credentialsChangedAt();
        return changedAt == null || (issuedAt != null && !issuedAt.isBefore(changedAt));
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

    private Optional<UserCredentialsState> state(UUID userId) {
        long now = clock.getAsLong();
        Cached cached = cache.get(userId);
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
