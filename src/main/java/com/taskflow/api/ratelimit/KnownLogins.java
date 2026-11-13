package com.taskflow.api.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Desde qué IPs entró bien cada cuenta hace poco. El límite de intentos por cuenta desde cualquier IP frena a quien
 * reparte una prueba de contraseñas entre muchas IPs, pero también le permitía a cualquiera, con solo saber el correo,
 * dejar a la dueña sin poder entrar mientras siguiera gastando esos intentos. Desde una IP donde la cuenta ya entró,
 * ese techo no se aplica (siguen el límite por IP y el de cuenta e IP, que solo bloquean a quien prueba desde ahí).
 * <p>
 * En memoria, como los demás límites: tras reiniciar, todas las IPs vuelven a ser desconocidas (el límite estricto).
 * Con tope de entradas: las más viejas salen primero.
 */
@Component
public class KnownLogins {

    static final Duration REMEMBER = Duration.ofDays(30);
    static final int MAX_ENTRIES = 50_000;

    private final LongSupplier clock;
    private final int maxEntries;
    /** «correo|IP» → cuándo entró por última vez (nanos del reloj); en orden de acceso. */
    private final LinkedHashMap<String, Long> lastLogin;

    public KnownLogins() {
        this(System::nanoTime, MAX_ENTRIES);
    }

    KnownLogins(LongSupplier clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.lastLogin = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > KnownLogins.this.maxEntries;
            }
        };
    }

    /** La cuenta entró bien desde esa IP. */
    public synchronized void remember(String accountAndIp) {
        lastLogin.put(accountAndIp, clock.getAsLong());
    }

    /** ¿La cuenta entró bien desde esa IP en los últimos 30 días? */
    public synchronized boolean known(String accountAndIp) {
        Long at = lastLogin.get(accountAndIp);
        if (at == null) {
            return false;
        }
        if (clock.getAsLong() - at > REMEMBER.toNanos()) {
            lastLogin.remove(accountAndIp);
            return false;
        }
        return true;
    }
}
