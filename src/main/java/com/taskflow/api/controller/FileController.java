package com.taskflow.api.controller;

import com.taskflow.api.dto.response.FileUrlResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Archivos")
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Operation(summary = "URLs firmadas para ver o descargar archivos",
            description = "ids separados por coma (hasta 100): los de las referencias /files/<id> del Markdown, de los "
                    + "adjuntos y de las fotos de perfil (avatarUrl). Solo vienen los que el usuario puede ver: los de "
                    + "proyectos a los que tiene acceso y las fotos de quien comparte un workspace con él; los demás "
                    + "(o los que no existen) se omiten. Las URLs vencen en expiresAt (1 h): hay que pedirlas de nuevo "
                    + "antes. Imágenes y PDF se abren en el navegador; lo demás, o todo con download=true, se descarga "
                    + "con su nombre original.")
    @GetMapping("/urls")
    public List<FileUrlResponse> resolveUrls(@AuthenticationPrincipal UserPrincipal me,
                                             @RequestParam List<UUID> ids,
                                             @RequestParam(defaultValue = "false") boolean download) {
        return fileService.resolveUrls(me.getId(), ids, download);
    }
}
