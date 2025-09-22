package com.kanstad.task.estado.controller;

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

import com.kanstad.task.estado.Service.EstadoService;
import com.kanstad.task.estado.dto.EstadoDTO;
import com.kanstad.task.estado.dto.EstadoShortDTO;

@RestController
@RequestMapping("/api/estado")
@RequiredArgsConstructor
public class EstadoController {

    private final EstadoService estadoService;

    @GetMapping
    public ResponseEntity<Page<EstadoDTO>> getAllEstados(Pageable pageable) {
        return ResponseEntity.ok(estadoService.getAll(pageable));
    }

    @GetMapping("/short")
    public ResponseEntity<Page<EstadoShortDTO>> getAllEstadosShort(Pageable pageable) {
        return ResponseEntity.ok(estadoService.getAllShort(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EstadoDTO> getEstadoById(@PathVariable Long id) {
        return ResponseEntity.ok(estadoService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<EstadoDTO> createEstado(@Valid @RequestBody EstadoDTO estadoDTO,
                                                  UriComponentsBuilder uriComponentsBuilder) {

        EstadoDTO created = estadoService.create(estadoDTO);

        URI location = uriComponentsBuilder.path("/api/estado/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EstadoDTO> updateEstado(@PathVariable Long id,
                                                  @Valid @RequestBody EstadoDTO estadoDTO) {
        return ResponseEntity.ok(estadoService.update(id, estadoDTO));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEstado(@PathVariable Long id) {
        estadoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}



