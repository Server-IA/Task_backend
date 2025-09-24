package com.kanstad.task.empresa;

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
@Table(name = "empresa", schema = "seguridad")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "empresa_emp_id_seq")
    @SequenceGenerator(name = "empresa_emp_id_seq", sequenceName = "seguridad.empresa_emp_id_seq", allocationSize = 1)
    @Column(name = "emp_id")
    private Long id;

    @Column(name = "emp_nombre")
    private String nombre;
 

    @Column(name = "emp_descripcion")
    private String descripcion;

    @Column(name = "emp_correo", unique = true, length = 100)
    private String correo;

    @ManyToOne
    @JoinColumn(name ="emp_estado_id", referencedColumnName= "est_id")
    private Estado estado;
}
