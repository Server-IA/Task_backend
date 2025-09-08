package com.kanstad.task.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "rol" /*schema = "seguridad"*/ )

public class Role {

    @Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "rol_id")
	private Long id;

	@Column(name = "rol_nombre")
	private String name;

	@Column(name = "rol_descripcion")
	private String description;

	@Column(name = "rol_estado_id", columnDefinition = "integer default 1")
	private Integer state;

}
