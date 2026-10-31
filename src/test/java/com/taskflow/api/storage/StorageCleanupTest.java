package com.taskflow.api.storage;

import com.taskflow.api.repository.StoredFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La cola de borrado de objetos de R2 y la limpieza de subidas abandonadas.
 */
@ExtendWith(MockitoExtension.class)
class StorageCleanupTest {

    @Mock
    private ObjectStorage storage;
    @Mock
    private StoredFileRepository repository;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformTransactionManager transactionManager;

    private StorageCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new StorageCleanup(storage, repository, jdbc, transactionManager);
    }

    private static List<Map<String, Object>> rows(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Map.<String, Object>of("id", (long) i + 1, "object_key", "projects/p/" + (i + 1)))
                .toList();
    }

    @Test
    void borraLosObjetosDeLaColaYSusFilas() {
        when(storage.enabled()).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(StorageCleanup.BATCH))).thenReturn(rows(3));

        int deleted = cleanup.drain();

        assertThat(deleted).isEqualTo(3);
        verify(storage).delete("projects/p/1");
        verify(storage).delete("projects/p/3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> ids = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("DELETE FROM storage_deletions WHERE id = ?"), ids.capture());
        assertThat(ids.getValue()).extracting(row -> row[0]).containsExactly(1L, 2L, 3L);
        verify(jdbc, never()).batchUpdate(eq("UPDATE storage_deletions SET attempts = attempts + 1 WHERE id = ?"),
                anyList());
    }

    @Test
    void siR2FallaLaClaveSeQuedaConUnIntentoMasYNoSigueInsistiendo() {
        when(storage.enabled()).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(StorageCleanup.BATCH))).thenReturn(rows(StorageCleanup.BATCH));
        // lenient: con las otras claves, delete no hace nada (sin el aviso de «otro argumento» de Mockito)
        lenient().doThrow(new StorageUnavailableException("R2 caído")).when(storage).delete("projects/p/2");

        int deleted = cleanup.drain();

        assertThat(deleted).isEqualTo(1);
        // La tercera ni se intenta: espera a la próxima pasada
        verify(storage, never()).delete("projects/p/3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> failed = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(eq("UPDATE storage_deletions SET attempts = attempts + 1 WHERE id = ?"),
                failed.capture());
        assertThat(failed.getValue()).extracting(row -> row[0]).containsExactly(2L);
        // Una sola tanda aunque estuviera llena
        verify(jdbc, times(1)).queryForList(anyString(), anyInt());
    }

    @Test
    void sigueConLaSiguienteTandaMientrasLleguenLlenas() {
        when(storage.enabled()).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(StorageCleanup.BATCH)))
                .thenReturn(rows(StorageCleanup.BATCH))
                .thenReturn(rows(2));

        assertThat(cleanup.drain()).isEqualTo(StorageCleanup.BATCH + 2);
        verify(jdbc, times(2)).queryForList(anyString(), anyInt());
    }

    @Test
    void sinR2NoTocaLaCola() {
        when(storage.enabled()).thenReturn(false);

        assertThat(cleanup.drain()).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    void borraSubidasSinConfirmarDeMasDe24hEImagenesSinUsar() {
        when(repository.deletePendingBefore(any())).thenReturn(2);
        when(repository.deleteUnreferencedInlineImages(any())).thenReturn(1);

        cleanup.purgeAbandoned();

        ArgumentCaptor<Instant> pending = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deletePendingBefore(pending.capture());
        assertThat(pending.getValue()).isCloseTo(Instant.now().minus(Duration.ofHours(24)),
                within(5, java.time.temporal.ChronoUnit.SECONDS));
        ArgumentCaptor<Instant> inline = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteUnreferencedInlineImages(inline.capture());
        assertThat(inline.getValue()).isCloseTo(Instant.now().minus(Duration.ofDays(7)),
                within(5, java.time.temporal.ChronoUnit.SECONDS));
        verify(repository).deleteOrphanAvatars();
    }
}
