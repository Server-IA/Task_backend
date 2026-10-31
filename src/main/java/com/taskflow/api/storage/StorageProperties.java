package com.taskflow.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Archivos en Cloudflare R2 (prefijo {@code app.storage}). Las credenciales salen de {@code R2_*} (archivo .env); sin
 * ellas la API arranca igual, pero subir archivos responde 503.
 *
 * @param accountId          cuenta de Cloudflare: el endpoint es {@code https://<accountId>.r2.cloudflarestorage.com}
 * @param endpoint           endpoint S3 explícito (opcional; por ejemplo un MinIO local). Vacío: el de R2
 * @param workspaceQuotaBytes espacio de cada workspace (2 GB)
 * @param uploadUrlTtl       vigencia de la URL firmada para subir
 * @param downloadUrlTtl     vigencia de las URLs firmadas para ver y descargar
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String accountId,
        String accessKeyId,
        String secretAccessKey,
        String bucket,
        String endpoint,
        long workspaceQuotaBytes,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl
) {

    public static final long DEFAULT_QUOTA = 2L * 1024 * 1024 * 1024;

    public StorageProperties {
        if (workspaceQuotaBytes <= 0) {
            workspaceQuotaBytes = DEFAULT_QUOTA;
        }
        if (uploadUrlTtl == null || uploadUrlTtl.isNegative() || uploadUrlTtl.isZero()) {
            uploadUrlTtl = Duration.ofMinutes(15);
        }
        if (downloadUrlTtl == null || downloadUrlTtl.isNegative() || downloadUrlTtl.isZero()) {
            downloadUrlTtl = Duration.ofHours(1);
        }
    }

    /** Hay credenciales, bucket y a dónde conectarse. */
    public boolean configured() {
        return notBlank(accessKeyId) && notBlank(secretAccessKey) && notBlank(bucket)
                && (notBlank(endpoint) || notBlank(accountId));
    }

    public URI endpointUri() {
        if (notBlank(endpoint)) {
            return URI.create(endpoint.trim());
        }
        return URI.create("https://" + accountId.trim() + ".r2.cloudflarestorage.com");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
