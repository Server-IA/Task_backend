package com.kanstad.task.subSistema.services;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kanstad.task.exception.NotFoundException;

import com.kanstad.task.subSistema.SubSistema;
import com.kanstad.task.subSistema.dto.SubSistemaDTO;
import com.kanstad.task.subSistema.mappers.SubSistemaMapper;
import com.kanstad.task.subSistema.repositories.SubSistemaRepository;
import com.kanstad.task.sistema.Sistema;

import com.kanstad.task.sistema.repositories.SistemaRepository;
import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;

@Service
@RequiredArgsConstructor
public class SubSistemaService {

    private final SubSistemaRepository subSistemaRepository;
    private final SubSistemaMapper subSistemaMapper;
    private final SistemaRepository sistemaRepository;
    private final EstadoRepository estadoRepository;

    public Page<SubSistemaDTO> getAll(Pageable pageable) {
        return subSistemaRepository.findAll(pageable)
                .map(subSistemaMapper::toDto);
    }

    public SubSistemaDTO findByIdOrThrow(Long id) {
        return subSistemaRepository.findById(id)
                .map(subSistemaMapper::toDto)
                .orElseThrow(() -> new NotFoundException("subSistema.not-found", id));
    }

    public SubSistemaDTO create(SubSistemaDTO dto) {
        Sistema sistema = sistemaRepository.findById(dto.getSistemaId())
                .orElseThrow(() -> new NotFoundException("sistema.not-found", dto.getSistemaId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        SubSistema subSistema = subSistemaMapper.toEntity(dto);
        subSistema.setSistema(sistema);
        subSistema.setEstado(estado);
        subSistema.setNombre(dto.getNombre());
        subSistema.setDescripcion(dto.getDescripcion());
        return subSistemaMapper.toDto(subSistemaRepository.save(subSistema));
    }

    public SubSistemaDTO update(Long id, SubSistemaDTO dto) {
        SubSistema existingSubSistema = subSistemaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("subSistema.not-found", id));

        Sistema sistema = sistemaRepository.findById(dto.getSistemaId())
                .orElseThrow(() -> new NotFoundException("sistema.not-found", dto.getSistemaId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        existingSubSistema.setSistema(sistema);
        existingSubSistema.setEstado(estado);
        existingSubSistema.setNombre(dto.getNombre());
        existingSubSistema.setDescripcion(dto.getDescripcion());
        return subSistemaMapper.toDto(subSistemaRepository.save(existingSubSistema));
    }

    public void delete(Long id) {
        if (!subSistemaRepository.existsById(id)) {
            throw new NotFoundException("subSistema.not-found", id);
        }
        subSistemaRepository.deleteById(id);
    }

}
