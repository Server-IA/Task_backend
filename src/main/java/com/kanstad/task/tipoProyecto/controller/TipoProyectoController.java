package com.kanstad.task.tipoProyecto.controller;

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

import com.kanstad.task.tipoProyecto.dto.TipoProyectoDTO;
import com.kanstad.task.tipoProyecto.services.TipoProyectoService;

@RestController
@RequestMapping("/api/tipo-proyecto")
@RequiredArgsConstructor
public class TipoProyectoController {

    private final TipoProyectoService tipoProyectoService;

    @GetMapping
    public ResponseEntity<Page<TipoProyectoDTO>> getAllTipoProyecto(Pageable pageable) {
        return ResponseEntity.ok(tipoProyectoService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TipoProyectoDTO> findTipoProyectoById(@PathVariable Long id) {
        return ResponseEntity.ok(tipoProyectoService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<TipoProyectoDTO> createTipoProyecto(@Valid @RequestBody TipoProyectoDTO tipoProyectoDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        TipoProyectoDTO created = tipoProyectoService.create(tipoProyectoDTO);
        URI location = uriComponentsBuilder.path("/api/tipo-proyecto/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TipoProyectoDTO> updateTipoProyecto(@PathVariable Long id,
                                              @Valid @RequestBody TipoProyectoDTO tipoProyectoDTO){
        return ResponseEntity.ok(tipoProyectoService.update(id, tipoProyectoDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTipoProyecto(@PathVariable Long id){
        tipoProyectoService.delete(id);
        return ResponseEntity.noContent().build();
       }

}
