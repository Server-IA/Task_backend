package com.kanstad.task.subSistema.controller;

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

import com.kanstad.task.subSistema.dto.SubSistemaDTO;
import com.kanstad.task.subSistema.services.SubSistemaService;

@RestController
@RequestMapping("/api/sub-sistema")
@RequiredArgsConstructor
public class SubSistemaController {

    private final SubSistemaService subSistemaService;

    @GetMapping
    public ResponseEntity<Page<SubSistemaDTO>> getAllSubSistemas(Pageable pageable) {
        return ResponseEntity.ok(subSistemaService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubSistemaDTO> findSubSistemaById(@PathVariable Long id) {
        return ResponseEntity.ok(subSistemaService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<SubSistemaDTO> createSubSistema(@Valid @RequestBody SubSistemaDTO subSistemaDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        SubSistemaDTO created = subSistemaService.create(subSistemaDTO);
        URI location = uriComponentsBuilder.path("/api/subsistemas/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SubSistemaDTO> updateSubSistema(@PathVariable Long id,
                                              @Valid @RequestBody SubSistemaDTO subSistemaDTO){
        return ResponseEntity.ok(subSistemaService.update(id, subSistemaDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubSistema(@PathVariable Long id){
        subSistemaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
