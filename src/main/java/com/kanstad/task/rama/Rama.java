package com.kanstad.task.rama;

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
@Table(name = "rama", schema = "proyecto")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Rama {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rama_ram_id_seq")
    @SequenceGenerator(name = "rama_ram_id_seq", sequenceName = "proyecto.rama_ram_id_seq", allocationSize = 1)
    @Column(name = "ram_id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "ram_estado_id", referencedColumnName = "est_id")
    private com.kanstad.task.estado.Estado estado;

    @Column(name = "ram_nombre")
    private String nombre;

    @Column(name = "ram_descripcion")
    private String descripcion;


}
