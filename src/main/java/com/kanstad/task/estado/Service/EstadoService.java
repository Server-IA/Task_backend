package com.kanstad.task.estado.Service;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;
import com.kanstad.task.exception.NotFoundException;
import com.kanstad.task.estado.dto.EstadoDTO;
import com.kanstad.task.estado.mappers.EstadoMapper;
import com.kanstad.task.categoriaEstado.CategoriaEstado;
import com.kanstad.task.categoriaEstado.repositories.CategoriaEstadoRepository;


import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kanstad.task.estado.dto.EstadoShortDTO;

@Service
@RequiredArgsConstructor
public class EstadoService {

        private final EstadoRepository estadoRepository;
        private final CategoriaEstadoRepository categoriaEstadoRepository;
        private final EstadoMapper estadoMapper;
        private final MessageSource messageSource;

        /**
         * Obtiene todos los estados.
         */
        public Page<EstadoDTO> getAll(Pageable pageable) {
                return estadoRepository.findAll(pageable)
                                .map(estadoMapper::toDto); 
        }

        public Page<EstadoShortDTO> getAllShort(Pageable pageable) {
                return estadoRepository.findAll(pageable)
                                .map(estadoMapper::toShortDTO);

        }

        public EstadoDTO findByIdOrThrow(Long id) {
                return estadoRepository.findById(id)
                                .map(estadoMapper::toDto)
                                .orElseThrow(() -> new NotFoundException("estado.not-found", id));
        }

        
        public EstadoDTO create(EstadoDTO dto) {
                CategoriaEstado categoria = categoriaEstadoRepository.findById(dto.getCategoriaEstadoId())
                                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getCategoriaEstadoId()));

                Estado estado = estadoMapper.toEntity(dto);
                estado.setCategoriaEstado(categoria);

                Estado saved = estadoRepository.save(estado);
                return estadoMapper.toDto(saved);
        }

        
        public EstadoDTO update(Long id, EstadoDTO dto) {
                Estado estado = estadoRepository.findById(id)
                                .orElseThrow(() -> new NotFoundException("estado.not-found", id));

                CategoriaEstado categoria = categoriaEstadoRepository.findById(dto.getCategoriaEstadoId())
                                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getCategoriaEstadoId()));

                estado.setNombre(dto.getNombre());
                estado.setDescripcion(dto.getDescripcion());
                estado.setCategoriaEstado(categoria);

                Estado updated = estadoRepository.save(estado);
                return estadoMapper.toDto(updated);
        }

      
        public void delete(Long id) {
                if (!estadoRepository.existsById(id)) {
                        throw new NotFoundException("estado.not-found", id);
                }
                estadoRepository.deleteById(id);
        }
}