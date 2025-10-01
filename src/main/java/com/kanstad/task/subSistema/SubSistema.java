package com.kanstad.task.subSistema;

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
import com.kanstad.task.sistema.Sistema;

@Entity
@Table(name = "sub_sistema", schema = "configuracion")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubSistema {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sub_sistemas_sus_id_seq")
    @SequenceGenerator(name = "sub_sistemas_sus_id_seq", sequenceName = "configuracion.sub_sistema_sus_id_seq", allocationSize = 1)
    @Column(name = "sus_id")
    private Long id;

    @Column(name = "sus_nombre")
    private String nombre;

    @Column(name = "sus_descripcion")
    private String descripcion;

    @ManyToOne
    @JoinColumn(name = "sus_sistema_id", referencedColumnName = "sis_id")
    private Sistema sistema;

    @ManyToOne
    @JoinColumn(name = "sus_estado_id", referencedColumnName = "est_id")
    private Estado estado;

   

}
