package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Pedir la subida de una foto de perfil. El navegador la recorta a 512 × 512 y la manda como WebP; también se aceptan
 * PNG y JPG. Hasta 5 MB.
 */
public record AvatarUploadRequest(
        @Size(max = 100, message = "El tipo del archivo es demasiado largo")
        String contentType,

        @NotNull(message = "Falta el tamaño del archivo")
        @Positive(message = "El archivo está vacío")
        Long size
) {
}
