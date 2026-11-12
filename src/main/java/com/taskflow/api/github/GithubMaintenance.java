package com.taskflow.api.github;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limpieza diaria de lo que la integración con GitHub solo acumula: los avisos ya atendidos (se guardan unos días para
 * no atender dos veces uno repetido) y las ejecuciones de Actions antiguas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubMaintenance {

    private final GithubStore store;

    @Scheduled(cron = "${app.maintenance.cron:0 30 3 * * *}", zone = "${app.timezone}")
    public void purge() {
        int deliveries = store.forgetOldDeliveries();
        int runs = store.forgetOldRuns();
        if (deliveries > 0 || runs > 0) {
            log.info("Limpieza de GitHub: {} avisos atendidos y {} ejecuciones antiguas eliminados", deliveries, runs);
        }
    }
}
