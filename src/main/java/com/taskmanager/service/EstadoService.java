package com.taskmanager.service;

import com.taskmanager.dto.EstadoDTO;
import com.taskmanager.exception.ResourceNotFoundException;
import com.taskmanager.model.Estado;
import com.taskmanager.repository.EstadoRepository;
import com.taskmanager.repository.ProyectoRepository;
import com.taskmanager.repository.TareaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class EstadoService implements IEstadoService {

    @Autowired
    private EstadoRepository estadoRepository;

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private ProyectoRepository proyectoRepository;

    public List<EstadoDTO> findAll() {
        return estadoRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public EstadoDTO findById(Long id) {
        Estado estado = estadoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estado", id));
        return convertToDTO(estado);
    }

    public EstadoDTO create(EstadoDTO dto) {
        Estado estado = new Estado();
        estado.setNombre(dto.getNombre());
        estado.setDescripcion(dto.getDescripcion());
        estado.setColor(dto.getColor());

        Estado saved = estadoRepository.save(estado);
        return convertToDTO(saved);
    }

    public EstadoDTO update(Long id, EstadoDTO dto) {
        Estado estado = estadoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Estado", id));

        estado.setNombre(dto.getNombre());
        estado.setDescripcion(dto.getDescripcion());
        estado.setColor(dto.getColor());

        Estado updated = estadoRepository.save(estado);
        return convertToDTO(updated);
    }

    public void delete(Long id) {
        if (!estadoRepository.existsById(id)) {
            throw new ResourceNotFoundException("Estado", id);
        }

        long tareasEnUso = tareaRepository.countByEstadoId(id);
        long proyectosEnUso = proyectoRepository.countByEstadoId(id);
        if (tareasEnUso > 0 || proyectosEnUso > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, buildEstadoEnUsoMessage(tareasEnUso, proyectosEnUso));
        }

        estadoRepository.deleteById(id);
    }

    private String buildEstadoEnUsoMessage(long tareas, long proyectos) {
        if (tareas > 0 && proyectos > 0) {
            return String.format(
                    "No se puede eliminar este estado porque está en uso por %d tarea(s) y %d proyecto(s). "
                            + "Cambia el estado de esos registros antes de eliminarlo.",
                    tareas, proyectos);
        }
        if (tareas > 0) {
            return String.format(
                    "No se puede eliminar este estado porque está en uso por %d tarea(s). "
                            + "Cambia el estado de esas tareas antes de eliminarlo.",
                    tareas);
        }
        return String.format(
                "No se puede eliminar este estado porque está en uso por %d proyecto(s). "
                        + "Cambia el estado de esos proyectos antes de eliminarlo.",
                proyectos);
    }

    private EstadoDTO convertToDTO(Estado estado) {
        return new EstadoDTO(
                estado.getId(),
                estado.getNombre(),
                estado.getDescripcion(),
                estado.getColor()
        );
    }
}
