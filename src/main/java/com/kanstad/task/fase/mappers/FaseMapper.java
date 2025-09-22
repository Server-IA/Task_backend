package com.kanstad.task.fase.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.fase.Fase;
import com.kanstad.task.fase.dto.FaseDTO;

@Mapper(componentModel = "spring")
public interface FaseMapper {

    @Mapping(source = "estadoId", target = "estado.id")
    Fase toEntity(FaseDTO faseDTO);

    @Mapping(source = "estado.id", target = "estadoId")
    FaseDTO toDto(Fase fase);

    

}
