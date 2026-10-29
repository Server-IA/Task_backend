package com.taskflow.api.ratelimit;

import com.taskflow.api.exception.TooManyRequestsException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Un bucket de Bucket4j por regla y clave (IP, correo, usuario…), en memoria: suficiente para una sola instancia.
 * <p>
 * Los buckets no crecen sin límite: se descartan los que llevan sin usarse más que el periodo de su regla (a esas
 * alturas ya estarían llenos, así que descartarlos no cambia nada) y, si aun así hay más de {@link #MAX_BUCKETS}, los
 * menos usados.
 */
@Service
public class RateLimitService {

    static final int MAX_BUCKETS = 50_000;
    /** Buckets vencidos que se revisan en cada uso (los más antiguos primero). */
    private static final int PURGE_PER_CALL = 64;

    private record Entry(Bucket bucket, long periodNanos, long lastUsedNanos) {
    }

    private final LongSupplier clock;

    /** En orden de uso: el primero es el que lleva más tiempo sin usarse. */
    private final LinkedHashMap<String, Entry> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_BUCKETS;
        }
    };

    public RateLimitService() {
        this(System::nanoTime);
    }

    RateLimitService(LongSupplier clock) {
        this.clock = clock;
    }

    /** Gasta un intento de la clave. */
    public ConsumptionProbe tryConsume(RateLimitRule rule, String key) {
        return bucket(rule, key).tryConsumeAndReturnRemaining(1);
    }

    /** Gasta un intento o, si no quedan, lanza 429 con los segundos de espera. */
    public void consume(RateLimitRule rule, String key) {
        ConsumptionProbe probe = tryConsume(rule, key);
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException(seconds(probe.getNanosToWaitForRefill()));
        }
    }

    /** Lanza 429 si no queda ningún intento, sin gastar uno (el intento se gasta solo si falla). */
    public void ensureAvailable(RateLimitRule rule, String key) {
        EstimationProbe probe = bucket(rule, key).estimateAbilityToConsume(1);
        if (!probe.canBeConsumed()) {
            throw new TooManyRequestsException(seconds(probe.getNanosToWaitForRefill()));
        }
    }

    public static long seconds(long nanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos) + (nanos % 1_000_000_000L == 0 ? 0 : 1));
    }

    synchronized int size() {
        return buckets.size();
    }

    private synchronized Bucket bucket(RateLimitRule rule, String key) {
        long now = clock.getAsLong();
        purgeIdle(now);
        String id = rule.name() + ":" + key;
        Entry entry = buckets.get(id);
        Bucket bucket = entry != null ? entry.bucket() : newBucket(rule);
        buckets.put(id, new Entry(bucket, rule.getPeriod().toNanos(), now));
        return bucket;
    }

    private void purgeIdle(long now) {
        Iterator<Entry> iterator = buckets.values().iterator();
        for (int i = 0; i < PURGE_PER_CALL && iterator.hasNext(); i++) {
            Entry entry = iterator.next();
            if (now - entry.lastUsedNanos() <= entry.periodNanos()) {
                return;
            }
            iterator.remove();
        }
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
