package com.kanstad.task.proyectoFase.services;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kanstad.task.exception.NotFoundException;

import com.kanstad.task.proyectoFase.ProyectoFase;
import com.kanstad.task.proyectoFase.dto.ProyectoFaseDTO;
import com.kanstad.task.proyectoFase.mappers.ProyectoFaseMapper;
import com.kanstad.task.proyectoFase.repositories.ProyectoFaseRepository;

import com.kanstad.task.proyecto.Proyecto;
import com.kanstad.task.proyecto.repositories.ProyectoRepository;

import com.kanstad.task.fase.Fase;
import com.kanstad.task.fase.repositories.FaseRepository;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;


@Service
@RequiredArgsConstructor
public class ProyectoFaseService {

    private final ProyectoFaseRepository proyectoFaseRepository;
    private final ProyectoFaseMapper proyectoFaseMapper;
    private final ProyectoRepository proyectoRepository;
    private final FaseRepository faseRepository;
    private final EstadoRepository estadoRepository;

    public Page<ProyectoFaseDTO> getAll(Pageable pageable) {
        return proyectoFaseRepository.findAll(pageable)
                .map(proyectoFaseMapper::toDto);
    }

    public ProyectoFaseDTO findByIdOrThrow(Long id) {
        return proyectoFaseRepository.findById(id)
                .map(proyectoFaseMapper::toDto)
                .orElseThrow(() -> new NotFoundException("proyecto-fase.not-found", id));
    }

    public ProyectoFaseDTO create(ProyectoFaseDTO dto) {
        Proyecto proyecto = proyectoRepository.findById(dto.getProyectoId())
                .orElseThrow(() -> new NotFoundException("proyecto.not-found", dto.getProyectoId()));

        Fase fase = faseRepository.findById(dto.getFaseId())
                .orElseThrow(() -> new NotFoundException("fase.not-found", dto.getFaseId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        ProyectoFase proyectoFase = proyectoFaseMapper.toEntity(dto);
        proyectoFase.setProyecto(proyecto);
        proyectoFase.setFase(fase);
        proyectoFase.setEstado(estado);
        proyectoFase.setNombre(dto.getNombre());
        proyectoFase.setDescripcion(dto.getDescripcion());
        proyectoFase.setFechaInicio(dto.getFechaInicio());
        proyectoFase.setFechaFin(dto.getFechaFin());
        return proyectoFaseMapper.toDto(proyectoFaseRepository.save(proyectoFase));
    }

    public ProyectoFaseDTO update(Long id, ProyectoFaseDTO dto) {
        ProyectoFase proyectoFase = proyectoFaseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("proyecto-fase.not-found", id));

        Proyecto proyecto = proyectoRepository.findById(dto.getProyectoId())
                .orElseThrow(() -> new NotFoundException("proyecto.not-found", dto.getProyectoId()));

        Fase fase = faseRepository.findById(dto.getFaseId())
                .orElseThrow(() -> new NotFoundException("fase.not-found", dto.getFaseId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        proyectoFase.setProyecto(proyecto);
        proyectoFase.setFase(fase);
        proyectoFase.setEstado(estado);
        proyectoFase.setNombre(dto.getNombre());
        proyectoFase.setDescripcion(dto.getDescripcion());
        proyectoFase.setFechaInicio(dto.getFechaInicio());
        proyectoFase.setFechaFin(dto.getFechaFin());
        return proyectoFaseMapper.toDto(proyectoFaseRepository.save(proyectoFase));
    }
    
    public void delete(Long id){
        ProyectoFase proyectoFase = proyectoFaseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("proyecto-fase.not-found", id));
        proyectoFaseRepository.delete(proyectoFase);
        
    }

}
