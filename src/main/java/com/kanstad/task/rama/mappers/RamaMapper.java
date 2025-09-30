package com.kanstad.task.rama.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.rama.Rama;
import com.kanstad.task.rama.dto.RamaDTO;

@Mapper(componentModel = "spring")
public interface RamaMapper {

    @Mapping(source = "estado.id", target = "estadoId")
    RamaDTO toDto(Rama rama);

    @Mapping(source = "estadoId", target = "estado.id")
    Rama toEntity(RamaDTO ramaDTO);
    
}
