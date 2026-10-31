package com.taskflow.api.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * El bucket de archivos (Cloudflare R2, con la API de S3). Privado: el navegador sube y descarga con URLs firmadas.
 */
public interface ObjectStorage {

    /** Si hay un bucket configurado. Si no, subir archivos responde 503 y no se borra nada. */
    boolean enabled();

    /**
     * URL firmada para subir con {@code PUT}. La firma incluye el tipo y el tamaño: R2 rechaza un archivo con otros.
     *
     * @return la URL y las cabeceras que debe mandar el navegador (sin {@code Host} ni {@code Content-Length})
     */
    PresignedRequest presignUpload(String key, String contentType, long size, Duration ttl);

    /**
     * URL firmada para ver ({@code inline}) o descargar ({@code attachment}) con el nombre original. La respuesta lleva
     * el tipo guardado y se puede guardar en la caché del navegador mientras la URL vale.
     */
    PresignedRequest presignDownload(String key, String contentType, String fileName, boolean inline, Duration ttl);

    /** Tamaño y tipo del objeto, o vacío si no existe. */
    Optional<ObjectInfo> head(String key);

    /** Los primeros {@code bytes} bytes del objeto (o menos, si es más corto). */
    byte[] readPrefix(String key, int bytes);

    /** Borra el objeto (no falla si no existe). */
    void delete(String key);

    record PresignedRequest(String url, Map<String, String> headers, Instant expiresAt) {
    }

    record ObjectInfo(long size, String contentType) {
    }
}
