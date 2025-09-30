package com.kanstad.task.rama.controller;

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

import com.kanstad.task.rama.dto.RamaDTO;
import com.kanstad.task.rama.services.RamaService;

@RestController
@RequestMapping("/api/rama")
@RequiredArgsConstructor
public class RamaController {

    private final RamaService ramaService;

    @GetMapping
    public ResponseEntity<Page<RamaDTO>> getAllRamas(Pageable pageable) {
        return ResponseEntity.ok(ramaService.getAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RamaDTO> findRamaById(@PathVariable Long id) {
        return ResponseEntity.ok(ramaService.findByIdOrThrow(id));
    }

    @PostMapping
    public ResponseEntity<RamaDTO> createRama(@Valid @RequestBody RamaDTO ramaDTO,
                                              UriComponentsBuilder uriComponentsBuilder) {

        RamaDTO created = ramaService.create(ramaDTO);
        URI location = uriComponentsBuilder.path("/api/rama/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RamaDTO> updateRama(@PathVariable Long id,
                                              @Valid @RequestBody RamaDTO ramaDTO){
        return ResponseEntity.ok(ramaService.update(id, ramaDTO));
       }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRama(@PathVariable Long id){
        ramaService.delete(id);
        return ResponseEntity.noContent().build();
       }

}
