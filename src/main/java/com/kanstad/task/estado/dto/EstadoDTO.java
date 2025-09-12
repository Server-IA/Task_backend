package com.kanstad.task.estado.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class EstadoDTO {

    private Long id;

    private String nombre;

    private String descripcion;

    private Long categoriaEstadoId;

    
}
