package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.Map;

/**
 * Subida firmada: el navegador manda el archivo directo a R2 con {@code PUT url}, con exactamente estas cabeceras (la
 * firma incluye el tipo y el tamaño: R2 rechaza otro archivo), y después la confirma.
 *
 * @param file    el archivo, todavía {@code PENDING}
 * @param url     URL firmada para el {@code PUT} (vence en {@code expiresAt})
 * @param method  siempre {@code PUT}
 * @param headers cabeceras que hay que mandar tal cual ({@code Content-Type}); el tamaño lo pone el navegador
 * @param storage uso del workspace contando esta subida ({@code null} en una foto de perfil)
 */
public record UploadTicketResponse(
        FileResponse file,
        String url,
        String method,
        Map<String, String> headers,
        Instant expiresAt,
        StorageUsageResponse storage
) {
}
