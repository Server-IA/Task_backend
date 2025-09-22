package com.kanstad.task.fase.services;


import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;
import com.kanstad.task.exception.NotFoundException;
import com.kanstad.task.fase.Fase;
import com.kanstad.task.fase.dto.FaseDTO;
import com.kanstad.task.fase.mappers.FaseMapper;
import com.kanstad.task.fase.repositories.FaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;



@Service
@RequiredArgsConstructor
public class FaseService {

    private final FaseRepository faseRepository;
    private final FaseMapper faseMapper;
    private final EstadoRepository estadoRepository;

    public Page<FaseDTO> getAll(Pageable pageable) {
        return faseRepository.findAll(pageable)
                .map(faseMapper::toDto);
    }

    public FaseDTO findByIdOrThrow(Long id) {
        return faseRepository.findById(id)
                .map(faseMapper::toDto)
                .orElseThrow(() -> new NotFoundException("fase.not-found", id));
    }

    public FaseDTO create(FaseDTO dto) {
        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("fase.not-found", dto.getEstadoId()));

        Fase fase = faseMapper.toEntity(dto);
        fase.setEstado(estado);

        Fase saved = faseRepository.save(fase);
        return faseMapper.toDto(saved);
    }

    public FaseDTO update(Long id, FaseDTO dto) {
        Fase fase = faseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("fase.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("fase.not-found", dto.getEstadoId()));

        fase.setNombre(dto.getNombre());
        fase.setDescripcion(dto.getDescripcion());
        fase.setInicio(dto.getInicio());
        fase.setFin(dto.getFin());
        fase.setEstado(estado);

        Fase updated = faseRepository.save(fase);
        return faseMapper.toDto(updated);
    }

    public void delete(Long id) {
        if (!faseRepository.existsById(id)) {
            throw new NotFoundException("fase.not-found", id);
        }
        faseRepository.deleteById(id);
    
    }
}
