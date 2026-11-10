package com.taskflow.api.security;

import com.taskflow.api.entity.CustomRole;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas de {@code @PreAuthorize}: qué puede cada rol (project manager, «Miembro» y los roles creados por el
 * workspace) y qué proyectos ve.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceSecurityTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectRepository projectRepository;

    private WorkspaceSecurity workspaceSecurity;

    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private Workspace workspace;
    private User user;

    @BeforeEach
    void authenticate() {
        workspaceSecurity = new WorkspaceSecurity(new AccessControl(memberRepository), projectMemberRepository,
                projectRepository);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSlug(SLUG);
        user = new User();
        user.setId(userId);
        UserPrincipal principal = UserPrincipal.fromToken(userId, "ana@test.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void asManager() {
        when(memberRepository.findAccess(SLUG, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, user, WorkspaceRole.OWNER)));
    }

    /** Con el rol «Miembro» del workspace (sus permisos de fábrica, salvo que la prueba los cambie). */
    private void asMember() {
        when(memberRepository.findAccess(SLUG, userId))
                .thenReturn(Optional.of(new WorkspaceMember(workspace, user, WorkspaceRole.MEMBER)));
    }

    /** Con un rol creado por el workspace que tiene justo esos permisos. */
    private void withCustomRole(Permission... permissions) {
        CustomRole role = new CustomRole();
        role.setId(UUID.randomUUID());
        role.setWorkspace(workspace);
        role.setName("Líder");
        role.setPermissionSet(Set.of(permissions));
        WorkspaceMember member = new WorkspaceMember(workspace, user, WorkspaceRole.MEMBER);
        member.setCustomRole(role);
        when(memberRepository.findAccess(SLUG, userId)).thenReturn(Optional.of(member));
    }

    private void notMember() {
        when(memberRepository.findAccess(SLUG, userId)).thenReturn(Optional.empty());
    }

    @Test
    void losRolesBaseSonProjectManagerYMiembro() {
        assertThat(WorkspaceRole.values()).containsExactly(WorkspaceRole.OWNER, WorkspaceRole.MEMBER);
        assertThat(WorkspaceRole.OWNER.isManager()).isTrue();
        assertThat(WorkspaceRole.MEMBER.isManager()).isFalse();
    }

    @Test
    void projectManagerTieneTodosLosPermisos() {
        asManager();

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isManager(SLUG)).isTrue();
        for (Permission permission : Permission.values()) {
            assertThat(workspaceSecurity.can(SLUG, permission.name())).as(permission.name()).isTrue();
            assertThat(workspaceSecurity.canInProject(SLUG, projectId, permission.name())).as(permission.name()).isTrue();
        }
    }

    @Test
    void elRolMiembroTraeLosPermisosDeFabricaYNoGestionaElWorkspace() {
        asMember();

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        for (Permission permission : Permission.values()) {
            assertThat(workspaceSecurity.can(SLUG, permission.name())).as(permission.name())
                    .isEqualTo(Permission.MEMBER_DEFAULTS.contains(permission));
        }
        // Lo que antes era solo del project manager sigue sin estar en «Miembro»
        assertThat(Permission.MEMBER_DEFAULTS).doesNotContain(Permission.PROJECT_CREATE, Permission.PROJECT_EDIT,
                Permission.PROJECT_DELETE, Permission.PROJECT_MEMBERS, Permission.PROJECT_STATES, Permission.PROJECT_LABELS,
                Permission.PROJECT_TEMPLATES, Permission.PROJECT_VIEW_ALL, Permission.CYCLE_CREATE, Permission.CYCLE_CLOSE,
                Permission.MODULE_CREATE, Permission.STORY_MANAGE, Permission.STORY_CLOSE, Permission.RETRO_MANAGE,
                Permission.DAILY_SETTINGS, Permission.VIEW_SHARED, Permission.MEMBER_INVITE, Permission.MEMBER_REMOVE,
                Permission.MEMBER_ROLE, Permission.ROLE_MANAGE, Permission.WORKSPACE_EDIT, Permission.STORAGE_VIEW,
                Permission.ISSUE_DELETE_ANY, Permission.COMMENT_DELETE_ANY, Permission.FILE_DELETE_ANY);
    }

    @Test
    void losPermisosDelRolMiembroSePuedenCambiarPorWorkspace() {
        workspace.setMemberPermissionSet(EnumSet.of(Permission.ISSUE_EDIT, Permission.MEMBER_INVITE));
        asMember();

        assertThat(workspaceSecurity.can(SLUG, "MEMBER_INVITE")).isTrue();
        assertThat(workspaceSecurity.can(SLUG, "ISSUE_CREATE")).isFalse();
    }

    @Test
    void unRolCreadoTieneSoloSusPermisos() {
        withCustomRole(Permission.CYCLE_CREATE, Permission.MEMBER_INVITE);
        lenient().when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(true);

        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        assertThat(workspaceSecurity.can(SLUG, "MEMBER_INVITE")).isTrue();
        assertThat(workspaceSecurity.can(SLUG, "ROLE_MANAGE")).isFalse();
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "CYCLE_CREATE")).isTrue();
        // Ni siquiera lo que trae «Miembro»: el rol creado reemplaza, no suma
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "ISSUE_CREATE")).isFalse();
    }

    @Test
    void unPermisoDeProyectoExigeAdemasVerElProyecto() {
        withCustomRole(Permission.CYCLE_CREATE);
        when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(false);

        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "CYCLE_CREATE")).isFalse();

        when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(true);
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "CYCLE_CREATE")).isTrue();
    }

    @Test
    void verTodosLosProyectosDaAccesoSinSerMiembroDeCadaUno() {
        withCustomRole(Permission.PROJECT_VIEW_ALL);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void quienNoPerteneceNoTieneAcceso() {
        notMember();

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        assertThat(workspaceSecurity.can(SLUG, "ISSUE_CREATE")).isFalse();
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "ISSUE_CREATE")).isFalse();
    }

    @Test
    void sinAutenticacionNoConsultaLaBaseDeDatos() {
        SecurityContextHolder.clearContext();

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        assertThat(workspaceSecurity.can(SLUG, "ISSUE_CREATE")).isFalse();
        verifyNoInteractions(memberRepository);
    }

    @Test
    void projectManagerAccedeATodosLosProyectosSinSerMiembro() {
        asManager();

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void unProyectoDeOtroWorkspaceNoSeAccedeNiSeGestionaDesdeEste() {
        // Ana es project manager de su workspace (cualquiera puede crear uno) y pide un proyecto de otro
        asManager();
        when(projectRepository.existsInOtherWorkspace(projectId, SLUG)).thenReturn(true);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isFalse();
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "PROJECT_EDIT")).isFalse();
    }

    @Test
    void miembroSoloAccedeASusProyectosDelWorkspaceDeLaUrl() {
        asMember();
        when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(false);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isFalse();

        when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(true);
        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
    }

    @Test
    void miembroDelProyectoNoPuedeConfigurarlo() {
        asMember();
        lenient().when(projectMemberRepository.existsMemberInWorkspace(projectId, SLUG, userId)).thenReturn(true);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
        // Estados, etiquetas, miembros, ciclos, módulos, editar o borrar el proyecto: no vienen en «Miembro»
        for (String permission : List.of("PROJECT_EDIT", "PROJECT_DELETE", "PROJECT_MEMBERS", "PROJECT_STATES",
                "PROJECT_LABELS", "CYCLE_CREATE", "MODULE_CREATE")) {
            assertThat(workspaceSecurity.canInProject(SLUG, projectId, permission)).as(permission).isFalse();
        }
        // Pero sí trabajar las tareas
        assertThat(workspaceSecurity.canInProject(SLUG, projectId, "ISSUE_EDIT")).isTrue();
    }

    @Test
    void sinSerMiembroDelWorkspaceNoAccedeAlProyecto() {
        notMember();

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isFalse();
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void unPermisoMalEscritoEnUnaReglaFallaEnVezDeNegarEnSilencio() {
        assertThatThrownBy(() -> workspaceSecurity.can(SLUG, "ISSUE_CRAETE")).isInstanceOf(IllegalArgumentException.class);
    }
}
