package com.kanstad.task.estado.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class EstadoShortDTO {

    private Long id;

    private String nombre;
    
    private Long categoriaEstadoId;

    
}
