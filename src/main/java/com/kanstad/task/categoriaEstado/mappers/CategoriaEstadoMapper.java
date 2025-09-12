package com.kanstad.task.categoriaEstado.mappers;

import com.kanstad.task.categoriaEstado.CategoriaEstado;
import com.kanstad.task.categoriaEstado.dtos.CategoriaEstadoDTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CategoriaEstadoMapper {
    
    CategoriaEstadoDTO toDTO(CategoriaEstado categoriaEstado);
    CategoriaEstado toEntity(CategoriaEstadoDTO categoriaEstadoDTO);

}
