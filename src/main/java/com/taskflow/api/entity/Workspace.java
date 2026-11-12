package com.taskflow.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.taskflow.api.entity.enums.Permission;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "workspaces")
public class Workspace extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, unique = true, length = 48)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * Permisos del rol «Miembro» de este workspace (nombres de {@link Permission}): los de quien no es project manager
     * ni tiene un rol propio. Empiezan en {@link Permission#MEMBER_DEFAULTS} y quien gestiona los roles los cambia.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "member_permissions", nullable = false, columnDefinition = "jsonb")
    private List<String> memberPermissions = new ArrayList<>(Permission.names(Permission.MEMBER_DEFAULTS));

    public Set<Permission> memberPermissionSet() {
        return Permission.fromNames(memberPermissions);
    }

    public void setMemberPermissionSet(Collection<Permission> values) {
        this.memberPermissions = new ArrayList<>(Permission.names(values));
    }
}
