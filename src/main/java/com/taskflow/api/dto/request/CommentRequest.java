package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CommentRequest(
        @NotNull(message = "El comentario no puede estar vacío")
        @Visible(message = "El comentario no puede estar vacío")
        @Size(max = 10000, message = "El comentario no puede superar 10000 caracteres")
        String body
) {
}
