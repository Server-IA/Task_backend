package com.kanstad.task.categoriaEstado.controllers;

import com.kanstad.task.categoriaEstado.CategoriaEstado;
import com.kanstad.task.categoriaEstado.dtos.CategoriaEstadoDTO;
import com.kanstad.task.categoriaEstado.mappers.CategoriaEstadoMapper;
import com.kanstad.task.categoriaEstado.repositories.CategoriaEstadoRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;


import java.net.URI;
import java.util.List;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/categoria-estado")
public class CategoriaEstadoController {

    private final CategoriaEstadoRepository categoriaEstadoRepository;

    private final CategoriaEstadoMapper categoriaEstadoMapper;

  
    @GetMapping
    public ResponseEntity<List<CategoriaEstado>> findAll(Pageable pageable){
        Page<CategoriaEstado> categoriasEstados = categoriaEstadoRepository.findAll(pageable);
        return ResponseEntity.ok(categoriasEstados.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoriaEstado> findById(@PathVariable Long id) {
        return categoriaEstadoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
     public ResponseEntity<CategoriaEstado> create(@Valid @RequestBody CategoriaEstadoDTO  newCategoriaEstadoRequest,
        UriComponentsBuilder ucb) {
        CategoriaEstado saveCategoriaEstado = categoriaEstadoRepository.save(categoriaEstadoMapper.toEntity(newCategoriaEstadoRequest));
        URI locationOfNewCategoriaEstado = ucb.path("/api/categorias-estados/{id}")
                .buildAndExpand(saveCategoriaEstado.getId())
                .toUri();
        return ResponseEntity.created(locationOfNewCategoriaEstado).body(saveCategoriaEstado);

     }

    @PutMapping("/{id}")
    public ResponseEntity<CategoriaEstado> update(@PathVariable Long id, @Valid @RequestBody CategoriaEstado categoriaEstado) {
        return categoriaEstadoRepository.findById(id)
                .map(existingCategoriaEstado -> {
                    existingCategoriaEstado.setNombre(categoriaEstado.getNombre());
                    existingCategoriaEstado.setDescripcion(categoriaEstado.getDescripcion());
                    CategoriaEstado updatedCategoriaEstado = categoriaEstadoRepository.save(existingCategoriaEstado);
                    return ResponseEntity.ok(updatedCategoriaEstado);
                })
                .orElse(ResponseEntity.notFound().build());
            }
    @DeleteMapping("/{id}")    
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return categoriaEstadoRepository.findById(id)
                .map(existingCategoriaEstado -> {
                    categoriaEstadoRepository.delete(existingCategoriaEstado);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

}
