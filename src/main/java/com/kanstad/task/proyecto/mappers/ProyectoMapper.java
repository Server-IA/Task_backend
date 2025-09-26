package com.kanstad.task.proyecto.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.proyecto.Proyecto;
import com.kanstad.task.proyecto.dto.ProyectoDTO;

@Mapper(componentModel = "spring")
public interface ProyectoMapper {

    @Mapping(source = "tipoProyectoId", target = "tipoProyecto.id")
    @Mapping(source = "empresaId", target = "empresa.id")
    @Mapping(source = "estadoId", target = "estado.id")
    Proyecto toEntity(ProyectoDTO dto);

    @Mapping(source = "tipoProyecto.id", target = "tipoProyectoId")
    @Mapping(source = "empresa.id", target = "empresaId")
    @Mapping(source = "estado.id", target = "estadoId")
    ProyectoDTO toDto(Proyecto entity);
}
