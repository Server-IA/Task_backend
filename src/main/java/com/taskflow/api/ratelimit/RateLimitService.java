package com.taskflow.api.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda un bucket de Bucket4j por regla e IP. En memoria, suficiente para una sola instancia local.
 */
@Service
public class RateLimitService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ConsumptionProbe tryConsume(RateLimitRule rule, String clientKey) {
        Bucket bucket = buckets.computeIfAbsent(rule.name() + ":" + clientKey, key -> newBucket(rule));
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rule.getCapacity())
                        .refillIntervally(rule.getCapacity(), rule.getPeriod())
                        .build())
                .build();
    }
}
