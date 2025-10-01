package com.kanstad.task.sistema.controller;

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

import com.kanstad.task.sistema.dto.SistemaDTO;
import com.kanstad.task.sistema.services.SistemaService;

@RestController
@RequestMapping("/api/sistema")
@RequiredArgsConstructor
public class SistemaController {

    private final SistemaService sistemaService;

    @GetMapping
    public ResponseEntity<Page<SistemaDTO>> getAllSistemas(Pageable pageable) {
        return ResponseEntity.ok(sistemaService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SistemaDTO> findSistemaById(@PathVariable Long id) {
        return ResponseEntity.ok(sistemaService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<SistemaDTO> createSistema(@Valid @RequestBody SistemaDTO sistemaDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        SistemaDTO created = sistemaService.create(sistemaDTO);
        URI location = uriComponentsBuilder.path("/api/sistemas/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SistemaDTO> updateSistema(@PathVariable Long id,
                                              @Valid @RequestBody SistemaDTO sistemaDTO){
        return ResponseEntity.ok(sistemaService.update(id, sistemaDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSistema(@PathVariable Long id){
        sistemaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
