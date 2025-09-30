package com.kanstad.task.proyectoFase.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.kanstad.task.proyectoFase.ProyectoFase;
import com.kanstad.task.proyectoFase.dto.ProyectoFaseDTO;

@Mapper(componentModel = "spring")
public interface ProyectoFaseMapper {
    @Mapping(source = "proyectoId", target = "proyecto.id")
    @Mapping(source = "faseId", target = "fase.id")
    @Mapping(source = "estadoId", target = "estado.id")
    ProyectoFase toEntity(ProyectoFaseDTO proyectoFaseDTO);

    @Mapping(source = "proyecto.id", target = "proyectoId")
    @Mapping(source = "fase.id", target = "faseId")
    @Mapping(source = "estado.id", target = "estadoId")
    ProyectoFaseDTO toDto(ProyectoFase proyectoFase);

    
}
