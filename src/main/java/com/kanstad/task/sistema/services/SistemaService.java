package com.kanstad.task.sistema.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kanstad.task.exception.NotFoundException;

import com.kanstad.task.sistema.Sistema;
import com.kanstad.task.sistema.dto.SistemaDTO;
import com.kanstad.task.sistema.mappers.SistemaMapper;
import com.kanstad.task.sistema.repositories.SistemaRepository;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;

@Service
@RequiredArgsConstructor
public class SistemaService {

    private final SistemaRepository sistemaRepository;
    private final SistemaMapper sistemaMapper;
    private final EstadoRepository estadoRepository;

    public Page<SistemaDTO> getAll(Pageable pageable) {
        return sistemaRepository.findAll(pageable)
                .map(sistemaMapper::toDto);
    }

    public SistemaDTO findByIdOrThrow(Long id) {
        return sistemaRepository.findById(id)
                .map(sistemaMapper::toDto)
                .orElseThrow(() -> new NotFoundException("sistema.not-found", id));
    }

    public SistemaDTO create(SistemaDTO dto) {
        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        Sistema sistema = sistemaMapper.toEntity(dto);
        sistema.setEstado(estado);
        sistema.setNombre(dto.getNombre());
        sistema.setDescripcion(dto.getDescripcion());
        return sistemaMapper.toDto(sistemaRepository.save(sistema));
    }

    public SistemaDTO update(Long id, SistemaDTO dto) {
        Sistema existingSistema = sistemaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("sistema.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        existingSistema.setEstado(estado);
        existingSistema.setNombre(dto.getNombre());
        existingSistema.setDescripcion(dto.getDescripcion());
        return sistemaMapper.toDto(sistemaRepository.save(existingSistema));
    }

    public void delete(Long id) {
        Sistema existingSistema = sistemaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("sistema.not-found", id));
        sistemaRepository.delete(existingSistema);
    }

}
