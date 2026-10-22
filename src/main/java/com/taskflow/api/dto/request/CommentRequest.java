package com.taskflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentRequest(
        @NotBlank(message = "El comentario no puede estar vacío")
        @Size(max = 10000, message = "El comentario no puede superar 10000 caracteres")
        String body
) {
}
