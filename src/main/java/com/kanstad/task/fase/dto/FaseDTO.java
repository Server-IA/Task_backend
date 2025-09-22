package com.kanstad.task.fase.dto;

import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FaseDTO {

    private Long id;

    @Size(max = 50, message = "El nombre no puede tener más de 50 caracteres")
    private String nombre;

    @NotBlank(message = "La descripción no puede estar vacía")
    @Size(max = 255, message = "La descripción no puede tener más de 255 caracteres")
    private String descripcion;
    @NotNull(message = "El campo inicio no puede estar vacío")
    private Boolean inicio;

    @NotNull(message = "El campo fin no puede estar vacío")
    private Boolean fin;

    @NotNull(message = "El campo estadoId no puede estar vacío")
    private Long estadoId;

}
