package com.taskflow.api.ratelimit;

import com.taskflow.api.exception.TooManyRequestsException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Un bucket de Bucket4j por regla y clave (IP, correo, usuario…), en memoria: suficiente para una sola instancia.
 * <p>
 * Cada regla guarda sus buckets aparte, en orden de uso, y no crecen sin límite:
 * <ul>
 *   <li>se descartan los que llevan sin usarse más que el periodo de la regla (a esas alturas ya están llenos, así
 *       que descartarlos no cambia nada). Como todos los de un mapa tienen el mismo periodo, revisar desde el más
 *       antiguo y parar en el primero reciente no deja vencidos detrás;</li>
 *   <li>si aun así una regla llega a {@link #MAX_BUCKETS_PER_RULE}, se descarta un bucket lleno (sin intentos
 *       gastados). Uno con intentos gastados nunca se descarta: si no hay ninguno lleno, la clave nueva se rechaza
 *       (429) hasta que se libere sitio. Así, inundar el mapa con claves nuevas no le devuelve los intentos a nadie.</li>
 * </ul>
 */
@Service
public class RateLimitService {

    static final int MAX_BUCKETS_PER_RULE = 50_000;
    /** Buckets vencidos que se revisan en cada uso (los más antiguos primero). */
    private static final int PURGE_PER_CALL = 64;
    /** Buckets que se revisan, como mucho, buscando uno lleno que descartar. */
    private static final int FULL_SCAN_LIMIT = 4_096;

    private record Entry(Bucket bucket, long lastUsedNanos) {
    }

    private final LongSupplier clock;
    private final int maxBucketsPerRule;
    /** Por regla, en orden de uso: el primero es el que lleva más tiempo sin usarse. */
    private final Map<RateLimitRule, LinkedHashMap<String, Entry>> buckets = new EnumMap<>(RateLimitRule.class);

    public RateLimitService() {
        this(System::nanoTime, MAX_BUCKETS_PER_RULE);
    }

    RateLimitService(LongSupplier clock) {
        this(clock, MAX_BUCKETS_PER_RULE);
    }

    RateLimitService(LongSupplier clock, int maxBucketsPerRule) {
        this.clock = clock;
        this.maxBucketsPerRule = maxBucketsPerRule;
        for (RateLimitRule rule : RateLimitRule.values()) {
            buckets.put(rule, new LinkedHashMap<>(256, 0.75f, true));
        }
    }

    /** Gasta un intento de la clave. */
    public ConsumptionProbe tryConsume(RateLimitRule rule, String key) {
        Bucket bucket = bucket(rule, key);
        if (bucket == null) {
            long wait = rule.getPeriod().toNanos();
            return ConsumptionProbe.rejected(0, wait, wait);
        }
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    /** Gasta un intento o, si no quedan, lanza 429 con los segundos de espera. */
    public void consume(RateLimitRule rule, String key) {
        ConsumptionProbe probe = tryConsume(rule, key);
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException(seconds(probe.getNanosToWaitForRefill()));
        }
    }

    /** Devuelve un intento gastado con {@link #tryConsume} (por ejemplo, el de un login correcto). */
    public void refund(RateLimitRule rule, String key) {
        Entry entry;
        synchronized (this) {
            entry = buckets.get(rule).get(key);
        }
        if (entry != null) {
            entry.bucket().addTokens(1);
        }
    }

    public static long seconds(long nanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos) + (nanos % 1_000_000_000L == 0 ? 0 : 1));
    }

    synchronized int size() {
        return buckets.values().stream().mapToInt(Map::size).sum();
    }

    synchronized int size(RateLimitRule rule) {
        return buckets.get(rule).size();
    }

    /** El bucket de la clave, o {@code null} si la regla está llena de buckets con intentos gastados. */
    private synchronized Bucket bucket(RateLimitRule rule, String key) {
        long now = clock.getAsLong();
        LinkedHashMap<String, Entry> ruleBuckets = buckets.get(rule);
        purgeIdle(ruleBuckets, rule.getPeriod().toNanos(), now);
        Entry entry = ruleBuckets.get(key);
        Bucket bucket;
        if (entry != null) {
            bucket = entry.bucket();
        } else {
            if (ruleBuckets.size() >= maxBucketsPerRule && !evictFull(ruleBuckets, rule)) {
                return null;
            }
            bucket = newBucket(rule);
        }
        ruleBuckets.put(key, new Entry(bucket, now));
        return bucket;
    }

    private static void purgeIdle(LinkedHashMap<String, Entry> ruleBuckets, long periodNanos, long now) {
        Iterator<Entry> iterator = ruleBuckets.values().iterator();
        for (int i = 0; i < PURGE_PER_CALL && iterator.hasNext(); i++) {
            Entry entry = iterator.next();
            if (now - entry.lastUsedNanos() <= periodNanos) {
                return;
            }
            iterator.remove();
        }
    }

    /** Descarta el bucket lleno usado hace más tiempo; {@code false} si no hay ninguno. */
    private static boolean evictFull(LinkedHashMap<String, Entry> ruleBuckets, RateLimitRule rule) {
        Iterator<Entry> iterator = ruleBuckets.values().iterator();
        for (int i = 0; i < FULL_SCAN_LIMIT && iterator.hasNext(); i++) {
            if (iterator.next().bucket().getAvailableTokens() >= rule.getCapacity()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rule.getCapacity())
                        .refillIntervally(rule.getCapacity(), rule.getPeriod())
                        .build())
                .build();
    }
}
