package com.taskflow.api.controller;

import com.taskflow.api.dto.response.StorageReportResponse;
import com.taskflow.api.dto.response.StorageUsageResponse;
import com.taskflow.api.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Archivos")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/storage")
@RequiredArgsConstructor
public class StorageController {

    private final FileService fileService;

    @Operation(summary = "Almacenamiento del workspace (project manager)",
            description = "Espacio usado de los 2 GB (adjuntos e imágenes de las tareas; las fotos de perfil no "
                    + "cuentan), por tipo, por proyecto y los archivos más pesados.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.isManager(#slug)")
    public StorageReportResponse getReport(@PathVariable String slug) {
        return fileService.getReport(slug);
    }

    @Operation(summary = "Espacio usado del workspace",
            description = "warning desde el 90 %; full al 100 % (no se pueden subir archivos nuevos).")
    @GetMapping("/usage")
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public StorageUsageResponse getUsage(@PathVariable String slug) {
        return fileService.getUsage(slug);
    }
}
