package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * URL firmada (GET) para ver o descargar un archivo. Las imágenes y los PDF se abren en el navegador; lo demás (o
 * todo, con {@code download=true}) se descarga con su nombre original.
 */
public record FileUrlResponse(
        UUID id,
        String url,
        Instant expiresAt,
        String fileName,
        String contentType,
        long size,
        boolean image
) {
}
