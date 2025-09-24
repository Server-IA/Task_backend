package com.kanstad.task.empresa.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.empresa.Empresa;
import com.kanstad.task.empresa.dto.EmpresaDTO;

@Mapper(componentModel = "spring")
public interface EmpresaMapper {

    @Mapping(source = "estadoId", target = "estado.id")
    Empresa toEntity(EmpresaDTO empresaDTO);

    @Mapping(source = "estado.id", target = "estadoId")
    EmpresaDTO toDto(Empresa empresa);

}
