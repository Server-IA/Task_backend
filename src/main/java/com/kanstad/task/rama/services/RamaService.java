package com.kanstad.task.rama.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kanstad.task.exception.NotFoundException;

import com.kanstad.task.rama.Rama;
import com.kanstad.task.rama.dto.RamaDTO;
import com.kanstad.task.rama.mappers.RamaMapper;
import com.kanstad.task.rama.repositories.RamaRepository;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;


@Service
@RequiredArgsConstructor
public class RamaService {

    private final RamaRepository ramaRepository;
    private final RamaMapper ramaMapper;
    private final EstadoRepository estadoRepository;

    public Page<RamaDTO> getAll(Pageable pageable) {
        return ramaRepository.findAll(pageable)
                .map(ramaMapper::toDto);
    }

    public RamaDTO findByIdOrThrow(Long id) {
        return ramaRepository.findById(id)
                .map(ramaMapper::toDto)
                .orElseThrow(() -> new NotFoundException("rama.not-found", id));
    }

    public RamaDTO create(RamaDTO dto) {
        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        Rama rama = ramaMapper.toEntity(dto);
        rama.setEstado(estado);
        rama.setNombre(dto.getNombre());
        rama.setDescripcion(dto.getDescripcion());
        return ramaMapper.toDto(ramaRepository.save(rama));
    }

    public RamaDTO update(Long id, RamaDTO dto) {
        Rama existingRama = ramaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("rama.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        existingRama.setEstado(estado);
        existingRama.setNombre(dto.getNombre());
        existingRama.setDescripcion(dto.getDescripcion());
        return ramaMapper.toDto(ramaRepository.save(existingRama));
    }

    public void delete(Long id) {
        if (!ramaRepository.existsById(id)) {
            throw new NotFoundException("rama.not-found", id);
        }
        ramaRepository.deleteById(id);
    }
}
