package com.taskflow.api.security;

import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceSecurityTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @InjectMocks
    private WorkspaceSecurity workspaceSecurity;

    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        UserPrincipal principal = UserPrincipal.fromToken(userId, "ana@test.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void soloExistenLosRolesProjectManagerYMiembro() {
        assertThat(WorkspaceRole.values()).containsExactly(WorkspaceRole.OWNER, WorkspaceRole.MEMBER);
        assertThat(WorkspaceRole.OWNER.isManager()).isTrue();
        assertThat(WorkspaceRole.MEMBER.isManager()).isFalse();
    }

    @Test
    void projectManagerTieneTodosLosPermisos() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.OWNER));

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isManager(SLUG)).isTrue();
        assertThat(workspaceSecurity.canManageProject(SLUG, projectId)).isTrue();
    }

    @Test
    void miembroNoGestionaElWorkspace() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
    }

    @Test
    void quienNoPerteneceNoTieneAcceso() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.empty());

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        assertThat(workspaceSecurity.canManageProject(SLUG, projectId)).isFalse();
    }

    @Test
    void sinAutenticacionNoConsultaLaBaseDeDatos() {
        SecurityContextHolder.clearContext();

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        assertThat(workspaceSecurity.isManager(SLUG)).isFalse();
        verifyNoInteractions(memberRepository);
    }

    @Test
    void projectManagerAccedeATodosLosProyectosSinSerMiembro() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.OWNER));

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    void miembroSoloAccedeASusProyectos() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
        when(projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)).thenReturn(false);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isFalse();

        when(projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)).thenReturn(true);
        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
    }

    @Test
    void miembroDelProyectoNoPuedeConfigurarlo() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));
        lenient().when(projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)).thenReturn(true);

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isTrue();
        // Estados, etiquetas, miembros, ciclos, módulos, editar o borrar el proyecto: solo el project manager.
        assertThat(workspaceSecurity.canManageProject(SLUG, projectId)).isFalse();
    }

    @Test
    void sinSerMiembroDelWorkspaceNoAccedeAlProyecto() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.empty());

        assertThat(workspaceSecurity.canAccessProject(SLUG, projectId)).isFalse();
        verifyNoInteractions(projectMemberRepository);
    }
}
