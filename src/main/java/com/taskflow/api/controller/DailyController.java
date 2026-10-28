package com.taskflow.api.controller;

import com.taskflow.api.dto.request.DailyNoteRequest;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.dto.response.DailyResponse;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.service.DailyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Daily", description = "Reunión diaria de un proyecto")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/daily")
@PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
@RequiredArgsConstructor
public class DailyController {

    private final DailyService dailyService;

    @Operation(summary = "Daily del proyecto",
            description = "previousDate = «ayer»: el último día antes de date con actividad en el proyecto "
                    + "(historial de las tareas y comentarios), sin pasar del día hábil anterior (el día antes; si date "
                    + "es lunes, sábado o domingo, el viernes); sin actividad en los 14 días anteriores, el día hábil "
                    + "anterior. Por persona (miembros del proyecto y asignados a sus tareas abiertas, por nombre): "
                    + "yesterday = lo que hizo desde previousDate hasta antes de date, una entrada por tarea con el "
                    + "cambio más importante (completó > movió a {estado} > creó > comentó > actualizó), máximo 8; "
                    + "today = sus tareas abiertas en curso (STARTED) o con fecha límite hasta date, por fecha límite, "
                    + "máximo 8; blockers = sus tareas abiertas con bloqueos abiertos (blockedBy con estado y "
                    + "asignados de cada tarea que bloquea; de un proyecto al que no tengo acceso, sin título ni "
                    + "asignados); openCount = sus abiertas; cycleAssignedCount y "
                    + "cycleCompletedCount = sus tareas del ciclo en curso (sin canceladas) y cuántas completó. cycle = "
                    + "ciclo en curso ese día o null. summary = tareas movidas, empezadas y completadas, comentarios y "
                    + "bloqueadas en el periodo. note = nota del día o null.")
    @GetMapping
    public DailyResponse getDaily(
            @PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Día de la reunión (YYYY-MM-DD); por defecto, hoy en America/Bogota")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dailyService.getDaily(slug, projectId, me.getId(), date);
    }

    @Operation(summary = "Guardar la nota del día",
            description = "Cualquier miembro del proyecto. text de hasta 5000 caracteres; vacío (o null) borra la nota "
                    + "y responde 204 sin cuerpo.")
    @ApiResponse(responseCode = "200", description = "Nota guardada")
    @ApiResponse(responseCode = "204", description = "Nota borrada (texto vacío)")
    @PutMapping("/note")
    public ResponseEntity<DailyNoteResponse> saveNote(
            @PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
            @Parameter(description = "Día de la reunión (YYYY-MM-DD); por defecto, hoy en America/Bogota")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody DailyNoteRequest request) {
        return dailyService.saveNote(slug, projectId, me.getId(), date, request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
