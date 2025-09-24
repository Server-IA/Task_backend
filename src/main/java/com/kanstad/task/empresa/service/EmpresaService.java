package com.kanstad.task.empresa.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;


import com.kanstad.task.empresa.mappers.EmpresaMapper;
import com.kanstad.task.empresa.dto.EmpresaDTO;
import com.kanstad.task.empresa.Empresa;
import com.kanstad.task.empresa.repositories.EmpresaRepository;
import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.repositories.EstadoRepository;
import com.kanstad.task.exception.NotFoundException;
import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class EmpresaService {

    private final EmpresaRepository empresaRepository;
    private final EmpresaMapper empresaMapper;
    private final EstadoRepository estadoRepository;


    public Page<EmpresaDTO> getAll(Pageable pageable) {
        return empresaRepository.findAll(pageable)
                .map(empresaMapper::toDto);
    }

    public EmpresaDTO findByIdOrThrow(Long id) {
        return empresaRepository.findById(id)
                .map(empresaMapper::toDto)
                .orElseThrow(() -> new NotFoundException("empresa.not-found", id));
    }

    public EmpresaDTO create(EmpresaDTO dto) {
    // Validar que el correo no exista previamente
    if (empresaRepository.findByCorreo(dto.getCorreo()).isPresent()) {
        throw new NotFoundException("empresa.email-exists", dto.getCorreo());
    }

    Estado estado = estadoRepository.findById(dto.getEstadoId())
        .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

    Empresa empresa = empresaMapper.toEntity(dto);
    empresa.setEstado(estado);

    Empresa saved = empresaRepository.save(empresa);
    return empresaMapper.toDto(saved);
    }

    public EmpresaDTO update(Long id, EmpresaDTO dto) {
        Empresa empresa = empresaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("empresa.not-found", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new NotFoundException("estado.not-found", dto.getEstadoId()));

        empresa.setNombre(dto.getNombre());
        empresa.setDescripcion(dto.getDescripcion());
        empresa.setCorreo(dto.getCorreo());
        empresa.setEstado(estado);

        Empresa updated = empresaRepository.save(empresa);
        return empresaMapper.toDto(updated);
    }

    public void delete(Long id) {
        if (!empresaRepository.existsById(id)) {
            throw new NotFoundException("empresa.not-found", id);
        }
        empresaRepository.deleteById(id);
    }
}



