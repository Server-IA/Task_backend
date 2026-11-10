package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.Permission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Rol creado por un workspace («Líder», «Desarrollador»…) con los permisos que se le marcaron. Los dos roles de
 * fábrica no están aquí: el project manager ({@code WorkspaceRole.OWNER}) tiene todos los permisos y «Miembro» usa
 * {@link Workspace#getMemberPermissions()}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workspace_roles")
public class CustomRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(length = 200)
    private String description;

    /** Nombres de {@link Permission}; los que ya no existen se ignoran al leerlos. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> permissions = new ArrayList<>();

    public Set<Permission> permissionSet() {
        return Permission.fromNames(permissions);
    }

    public void setPermissionSet(Collection<Permission> values) {
        this.permissions = new ArrayList<>(Permission.names(values));
    }
}
