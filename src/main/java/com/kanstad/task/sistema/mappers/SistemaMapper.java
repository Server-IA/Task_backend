package com.kanstad.task.sistema.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.sistema.Sistema;
import com.kanstad.task.sistema.dto.SistemaDTO;

@Mapper(componentModel = "spring")
public interface SistemaMapper {

    @Mapping(source = "estado.id", target = "estadoId")
    SistemaDTO toDto(Sistema sistema);

    @Mapping(source = "estadoId", target = "estado.id")
    Sistema toEntity(SistemaDTO sistemaDTO);

    

}
