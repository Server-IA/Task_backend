package com.taskflow.api.security;

import com.taskflow.api.entity.CustomRole;
import com.taskflow.api.entity.WorkspaceInvitation;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Qué puede hacer cada persona en un workspace. El rol y sus permisos se consultan en la base de datos en cada
 * comprobación (así un cambio de rol vale de inmediato, sin esperar a que venza la sesión):
 * <ul>
 *   <li>Project manager ({@code OWNER}): todos los permisos.</li>
 *   <li>Con un rol creado por el workspace: los de ese rol.</li>
 *   <li>Los demás: los del rol «Miembro» del workspace.</li>
 * </ul>
 * Lo usan las reglas de {@code @PreAuthorize} ({@link WorkspaceSecurity}) y los servicios cuando la regla depende de
 * los datos (por ejemplo, eliminar una tarea propia o la de otra persona).
 */
@Component
@RequiredArgsConstructor
public class AccessControl {

    /** Nombres de los dos roles de fábrica (en español; se muestran en el idioma de la petición). */
    public static final String MANAGER_ROLE_NAME = "Project manager";
    public static final String MEMBER_ROLE_NAME = "Miembro";

    private final WorkspaceMemberRepository memberRepository;

    /**
     * El rol de una persona en un workspace y lo que le permite.
     *
     * @param role         rol base: project manager o miembro
     * @param customRoleId id del rol creado por el workspace; {@code null} con un rol de fábrica
     * @param roleName     nombre del rol como se muestra («Project manager», «Miembro», «Líder»…)
     * @param permissions  permisos efectivos
     */
    public record Access(WorkspaceRole role, UUID customRoleId, String roleName, Set<Permission> permissions) {

        public boolean isManager() {
            return role.isManager();
        }

        public boolean can(Permission permission) {
            return permissions.contains(permission);
        }

        /** Si tiene todos los permisos de {@code required} (para no dar a otro más de lo que uno tiene). */
        public boolean covers(Set<Permission> required) {
            return permissions.containsAll(required);
        }

        public static Access of(WorkspaceMember member) {
            if (member.getRole().isManager()) {
                return new Access(WorkspaceRole.OWNER, null, managerRoleName(), Permission.ALL);
            }
            CustomRole custom = member.getCustomRole();
            if (custom != null) {
                return new Access(WorkspaceRole.MEMBER, custom.getId(), custom.getName(), custom.permissionSet());
            }
            return new Access(WorkspaceRole.MEMBER, null, memberRoleName(), member.getWorkspace().memberPermissionSet());
        }

        /** El de quien acaba de crear el workspace: project manager. */
        public static Access manager() {
            return new Access(WorkspaceRole.OWNER, null, managerRoleName(), Permission.ALL);
        }
    }

    /** «Project manager», en el idioma de la petición. */
    public static String managerRoleName() {
        return Language.current().t(MANAGER_ROLE_NAME);
    }

    /** «Miembro» («Member» en inglés), en el idioma de la petición. */
    public static String memberRoleName() {
        return Language.current().t(MEMBER_ROLE_NAME);
    }

    /** Si el nombre es el de un rol de fábrica en cualquiera de los idiomas (sin importar mayúsculas). */
    public static boolean isFactoryRoleName(String name) {
        for (Language language : Language.values()) {
            if (name.equalsIgnoreCase(language.t(MANAGER_ROLE_NAME))
                    || name.equalsIgnoreCase(language.t(MEMBER_ROLE_NAME))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nombre del rol de un miembro como se muestra: el de fábrica (en el idioma de la petición) o el del rol creado
     * por el workspace.
     */
    public static String roleName(WorkspaceMember member) {
        if (member.getRole().isManager()) {
            return managerRoleName();
        }
        return member.getCustomRole() != null ? member.getCustomRole().getName() : memberRoleName();
    }

    /** Nombre del rol con el que entra una persona invitada. */
    public static String roleName(WorkspaceInvitation invitation) {
        return invitation.getCustomRole() != null ? invitation.getCustomRole().getName() : memberRoleName();
    }

    /** El acceso de la persona; vacío si no es miembro del workspace. */
    public Optional<Access> find(String slug, UUID userId) {
        if (slug == null || userId == null) {
            return Optional.empty();
        }
        return memberRepository.findAccess(slug, userId).map(Access::of);
    }

    public boolean can(String slug, UUID userId, Permission permission) {
        return find(slug, userId).map(access -> access.can(permission)).orElse(false);
    }

    public boolean isManager(String slug, UUID userId) {
        return find(slug, userId).map(Access::isManager).orElse(false);
    }

    /** Lanza 403 con {@code message} si la persona no tiene el permiso (o no es del workspace). */
    public void require(String slug, UUID userId, Permission permission, String message) {
        if (!can(slug, userId, permission)) {
            throw new ForbiddenException(message);
        }
    }
}
