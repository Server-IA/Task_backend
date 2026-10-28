package com.taskflow.api.controller;

import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Búsqueda", description = "Búsqueda global de tareas en el workspace")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "Buscar tareas",
            description = "Busca en el título (sin importar mayúsculas) y por código («WEB-12», o «12» en cualquier "
                    + "proyecto), solo en los proyectos a los que tengo acceso. Primero el código exacto y luego las "
                    + "actualizadas más recientemente. Con q vacío devuelve una lista vacía.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public List<IssueResponse> search(
            @PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Texto o código a buscar") @RequestParam(required = false) String q,
            @Parameter(description = "Máximo de resultados (1 a 20)") @RequestParam(defaultValue = "8") int limit) {
        return searchService.searchIssues(slug, me.getId(), q, limit);
    }
}
