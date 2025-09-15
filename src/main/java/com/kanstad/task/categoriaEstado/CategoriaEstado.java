package com.kanstad.task.categoriaEstado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "categoria_estado", schema = "parametrizacion")
public class CategoriaEstado {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "categoria_estado_cae_seq")
	@SequenceGenerator(name = "categoria_estado_cae_seq", sequenceName = "parametrizacion.categoria_estado_cae_id_seq", allocationSize = 1)
    @Column(name ="cae_id")
    private Long id;

    @Column(name= "cae_nombre")
    private String nombre;

    @Column(name="cae_descripcion")
    private String descripcion;
}
