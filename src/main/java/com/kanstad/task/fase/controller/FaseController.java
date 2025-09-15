package com.kanstad.task.fase.controller;

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

import com.kanstad.task.fase.dto.FaseDTO;
import com.kanstad.task.fase.services.FaseService;

@RestController
@RequestMapping("/api/fase")
@RequiredArgsConstructor
public class FaseController {

    private final FaseService faseService;

    @GetMapping
    public ResponseEntity<Page<FaseDTO>> getAllFase(Pageable pageable) {
        return ResponseEntity.ok(faseService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FaseDTO> findFaseById(@PathVariable Long id) {
        return ResponseEntity.ok(faseService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<FaseDTO> createFase(@Valid @RequestBody FaseDTO faseDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        FaseDTO created = faseService.create(faseDTO);
        URI location = uriComponentsBuilder.path("/api/fase/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FaseDTO> updateFase(@PathVariable Long id,
                                              @Valid @RequestBody FaseDTO faseDTO){
        return ResponseEntity.ok(faseService.update(id, faseDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFase(@PathVariable Long id){
        faseService.delete(id);
        return ResponseEntity.noContent().build();
    }

       
        

}



