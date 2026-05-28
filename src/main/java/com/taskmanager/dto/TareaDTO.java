package com.taskmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TareaDTO {
    private Long id;

    @NotBlank(message = "El título es obligatorio")
    @Size(max = 300, message = "El título no puede superar los 300 caracteres")
    private String titulo;

    @Size(max = 5000, message = "La descripción no puede superar los 5000 caracteres")
    private String descripcion;
    private Long proyectoId;
    private String proyectoNombre;
    private Long estadoId;
    private String estadoNombre;
    private Long asignadoId;
    private String asignadoNombre;
    private List<Long> asignadoIds = new ArrayList<>();
    private List<String> asignadoNombres = new ArrayList<>();
    private Long creadorId;
    private String creadorNombre;
    private String prioridad;
    private LocalDate fechaLimite;
    private LocalDate fechaCompletada;
    private Integer orden;
    private LocalDateTime fechaCreacion;
}
