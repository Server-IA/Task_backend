package com.kanstad.task.fase;

import com.kanstad.task.estado.Estado;

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
@Table(name = "fase", schema = "proyecto")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Fase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "fase_fas_id_seq")
    @SequenceGenerator(name = "fase_fas_id_seq", sequenceName = "proyecto.fase_fas_id_seq", allocationSize = 1)
    @Column(name = "fas_id")
    private Long id;

    @Column(name = "fas_nombre")
    private String nombre;

    @Column(name = "fas_descripcion")
    private String descripcion;

    @Column(name = "fas_inicial")
    private Boolean inicio;

    @Column(name = "fas_final")
    private Boolean fin;

    @ManyToOne
    @JoinColumn(name = "fas_estado_id", referencedColumnName = "est_id")
    private Estado estado;


}
