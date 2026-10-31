package com.taskflow.api.dto.response;

import java.util.UUID;

/**
 * El archivo de una entrada del historial ({@code ATTACHMENT}) mientras exista: la tarjeta del feed muestra su nombre,
 * su tamaño y, si es una imagen, su miniatura.
 */
public record FileRefResponse(
        UUID id,
        String fileName,
        String contentType,
        long size,
        boolean image
) {
}
