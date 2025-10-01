package com.kanstad.task.subSistema.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.subSistema.SubSistema;
import com.kanstad.task.subSistema.dto.SubSistemaDTO;

@Mapper(componentModel = "spring")
public interface SubSistemaMapper {

    @Mapping(source = "estado.id", target = "estadoId")
    @Mapping(source = "sistema.id", target = "sistemaId")
    SubSistemaDTO toDto(SubSistema subSistema);

    @Mapping(source = "estadoId", target = "estado.id")
    @Mapping(source = "sistemaId", target = "sistema.id")
    SubSistema toEntity(SubSistemaDTO subSistemaDTO);

}
