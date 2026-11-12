package com.taskflow.api.imports;

import com.taskflow.api.imports.source.SourceConnection;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Las credenciales del origen de cada importación en curso, solo en memoria: no se escriben en la base de datos ni en
 * los registros, y se olvidan al terminar la importación, a las {@link #TTL} horas o al reiniciar el servidor (en ese
 * caso la persona vuelve a conectar).
 */
@Component
public class ImportCredentials {

    static final Duration TTL = Duration.ofHours(6);

    private record Entry(SourceConnection connection, Instant expiresAt) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public void put(UUID jobId, SourceConnection connection) {
        Instant now = Instant.now();
        entries.values().removeIf(entry -> entry.expiresAt().isBefore(now));
        entries.put(jobId, new Entry(connection, now.plus(TTL)));
    }

    public Optional<SourceConnection> get(UUID jobId) {
        Entry entry = entries.get(jobId);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(jobId);
            return Optional.empty();
        }
        return Optional.of(entry.connection());
    }

    public void remove(UUID jobId) {
        entries.remove(jobId);
    }
}
