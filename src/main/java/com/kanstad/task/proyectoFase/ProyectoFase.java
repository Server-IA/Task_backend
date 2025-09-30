package com.kanstad.task.proyectoFase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "proyecto_fase", schema = "proyecto")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProyectoFase {
    
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "proyecto_fase_id_seq")
    @SequenceGenerator(name = "proyecto_fase_id_seq", sequenceName = "proyecto.proyecto_fase_id_seq", allocationSize = 1)
    @Column(name = "prf_id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "prf_proyecto_id", referencedColumnName = "pro_id")
    private com.kanstad.task.proyecto.Proyecto proyecto;

    @ManyToOne
    @JoinColumn(name = "prf_fase_id", referencedColumnName = "fas_id")
    private com.kanstad.task.fase.Fase fase;

    @ManyToOne
    @JoinColumn(name = "prf_estado_id", referencedColumnName = "est_id")
    private com.kanstad.task.estado.Estado estado;

    @Column(name = "prf_nombre")
    private String nombre;

    @Column(name = "prf_descripcion")
    private String descripcion;

    @Column(name = "prf_fecha_inicio")
    private java.time.LocalDate fechaInicio;

    @Column(name = "prf_fecha_fin")
    private java.time.LocalDate fechaFin;
}
