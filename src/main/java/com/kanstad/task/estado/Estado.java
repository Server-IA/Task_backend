package com.kanstad.task.estado;

import com.kanstad.task.categoriaEstado.CategoriaEstado;

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
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Table(name = "estado", schema = "parametrizacion")
    public class Estado {

        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "estado_est_id_seq")
        @SequenceGenerator(name = "estado_est_id_seq", sequenceName = "parametrizacion.estado_est_id_seq", allocationSize = 1)
        @Column(name = "est_id")
        private Long id;

        @Column(name = "est_nombre")
        private String nombre;

        @Column(name = "est_descripcion")
        private String descripcion;

        @ManyToOne
        @JoinColumn(name = "est_categoria_estado_id", referencedColumnName = "cae_id")
        private CategoriaEstado categoriaEstado;
    }
