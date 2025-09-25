package com.kanstad.task.tipoProyecto.services;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;

import com.kanstad.task.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.kanstad.task.tipoProyecto.TipoProyecto;
import com.kanstad.task.tipoProyecto.dto.TipoProyectoDTO;
import com.kanstad.task.tipoProyecto.mappers.TipoProyectoMapper;
import com.kanstad.task.tipoProyecto.repositories.TipoProyectoRepository;

@Service
@RequiredArgsConstructor
public class TipoProyectoService {

    private final TipoProyectoRepository tipoProyectoRepository;
    private final TipoProyectoMapper tipoProyectoMapper;
    private final EstadoRepository estadoRepository;

    public Page<TipoProyectoDTO> getAll(Pageable pageable) {
        return tipoProyectoRepository.findAll(pageable)
                .map(tipoProyectoMapper::toDTO);
    }

    public TipoProyectoDTO findByIdOrThrow(Long id) {
        return tipoProyectoRepository.findById(id)
                .map(tipoProyectoMapper::toDTO)
                .orElseThrow(() -> new NotFoundException("tipoProyecto.not-found", id));
    }

    public TipoProyectoDTO create(TipoProyectoDTO dto) {
        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        TipoProyecto tipoProyecto = tipoProyectoMapper.toEntity(dto);
        tipoProyecto.setEstado(estado);

        TipoProyecto saved = tipoProyectoRepository.save(tipoProyecto);
        return tipoProyectoMapper.toDTO(saved);
    }

    public TipoProyectoDTO update(Long id, TipoProyectoDTO dto) {
        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("tipoProyecto.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        tipoProyecto.setNombre(dto.getNombre());
        tipoProyecto.setDescripcion(dto.getDescripcion());
        tipoProyecto.setEstado(estado);

        TipoProyecto updated = tipoProyectoRepository.save(tipoProyecto);
        return tipoProyectoMapper.toDTO(updated);
    }

    public void delete(Long id) {
        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("tipoProyecto.not-found", id));
        tipoProyectoRepository.delete(tipoProyecto);
    }
}