package com.kanstad.task.proyecto.dto;

import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class ProyectoDTO {

    private Long id;

    @NotBlank(message = "El nombre no puede estar vacío")
    @Size(max = 50, message = "El nombre no puede tener más de 50 caracteres")
    private String nombre;

    @Size(max = 200, message = "La descripción no puede tener más de 200 caracteres")
    private String descripcion;

    @NotNull(message = "El campo tipoProyectoId no puede estar vacío")
    private Long tipoProyectoId;

    @NotNull(message = "El campo empresaId no puede estar vacío")
    private Long empresaId;

    @NotNull(message = "El campo estadoId no puede estar vacío")
    private Long estadoId;

}
