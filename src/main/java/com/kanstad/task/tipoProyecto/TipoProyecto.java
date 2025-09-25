package com.kanstad.task.tipoProyecto;

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
@Table(name = "tipo_proyecto", schema = "parametrizacion")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TipoProyecto {
    
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tipo_proyecto_tip_id_seq")
    @SequenceGenerator(name = "tipo_proyecto_tip_id_seq", sequenceName = "parametrizacion.tipo_proyecto_tip_id_seq", allocationSize = 1)
    @Column(name = "tip_id")
    private Long id;

    @Column(name = "tip_nombre")
    private String nombre;

    @Column(name = "tip_descripcion")
    private String descripcion;

    @ManyToOne
    @JoinColumn(name = "tip_estado_id", referencedColumnName = "est_id")
    private com.kanstad.task.estado.Estado estado;

}
