package com.taskflow.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pestaña «Almacenamiento» de los ajustes del workspace (project manager).
 *
 * @param usage     espacio usado y cuota
 * @param byType    por tipo, siempre en este orden: {@code IMAGES}, {@code DOCUMENTS}, {@code OTHER} (ZIP)
 * @param byProject por proyecto, del que más usa al que menos (solo los que tienen archivos)
 * @param largest   los archivos más pesados (hasta 10), para liberar espacio rápido
 */
public record StorageReportResponse(
        StorageUsageResponse usage,
        List<TypeUsage> byType,
        List<ProjectUsage> byProject,
        List<LargeFile> largest
) {

    public enum FileGroup {
        IMAGES,
        DOCUMENTS,
        OTHER
    }

    public record TypeUsage(FileGroup type, long bytes, long count) {
    }

    public record ProjectUsage(ProjectRefResponse project, long bytes, long count) {
    }

    /**
     * Un archivo pesado. Se elimina con {@code DELETE …/projects/{projectId}/files/{id}} (igual que en la tarea).
     *
     * @param issue tarea del archivo; {@code null} en una imagen de «Nueva tarea» que no llegó a crearse
     */
    public record LargeFile(
            UUID id,
            String fileName,
            String contentType,
            long size,
            boolean image,
            ProjectRefResponse project,
            IssueSummaryResponse issue,
            UserSummaryResponse uploadedBy,
            Instant createdAt
    ) {
    }
}
