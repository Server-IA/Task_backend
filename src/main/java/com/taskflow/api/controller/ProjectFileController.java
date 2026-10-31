package com.taskflow.api.controller;

import com.taskflow.api.dto.request.CreateUploadRequest;
import com.taskflow.api.dto.response.FileResponse;
import com.taskflow.api.dto.response.UploadTicketResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Archivos", description = "Adjuntos de las tareas, imágenes de los textos y fotos de perfil (Cloudflare R2)")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class ProjectFileController {

    private final FileService fileService;

    @Operation(summary = "Pedir una subida (URL firmada)",
            description = "Valida la extensión, el tipo declarado y el tamaño (imágenes PNG, JPG, GIF o WebP hasta 5 MB; "
                    + "PDF, DOCX, XLSX, PPTX, TXT, MD, CSV y ZIP hasta 10 MB) y el espacio del workspace (2 GB; 409 "
                    + "con code STORAGE_FULL si no cabe). Devuelve una URL para mandar el archivo directo a R2 con PUT "
                    + "y las cabeceras exactas: la firma incluye el tipo y el tamaño. purpose INLINE_IMAGE = imagen "
                    + "pegada en una descripción o un comentario (solo imágenes; no va a «Adjuntos»; sin issueId en "
                    + "«Nueva tarea»). Sin R2 configurado responde 503.")
    @PostMapping("/files")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicketResponse createUpload(@PathVariable String slug, @PathVariable UUID projectId,
                                             @AuthenticationPrincipal UserPrincipal me,
                                             @Valid @RequestBody CreateUploadRequest request) {
        return fileService.createUpload(slug, projectId, me.getId(), request);
    }

    @Operation(summary = "Confirmar una subida (quien la pidió)",
            description = "Comprueba en R2 que el archivo llegó con el tamaño y el tipo firmados (y que su contenido "
                    + "corresponde al tipo). Si no, lo borra y responde 400 con code UPLOAD_REJECTED; si todavía no "
                    + "está, 409 con code UPLOAD_MISSING. Un adjunto queda en el historial de la tarea.")
    @PostMapping("/files/{fileId}/confirm")
    public FileResponse confirmUpload(@PathVariable String slug, @PathVariable UUID projectId,
                                      @PathVariable UUID fileId, @AuthenticationPrincipal UserPrincipal me) {
        return fileService.confirmUpload(slug, projectId, fileId, me.getId());
    }

    @Operation(summary = "Eliminar un archivo (quien lo subió o un project manager)",
            description = "Lo borra de la base y de R2. También cancela una subida en curso. Borrar un adjunto queda "
                    + "en el historial de la tarea.")
    @DeleteMapping("/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID fileId,
                           @AuthenticationPrincipal UserPrincipal me) {
        fileService.delete(slug, projectId, fileId, me.getId());
    }

    @Operation(summary = "Adjuntos de la tarea",
            description = "Los confirmados, del más antiguo al más reciente. Sus URLs se piden aparte "
                    + "(GET /api/v1/files/urls).")
    @GetMapping("/issues/{issueId}/attachments")
    public List<FileResponse> getAttachments(@PathVariable String slug, @PathVariable UUID projectId,
                                             @PathVariable UUID issueId) {
        return fileService.getAttachments(slug, projectId, issueId);
    }
}
