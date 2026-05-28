package com.taskmanager.service;

import com.taskmanager.dto.TipoProyectoDTO;
import com.taskmanager.exception.ResourceNotFoundException;
import com.taskmanager.model.TipoProyecto;
import com.taskmanager.repository.ProyectoRepository;
import com.taskmanager.repository.TipoProyectoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class TipoProyectoService implements ITipoProyectoService {
    
    @Autowired
    private TipoProyectoRepository tipoProyectoRepository;

    @Autowired
    private ProyectoRepository proyectoRepository;
    
    public List<TipoProyectoDTO> findAll() {
        return tipoProyectoRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    public TipoProyectoDTO findById(Long id) {
        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TipoProyecto", id));
        return convertToDTO(tipoProyecto);
    }
    
    public TipoProyectoDTO create(TipoProyectoDTO dto) {
        TipoProyecto tipoProyecto = new TipoProyecto();
        tipoProyecto.setNombre(dto.getNombre());
        tipoProyecto.setDescripcion(dto.getDescripcion());
        tipoProyecto.setColor(dto.getColor());
        
        TipoProyecto saved = tipoProyectoRepository.save(tipoProyecto);
        return convertToDTO(saved);
    }
    
    public TipoProyectoDTO update(Long id, TipoProyectoDTO dto) {
        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TipoProyecto", id));
        
        tipoProyecto.setNombre(dto.getNombre());
        tipoProyecto.setDescripcion(dto.getDescripcion());
        tipoProyecto.setColor(dto.getColor());
        
        TipoProyecto updated = tipoProyectoRepository.save(tipoProyecto);
        return convertToDTO(updated);
    }
    
    public void delete(Long id) {
        if (!tipoProyectoRepository.existsById(id)) {
            throw new ResourceNotFoundException("TipoProyecto", id);
        }

        long proyectosEnUso = proyectoRepository.countByTipoProyectoId(id);
        if (proyectosEnUso > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    String.format(
                            "No se puede eliminar este tipo de proyecto porque está en uso por %d proyecto(s). "
                                    + "Cambia el tipo de esos proyectos antes de eliminarlo.",
                            proyectosEnUso));
        }

        tipoProyectoRepository.deleteById(id);
    }
    
    private TipoProyectoDTO convertToDTO(TipoProyecto tipoProyecto) {
        return new TipoProyectoDTO(
                tipoProyecto.getId(),
                tipoProyecto.getNombre(),
                tipoProyecto.getDescripcion(),
                tipoProyecto.getColor()
        );
    }
}
