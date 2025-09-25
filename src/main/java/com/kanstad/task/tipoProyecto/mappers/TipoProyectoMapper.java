package com.kanstad.task.tipoProyecto.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.tipoProyecto.TipoProyecto;
import com.kanstad.task.tipoProyecto.dto.TipoProyectoDTO;

@Mapper(componentModel = "spring")
public interface TipoProyectoMapper {

    @Mapping(source = "estado.id", target = "estadoId")
    TipoProyectoDTO toDTO(TipoProyecto tipoProyecto);
    
    @Mapping(source = "estadoId", target = "estado.id")
    TipoProyecto toEntity(TipoProyectoDTO tipoProyectoDTO);
}
