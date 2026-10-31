package com.taskflow.api.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Los campos nulos no se modifican. Un avatarUrl vacío quita la foto; una URL http(s) pone una externa. La foto subida
 * ({@code /files/<id>}) se cambia con {@code POST /users/me/avatar}: aquí solo se acepta la actual (sin cambios).
 */
public record UpdateProfileRequest(
        @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
        String fullName,

        @Size(max = 500, message = "La URL del avatar es demasiado larga")
        @Pattern(regexp = "^$|^https?://.+|^/files/[0-9a-fA-F-]{36}$",
                message = "La URL del avatar debe empezar por http:// o https://")
        String avatarUrl
) {
}
