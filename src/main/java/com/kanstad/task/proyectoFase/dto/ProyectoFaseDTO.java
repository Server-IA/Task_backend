package com.kanstad.task.proyectoFase.dto;

import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ProyectoFaseDTO {

    private Long id;

    @NotNull(message = "El campo proyectoId no puede estar vacío")
    private Long proyectoId;

    @NotNull(message = "El campo faseId no puede estar vacío")
    private Long faseId;

    @NotNull(message = "El campo estadoId no puede estar vacío")
    private Long estadoId;

    @NotBlank(message = "El nombre no puede estar vacío")
    @Size(max = 100, message = "El nombre no puede tener más de 100 caracteres")
    private String nombre;

    @Size(max = 200, message = "La descripción no puede tener más de 200 caracteres")
    private String descripcion;

    @NotNull(message = "El campo fechaInicio no puede estar vacío")
    private java.time.LocalDate fechaInicio;

    @NotNull(message = "El campo fechaFin no puede estar vacío")
    private java.time.LocalDate fechaFin;

}
