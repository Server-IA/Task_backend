package com.taskflow.api.maintenance;

import com.taskflow.api.repository.NotificationRepository;
import com.taskflow.api.repository.RefreshTokenRepository;
import com.taskflow.api.repository.VerificationTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Limpieza diaria (por defecto a las 3:30 en {@code app.timezone}) de lo que ya no sirve y crecería sin límite:
 * <ul>
 *   <li>refresh tokens vencidos (los rotados se guardan hasta vencer para detectar su reutilización);</li>
 *   <li>tokens de verificación y de contraseña usados o vencidos;</li>
 *   <li>notificaciones leídas de hace más de {@code app.maintenance.notification-retention-days} días (90).</li>
 * </ul>
 */
@Slf4j
@Component
public class MaintenanceJobs {

    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final NotificationRepository notificationRepository;
    private final Duration notificationRetention;

    public MaintenanceJobs(RefreshTokenRepository refreshTokenRepository,
                           VerificationTokenRepository verificationTokenRepository,
                           NotificationRepository notificationRepository,
                           @Value("${app.maintenance.notification-retention-days:90}") long notificationRetentionDays) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.notificationRepository = notificationRepository;
        this.notificationRetention = Duration.ofDays(notificationRetentionDays);
    }

    @Scheduled(cron = "${app.maintenance.cron:0 30 3 * * *}", zone = "${app.timezone}")
    @Transactional
    public void purge() {
        Instant now = Instant.now();
        int refreshTokens = refreshTokenRepository.deleteExpired(now);
        int verificationTokens = verificationTokenRepository.deleteUsedOrExpired(now);
        int notifications = notificationRepository.deleteReadBefore(now.minus(notificationRetention));
        log.info("Limpieza: {} refresh tokens, {} tokens de verificación y {} notificaciones leídas eliminados",
                refreshTokens, verificationTokens, notifications);
    }
}
