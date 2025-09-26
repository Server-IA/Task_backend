package com.kanstad.task.proyecto.controller;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import lombok.RequiredArgsConstructor;

import com.kanstad.task.proyecto.dto.ProyectoDTO;
import com.kanstad.task.proyecto.services.ProyectoService;

@RestController
@RequestMapping("/api/proyecto")
@RequiredArgsConstructor
public class ProyectoController {

    private final ProyectoService proyectoService;

    @GetMapping
    public ResponseEntity<Page<ProyectoDTO>> getAll(Pageable pageable) {
        return ResponseEntity.ok(proyectoService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProyectoDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(proyectoService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<ProyectoDTO> create(@Valid @RequestBody ProyectoDTO dto, UriComponentsBuilder uriBuilder) {
        ProyectoDTO created = proyectoService.create(dto);
        URI location = uriBuilder.path("/api/proyecto/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProyectoDTO> update(@PathVariable Long id, @Valid @RequestBody ProyectoDTO dto) {
        return ResponseEntity.ok(proyectoService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        proyectoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
