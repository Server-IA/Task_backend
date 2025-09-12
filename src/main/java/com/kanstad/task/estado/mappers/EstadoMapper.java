package com.kanstad.task.estado.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.estado.Estado;
import com.kanstad.task.estado.dto.EstadoDTO;

@Mapper(componentModel = "spring")
public interface EstadoMapper {

    @Mapping(source = "categoriaEstadoId", target = "categoriaEstado.id")
    Estado toEntity(EstadoDTO estadoDTO);

    @Mapping(source = "categoriaEstado.id", target = "categoriaEstadoId")
    EstadoDTO toDto(Estado estado);

    @Mapping(target = "descripcion", ignore = true)
    @Mapping(target = "categoriaEstadoId", source = "categoriaEstado.id")
    @Mapping(target = "id", source = "id")
    @Mapping(target = "nombre", source = "nombre")
    EstadoDTO toShortDTO(Estado estado);

}
