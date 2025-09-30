package com.kanstad.task.proyectoFase.controller;


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

import com.kanstad.task.proyectoFase.dto.ProyectoFaseDTO;
import com.kanstad.task.proyectoFase.services.ProyectoFaseService;

@RestController
@RequestMapping("/api/proyecto-fase")
@RequiredArgsConstructor
public class ProyectoFaseController {

    private final ProyectoFaseService proyectoFaseService;

    @GetMapping
    public ResponseEntity<Page<ProyectoFaseDTO>> getAllProyectoFase(Pageable pageable) {
        return ResponseEntity.ok(proyectoFaseService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProyectoFaseDTO> findProyectoFaseById(@PathVariable Long id) {
        return ResponseEntity.ok(proyectoFaseService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<ProyectoFaseDTO> createProyectoFase(@Valid @RequestBody ProyectoFaseDTO proyectoFaseDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        ProyectoFaseDTO created = proyectoFaseService.create(proyectoFaseDTO);
        URI location = uriComponentsBuilder.path("/api/proyecto-fase/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProyectoFaseDTO> updateProyectoFase(@PathVariable Long id,
                                              @Valid @RequestBody ProyectoFaseDTO proyectoFaseDTO){
        return ResponseEntity.ok(proyectoFaseService.update(id, proyectoFaseDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProyectoFase(@PathVariable Long id){
        proyectoFaseService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
