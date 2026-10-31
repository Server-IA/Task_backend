package com.taskflow.api.storage;

import com.taskflow.api.repository.StoredFileRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Que no queden objetos huérfanos en R2:
 * <ul>
 *   <li>Cola de borrado: cada fila borrada de {@code stored_files} (a mano o en cascada con su tarea, su proyecto o su
 *       workspace) deja su clave en {@code storage_deletions} en la misma transacción (un trigger). Después del commit
 *       ({@link #drainAfterCommit}) y cada minuto se borran esos objetos de R2; si R2 falla, la clave espera al
 *       siguiente intento.</li>
 *   <li>Cada hora ({@link #purgeAbandoned}): subidas sin confirmar de más de 24 h, imágenes pegadas que ya no están
 *       en ningún texto desde hace una semana y fotos de perfil sin dueño.</li>
 * </ul>
 */
@Slf4j
@Component
public class StorageCleanup {

    /** Claves por transacción al vaciar la cola. */
    static final int BATCH = 100;
    /** Tandas por pasada (después sigue la próxima pasada programada). */
    static final int MAX_BATCHES = 50;
    static final Duration PENDING_TTL = Duration.ofHours(24);
    static final Duration UNREFERENCED_GRACE = Duration.ofDays(7);

    private final ObjectStorage storage;
    private final StoredFileRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "storage-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public StorageCleanup(ObjectStorage storage, StoredFileRepository repository, JdbcTemplate jdbc,
                          PlatformTransactionManager transactionManager) {
        this.storage = storage;
        this.repository = repository;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Vacía la cola en segundo plano cuando termine bien la transacción actual (lo que borró esa transacción ya está en
     * la cola). Sin transacción, enseguida.
     */
    public void drainAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            drainInBackground();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                drainInBackground();
            }
        });
    }

    private void drainInBackground() {
        try {
            executor.execute(this::drainQuietly);
        } catch (RejectedExecutionException ex) {
            // Apagando: lo hará la próxima pasada programada
        }
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (RuntimeException ex) {
            log.warn("No se pudo vaciar la cola de borrado de archivos: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.storage.drain-interval:PT1M}",
            initialDelayString = "${app.storage.drain-initial-delay:PT30S}")
    public void drainScheduled() {
        drainQuietly();
    }

    /**
     * Borra de R2 los objetos de la cola, en tandas. Una clave que falla se queda (con un intento más) y la tanda deja
     * de insistir hasta la próxima pasada.
     *
     * @return objetos borrados
     */
    public int drain() {
        if (!storage.enabled()) {
            return 0;
        }
        int deleted = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            BatchResult result = transaction.execute(status -> drainBatch());
            if (result == null) {
                break;
            }
            deleted += result.deleted();
            if (result.size() < BATCH || result.failed() > 0) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Archivos: {} objetos borrados de R2", deleted);
        }
        return deleted;
    }

    private BatchResult drainBatch() {
        // SKIP LOCKED: dos pasadas a la vez (la programada y la de un commit) no toman las mismas claves
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, object_key FROM storage_deletions ORDER BY attempts, id LIMIT ? FOR UPDATE SKIP LOCKED",
                BATCH);
        List<Object[]> done = new ArrayList<>();
        List<Object[]> failed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String key = (String) row.get("object_key");
            try {
                storage.delete(key);
                done.add(new Object[] {id});
            } catch (RuntimeException ex) {
                failed.add(new Object[] {id});
                // R2 no responde: el resto de la tanda espera a la próxima pasada
                break;
            }
        }
        if (!done.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM storage_deletions WHERE id = ?", done);
        }
        if (!failed.isEmpty()) {
            jdbc.batchUpdate("UPDATE storage_deletions SET attempts = attempts + 1 WHERE id = ?", failed);
        }
        return new BatchResult(rows.size(), done.size(), failed.size());
    }

    /**
     * Borra lo que ya no sirve (sus objetos pasan a la cola y se borran de R2 enseguida): subidas sin confirmar de más
     * de 24 h, imágenes de los textos que no aparecen en ninguno desde hace una semana y fotos de perfil sin dueño.
     */
    @Scheduled(cron = "${app.storage.cleanup-cron:0 15 * * * *}", zone = "${app.timezone}")
    public void purgeAbandoned() {
        Instant now = Instant.now();
        int[] counts = transaction.execute(status -> new int[] {
                repository.deletePendingBefore(now.minus(PENDING_TTL)),
                repository.deleteUnreferencedInlineImages(now.minus(UNREFERENCED_GRACE)),
                repository.deleteOrphanAvatars()});
        if (counts != null && counts[0] + counts[1] + counts[2] > 0) {
            log.info("Archivos: {} subidas sin confirmar, {} imágenes sin usar y {} fotos sin dueño eliminadas",
                    counts[0], counts[1], counts[2]);
            drainInBackground();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    record BatchResult(int size, int deleted, int failed) {
    }
}
