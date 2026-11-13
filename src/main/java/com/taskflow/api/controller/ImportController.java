package com.taskflow.api.controller;

import com.taskflow.api.dto.request.ImportConnectRequest;
import com.taskflow.api.dto.request.ImportUploadRequest;
import com.taskflow.api.dto.response.ImportJobResponse;
import com.taskflow.api.imports.ImportMapping;
import com.taskflow.api.imports.ImportSelection;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Tag(name = "Importar", description = "Traer proyectos y tareas desde Jira, Trello, Linear, Asana o un archivo CSV")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @Operation(summary = "Importaciones del workspace", description = "Las más recientes primero, con su informe si terminaron.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public List<ImportJobResponse> list(@PathVariable String slug) {
        return importService.list(slug);
    }

    @Operation(summary = "Una importación",
            description = "Según por dónde va trae lo encontrado en el origen, las equivalencias y lo que se crearía, el avance "
                    + "o el informe. Mientras lee o escribe se consulta cada poco para ver el avance.")
    @GetMapping("/{jobId}")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse get(@PathVariable String slug, @PathVariable UUID jobId) {
        return importService.get(slug, jobId);
    }

    @Operation(summary = "Conectar con la herramienta de origen",
            description = "Comprueba las credenciales y lista los proyectos. Las credenciales no se guardan.")
    @PostMapping("/connect")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportJobResponse connect(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                     @Valid @RequestBody ImportConnectRequest request) {
        return importService.connect(slug, me.getId(), request);
    }

    @Operation(summary = "Importar desde un archivo", description = "Un CSV de cualquier herramienta o el JSON de un tablero de Trello.")
    @PostMapping("/upload")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportJobResponse upload(@PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
                                    @Valid @RequestBody ImportUploadRequest request) {
        return importService.upload(slug, me.getId(), request);
    }

    @Operation(summary = "Elegir qué traer", description = "Guarda los proyectos y las opciones y empieza a leerlos del origen.")
    @PutMapping("/{jobId}/selection")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse select(@PathVariable String slug, @PathVariable UUID jobId, @AuthenticationPrincipal UserPrincipal me,
                                    @RequestBody ImportSelection selection) {
        return importService.select(slug, jobId, me.getId(), selection);
    }

    @Operation(summary = "Guardar las equivalencias", description = "La respuesta trae lo que se crearía con ellas.")
    @PutMapping("/{jobId}/mapping")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse saveMapping(@PathVariable String slug, @PathVariable UUID jobId,
                                         @AuthenticationPrincipal UserPrincipal me, @RequestBody ImportMapping mapping) {
        return importService.saveMapping(slug, jobId, me.getId(), mapping);
    }

    @Operation(summary = "Empezar a importar", description = "Corre en segundo plano; al terminar avisa en la Bandeja.")
    @PostMapping("/{jobId}/start")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse start(@PathVariable String slug, @PathVariable UUID jobId, @AuthenticationPrincipal UserPrincipal me) {
        return importService.start(slug, jobId, me.getId());
    }

    @Operation(summary = "Detener una importación", description = "Para al terminar la tanda en curso; lo que ya entró se queda.")
    @PostMapping("/{jobId}/stop")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse stop(@PathVariable String slug, @PathVariable UUID jobId) {
        return importService.stop(slug, jobId);
    }

    @Operation(summary = "Deshacer una importación", description = "Borra lo que creó esa importación, y nada más.")
    @PostMapping("/{jobId}/undo")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ImportJobResponse undo(@PathVariable String slug, @PathVariable UUID jobId) {
        return importService.undo(slug, jobId);
    }

    @Operation(summary = "Descartar una importación sin terminar")
    @DeleteMapping("/{jobId}")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable String slug, @PathVariable UUID jobId) {
        importService.discard(slug, jobId);
    }

    @Operation(summary = "Informe de lo que no entró (CSV)")
    @GetMapping(value = "/{jobId}/report", produces = "text/csv")
    @PreAuthorize("@workspaceSecurity.can(#slug, 'DATA_IMPORT')")
    public ResponseEntity<byte[]> report(@PathVariable String slug, @PathVariable UUID jobId) {
        ImportService.FileDownload file = importService.report(slug, jobId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
