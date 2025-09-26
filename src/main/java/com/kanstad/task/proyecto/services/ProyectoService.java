package com.kanstad.task.proyecto.services;

import com.kanstad.task.empresa.Empresa;
import com.kanstad.task.estado.Estado;
import com.kanstad.task.empresa.repositories.EmpresaRepository;
import com.kanstad.task.estado.repositories.EstadoRepository;
import com.kanstad.task.exception.NotFoundException;
import com.kanstad.task.proyecto.Proyecto;
import com.kanstad.task.proyecto.dto.ProyectoDTO;
import com.kanstad.task.proyecto.mappers.ProyectoMapper;
import com.kanstad.task.proyecto.repositories.ProyectoRepository;
import com.kanstad.task.tipoProyecto.TipoProyecto;
import com.kanstad.task.tipoProyecto.repositories.TipoProyectoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProyectoService {

    private final ProyectoRepository proyectoRepository;
    private final ProyectoMapper proyectoMapper;
    private final EstadoRepository estadoRepository;
    private final TipoProyectoRepository tipoProyectoRepository;
    private final EmpresaRepository empresaRepository;

    public Page<ProyectoDTO> getAll(Pageable pageable) {
        return proyectoRepository.findAll(pageable)
                .map(proyectoMapper::toDto);
    }

    public ProyectoDTO findByIdOrThrow(Long id) {
        return proyectoRepository.findById(id)
                .map(proyectoMapper::toDto)
                .orElseThrow(() -> new NotFoundException("proyecto.not-found", id));
    }

    public ProyectoDTO create(ProyectoDTO dto) {
        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(dto.getTipoProyectoId())
                .orElseThrow(() -> new NotFoundException("tipo-proyecto.not-found", dto.getTipoProyectoId()));
        Empresa empresa = empresaRepository.findById(dto.getEmpresaId())
                .orElseThrow(() -> new NotFoundException("empresa.not-found", dto.getEmpresaId()));

        Proyecto proyecto = proyectoMapper.toEntity(dto);
        proyecto.setEstado(estado);
        proyecto.setTipoProyecto(tipoProyecto);
        proyecto.setEmpresa(empresa);
        return proyectoMapper.toDto(proyectoRepository.save(proyecto));
    }
    public ProyectoDTO update(Long id, ProyectoDTO dto) {
        Proyecto proyecto = proyectoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("proyecto.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(dto.getTipoProyectoId())
                .orElseThrow(() -> new NotFoundException("tipo-proyecto.not-found", dto.getTipoProyectoId()));
        Empresa empresa = empresaRepository.findById(dto.getEmpresaId())
                .orElseThrow(() -> new NotFoundException("empresa.not-found", dto.getEmpresaId()));

        proyecto.setNombre(dto.getNombre());
        proyecto.setDescripcion(dto.getDescripcion());
        proyecto.setEstado(estado);
        proyecto.setTipoProyecto(tipoProyecto);
        proyecto.setEmpresa(empresa);
        return proyectoMapper.toDto(proyectoRepository.save(proyecto));
    }
    public void delete(Long id) {
        Proyecto proyecto = proyectoRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("proyecto.not-found", id));
        proyectoRepository.delete(proyecto);
    }
}
