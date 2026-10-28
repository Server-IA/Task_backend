package com.taskflow.api.controller;

import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.MyWorkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Mi trabajo", description = "Tareas del usuario en todo el workspace")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/my-work")
@RequiredArgsConstructor
public class MyWorkController {

    private final MyWorkService myWorkService;

    @Operation(summary = "Mis tareas abiertas",
            description = "Tareas abiertas (ni completadas ni canceladas) de los proyectos a los que tengo acceso: "
                    + "asignadas a mí (assigned), creadas por mí (created) o en las que comenté (commented). "
                    + "Orden: fecha límite (sin fecha al final), prioridad (URGENT primero) y número. Incluye los "
                    + "contadores de las tres vistas, las asignadas completadas esta semana y las que están en curso.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.isMember(#slug)")
    public MyWorkResponse getMyWork(
            @PathVariable String slug, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Vista", schema = @Schema(allowableValues = {"assigned", "created", "commented"},
                    defaultValue = "assigned"))
            @RequestParam(defaultValue = "assigned") String view) {
        return myWorkService.getMyWork(slug, me.getId(), MyWorkView.from(view));
    }
}
