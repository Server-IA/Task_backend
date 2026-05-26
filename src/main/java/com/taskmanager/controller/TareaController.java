package com.taskmanager.controller;

import com.taskmanager.dto.TareaDTO;
import com.taskmanager.service.ITareaService;
import com.taskmanager.service.MembershipPermissionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/tareas")
public class TareaController {

    @Autowired
    private ITareaService tareaService;

    @Autowired
    private MembershipPermissionService membershipPermissionService;

    @GetMapping
    public ResponseEntity<List<TareaDTO>> getAll(Authentication authentication) {
        List<TareaDTO> tareas = tareaService.findAccessibleForUser(authentication.getName());
        return ResponseEntity.ok(tareas);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TareaDTO> getById(@PathVariable Long id, Authentication authentication) {
        membershipPermissionService.requireTareaAccess(authentication.getName(), id);
        TareaDTO tarea = tareaService.findById(id);
        return ResponseEntity.ok(tarea);
    }

    @GetMapping("/proyecto/{proyectoId}")
    public ResponseEntity<List<TareaDTO>> getByProyectoId(
            @PathVariable Long proyectoId,
            Authentication authentication) {
        membershipPermissionService.requireProyectoAccess(authentication.getName(), proyectoId);
        List<TareaDTO> tareas = tareaService.findByProyectoId(proyectoId);
        return ResponseEntity.ok(tareas);
    }

    @GetMapping("/asignado/{asignadoId}")
    public ResponseEntity<List<TareaDTO>> getByAsignadoId(
            @PathVariable Long asignadoId,
            Authentication authentication) {
        Long uid = membershipPermissionService.requireUserId(authentication.getName());
        if (!uid.equals(asignadoId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No autorizado para consultar estas tareas");
        }
        List<TareaDTO> tareas = tareaService.findByAsignadoId(asignadoId);
        return ResponseEntity.ok(tareas);
    }

    @GetMapping("/proyecto/{proyectoId}/estado/{estadoId}")
    public ResponseEntity<List<TareaDTO>> getByProyectoAndEstado(
            @PathVariable Long proyectoId,
            @PathVariable Long estadoId,
            Authentication authentication) {
        membershipPermissionService.requireProyectoAccess(authentication.getName(), proyectoId);
        List<TareaDTO> tareas = tareaService.findByProyectoIdAndEstadoId(proyectoId, estadoId);
        return ResponseEntity.ok(tareas);
    }

    @PostMapping
    public ResponseEntity<TareaDTO> create(@Valid @RequestBody TareaDTO dto, Authentication authentication) {
        membershipPermissionService.requireProyectoAccess(authentication.getName(), dto.getProyectoId());
        TareaDTO created = tareaService.create(dto, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TareaDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody TareaDTO dto,
            Authentication authentication) {
        membershipPermissionService.requireTareaAccess(authentication.getName(), id);
        TareaDTO updated = tareaService.updateWithPermissions(id, dto, authentication.getName());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        membershipPermissionService.requireTareaAccess(authentication.getName(), id);
        tareaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
