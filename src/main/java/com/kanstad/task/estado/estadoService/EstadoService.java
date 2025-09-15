package com.kanstad.task.estado.estadoService;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;
import com.kanstad.task.estado.dto.EstadoDTO;
import com.kanstad.task.estado.mappers.EstadoMapper;
import com.kanstad.task.categoriaEstado.CategoriaEstado;
import com.kanstad.task.categoriaEstado.repositories.CategoriaEstadoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.kanstad.task.estado.dto.EstadoShortDTO;


@Service
@RequiredArgsConstructor
public class EstadoService {

    private final EstadoRepository estadoRepository;
    private final CategoriaEstadoRepository categoriaEstadoRepository;
    private final EstadoMapper estadoMapper;

    /**
     * Obtiene todos los estados.
     */
    public Page<EstadoDTO> getAll(Pageable pageable) {
    return estadoRepository.findAll(pageable)
            .map(estadoMapper::toDto); // el map de Page convierte cada entidad en DTO
}
        public Page<EstadoShortDTO> getAllShort(Pageable pageable) {
        return estadoRepository.findAll(pageable)
                .map(estadoMapper::toShortDTO); // el map de Page convierte cada entidad en DTO
        }

    /**
     * Obtiene un estado por su id.
     */
    public EstadoDTO getById(Long id) {
        return estadoRepository.findById(id)
                .map(estadoMapper::toDto)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El estado con id " + id + " no existe"
                ));
    }

    /**
     * Crea un nuevo estado validando la categoría.
     */
    public EstadoDTO create(EstadoDTO dto) {
        CategoriaEstado categoria = categoriaEstadoRepository.findById(dto.getCategoriaEstadoId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "La categoría con id " + dto.getCategoriaEstadoId() + " no existe"
                ));

        Estado estado = estadoMapper.toEntity(dto);
        estado.setCategoriaEstado(categoria);

        Estado saved = estadoRepository.save(estado);
        return estadoMapper.toDto(saved);
    }

    /**
     * Actualiza un estado existente.
     */
    public EstadoDTO update(Long id, EstadoDTO dto) {
        Estado estado = estadoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El estado con id " + id + " no existe"
                ));

        CategoriaEstado categoria = categoriaEstadoRepository.findById(dto.getCategoriaEstadoId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "La categoría con id " + dto.getCategoriaEstadoId() + " no existe"
                ));

        estado.setNombre(dto.getNombre());
        estado.setDescripcion(dto.getDescripcion());
        estado.setCategoriaEstado(categoria);

        Estado updated = estadoRepository.save(estado);
        return estadoMapper.toDto(updated);
    }

    /**
     * Elimina un estado por id.
     */
    public void delete(Long id) {
        if (!estadoRepository.existsById(id)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "El estado con id " + id + " no existe"
            );
        }
        estadoRepository.deleteById(id);
    }
}