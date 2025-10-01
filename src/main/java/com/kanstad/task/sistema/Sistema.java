package com.kanstad.task.sistema;

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
import com.kanstad.task.estado.Estado;

@Entity
@Table(name = "sistema", schema = "configuracion")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sistema {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sistemas_sis_id_seq")
    @SequenceGenerator(name = "sistemas_sis_id_seq", sequenceName = "configuracion.sistema_sis_id_seq", allocationSize = 1)
    @Column(name = "sis_id")
    private Long id;


    @Column(name = "sis_nombre")
    private String nombre;

    @Column(name = "sis_descripcion")
    private String descripcion;

    @ManyToOne
    @JoinColumn(name = "sis_estado_id", referencedColumnName = "est_id")
    private Estado estado;

}
