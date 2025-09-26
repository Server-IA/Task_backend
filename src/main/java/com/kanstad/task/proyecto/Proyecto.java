package com.kanstad.task.proyecto;

import com.kanstad.task.estado.Estado;
import com.kanstad.task.empresa.Empresa;
import com.kanstad.task.tipoProyecto.TipoProyecto;

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
@Table(name = "proyecto" , schema = "proyecto")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Proyecto {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "proyecto_pro_id_seq")
    @SequenceGenerator(name = "proyecto_pro_id_seq", sequenceName = "proyecto.proyecto_pro_id_seq", allocationSize = 1)
    @Column(name = "pro_id")
    private Long id;

    @Column(name = "pro_nombre", nullable = false, length = 50)
    private String nombre;

    @Column(name = "pro_descripcion", length = 200)
    private String descripcion;

    @ManyToOne
    @JoinColumn(name = "pro_tipo_proyecto_id", referencedColumnName = "tip_id", nullable = false)
    private TipoProyecto tipoProyecto;

    @ManyToOne
    @JoinColumn(name = "pro_empresa_id", referencedColumnName = "emp_id", nullable = false)
    private Empresa empresa;

    @ManyToOne
    @JoinColumn(name = "pro_estado_id", referencedColumnName = "est_id", nullable = false)
    private Estado estado;

}
