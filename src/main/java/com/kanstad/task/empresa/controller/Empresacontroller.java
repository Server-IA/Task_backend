package com.kanstad.task.empresa.controller;

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

import com.kanstad.task.empresa.dto.EmpresaDTO;
import com.kanstad.task.empresa.service.EmpresaService;

@RestController
@RequestMapping("/api/empresa")
@RequiredArgsConstructor
public class Empresacontroller {

    private final EmpresaService empresaService;

    @GetMapping
    public ResponseEntity<Page<EmpresaDTO>> getAllEmpresas(Pageable pageable) {
        return ResponseEntity.ok(empresaService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmpresaDTO> findEmpresaById(@PathVariable Long id) {
        return ResponseEntity.ok(empresaService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<EmpresaDTO> createEmpresa(@Valid @RequestBody EmpresaDTO empresaDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        EmpresaDTO created = empresaService.create(empresaDTO);
        URI location = uriComponentsBuilder.path("/empresa/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmpresaDTO> updateEmpresa(@PathVariable Long id,
                                              @Valid @RequestBody EmpresaDTO empresaDTO){
        return ResponseEntity.ok(empresaService.update(id, empresaDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmpresa(@PathVariable Long id){
        empresaService.delete(id);
        return ResponseEntity.noContent().build();
       }

}
