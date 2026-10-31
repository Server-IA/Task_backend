package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.FileKind;
import com.taskflow.api.entity.enums.FileStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Un archivo subido. No trae su URL: el bucket es privado y las URLs firmadas vencen; se piden con
 * {@code GET /api/v1/files/urls?ids=…}.
 *
 * @param image     PNG, JPG, GIF o WebP (miniatura y visor)
 * @param issueId   tarea del archivo; {@code null} en una imagen de «Nueva tarea» o en una foto de perfil
 * @param reference cómo se escribe en el Markdown: {@code /files/<id>}
 */
public record FileResponse(
        UUID id,
        FileKind kind,
        FileStatus status,
        String fileName,
        String contentType,
        long size,
        boolean image,
        UUID projectId,
        UUID issueId,
        UserSummaryResponse uploadedBy,
        Instant createdAt,
        String reference
) {
}
