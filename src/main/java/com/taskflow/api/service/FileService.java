package com.taskflow.api.service;

import com.taskflow.api.dto.request.AvatarUploadRequest;
import com.taskflow.api.dto.request.CreateUploadRequest;
import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.dto.response.FileUrlResponse;
import com.taskflow.api.dto.response.StorageReportResponse;
import com.taskflow.api.dto.response.StorageUsageResponse;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.dto.response.UserResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Archivos en Cloudflare R2: adjuntos de tareas, imágenes de los textos y fotos de perfil.
 * <p>
 * Subir es en tres pasos: el backend valida y firma la subida ({@link #createUpload}), el navegador manda el archivo
 * directo a R2 y el backend la confirma ({@link #confirmUpload}) después de comprobar en R2 su tamaño y su tipo.
 */
public interface FileService {

    /**
     * Valida el archivo (extensión, tipo declarado y tamaño) y el espacio del workspace, y firma la subida. Cualquiera que
     * accede al proyecto puede subir.
     */
    UploadTicketResponse createUpload(String slug, UUID projectId, UUID userId, CreateUploadRequest request);

    /**
     * Confirma una subida de {@code userId}: comprueba en R2 que el objeto existe con el tamaño y el tipo firmados (y que
     * su contenido empieza como ese tipo). Si no coincide, lo borra y responde 400. Un adjunto queda en el historial.
     */
    FileResponse confirmUpload(String slug, UUID projectId, UUID fileId, UUID userId);

    /** Elimina el archivo (de la base y de R2). Solo quien lo subió o un project manager. */
    void delete(String slug, UUID projectId, UUID fileId, UUID userId);

    /** Adjuntos confirmados de la tarea. */
    List<FileResponse> getAttachments(String slug, UUID projectId, UUID issueId);

    /**
     * URLs firmadas de los archivos que {@code userId} puede ver: los de proyectos a los que tiene acceso y las fotos de
     * perfil de quien comparte un workspace con él. Los demás ids (o que no existen) no vienen.
     *
     * @param download {@code true}: todos se descargan con su nombre; si no, las imágenes y los PDF se abren
     */
    List<FileUrlResponse> resolveUrls(UUID userId, Collection<UUID> ids, boolean download);

    /** Espacio usado del workspace (para el aviso del 90 %). */
    StorageUsageResponse getUsage(String slug);

    /** Pestaña «Almacenamiento»: por tipo, por proyecto y los archivos más pesados. */
    StorageReportResponse getReport(String slug);

    /** Firma la subida de una foto de perfil (ya recortada en el navegador). */
    UploadTicketResponse createAvatarUpload(UUID userId, AvatarUploadRequest request);

    /** Confirma la foto de perfil y la pone en el perfil (la anterior se borra). */
    UserResponse confirmAvatar(UUID userId, UUID fileId);

    /** Quita la foto de perfil (vuelven las iniciales). */
    UserResponse removeAvatar(UUID userId);

    /**
     * Liga a la tarea recién creada las imágenes de su descripción subidas en «Nueva tarea», para que se borren con ella.
     */
    void claimInlineImages(Issue issue, String markdown);

    /**
     * Borra las imágenes de un texto que ya no está (un comentario borrado), salvo las que aparecen en otro texto del
     * proyecto (una descripción, un comentario o una plantilla).
     */
    void releaseInlineImages(Project project, String markdown);

    /** Borra las fotos de perfil subidas del usuario (por ejemplo, al poner una URL externa). */
    void deleteAvatars(UUID userId);
}
