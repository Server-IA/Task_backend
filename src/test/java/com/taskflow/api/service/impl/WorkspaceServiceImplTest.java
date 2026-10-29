package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.UpdateMemberRoleRequest;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceMember;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.mapper.WorkspaceMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private WorkspaceMemberRepository memberRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private IssueTemplateRepository templateRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private WorkspaceMapper workspaceMapper;

    @InjectMocks
    private WorkspaceServiceImpl workspaceService;

    private Workspace workspace;
    private User creator;

    @BeforeEach
    void setUp() {
        creator = user("Ana");
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSlug(SLUG);
        workspace.setOwner(creator);
        lenient().when(workspaceRepository.findBySlugForUpdate(SLUG)).thenReturn(Optional.of(workspace));
    }

    @Test
    void nadiePuedeTenerMasDeDiezWorkspaces() {
        when(lookup.user(creator.getId())).thenReturn(creator);
        when(workspaceRepository.countByOwnerId(creator.getId()))
                .thenReturn((long) WorkspaceServiceImpl.MAX_OWNED_WORKSPACES);

        assertThatThrownBy(() -> workspaceService.create(creator.getId(), new CreateWorkspaceRequest("Otro", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage(WorkspaceServiceImpl.WORKSPACE_LIMIT_MESSAGE)
                .extracting("code").isEqualTo(ConflictException.WORKSPACE_LIMIT);
        verify(workspaceRepository, never()).save(any());
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private WorkspaceMember member(User user, WorkspaceRole role) {
        WorkspaceMember member = new WorkspaceMember(workspace, user, role);
        when(memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), user.getId()))
                .thenReturn(Optional.of(member));
        return member;
    }

    private void otherManagers(User excluded, WorkspaceMember... others) {
        when(memberRepository.findOthersWithRole(workspace.getId(), WorkspaceRole.OWNER, excluded.getId()))
                .thenReturn(List.of(others));
    }

    @Nested
    class CambiarRol {

        @Test
        void promueveAUnMiembroAProjectManager() {
            WorkspaceMember dev = member(user("Beto"), WorkspaceRole.MEMBER);

            workspaceService.updateMemberRole(SLUG, dev.getUser().getId(),
                    new UpdateMemberRoleRequest(WorkspaceRole.OWNER));

            assertThat(dev.getRole()).isEqualTo(WorkspaceRole.OWNER);
        }

        @Test
        void noDegradaAlUltimoProjectManager() {
            WorkspaceMember pm = member(creator, WorkspaceRole.OWNER);
            otherManagers(creator);

            assertThatThrownBy(() -> workspaceService.updateMemberRole(SLUG, creator.getId(),
                    new UpdateMemberRoleRequest(WorkspaceRole.MEMBER)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("al menos un project manager");
            assertThat(pm.getRole()).isEqualTo(WorkspaceRole.OWNER);
        }

        @Test
        void degradaAUnProjectManagerSiQuedaOtroYLeTraspasaElWorkspace() {
            User second = user("Carla");
            WorkspaceMember pm = member(creator, WorkspaceRole.OWNER);
            otherManagers(creator, new WorkspaceMember(workspace, second, WorkspaceRole.OWNER));

            workspaceService.updateMemberRole(SLUG, creator.getId(), new UpdateMemberRoleRequest(WorkspaceRole.MEMBER));

            assertThat(pm.getRole()).isEqualTo(WorkspaceRole.MEMBER);
            // El dueño registrado del workspace siempre es un project manager
            assertThat(workspace.getOwner()).isSameAs(second);
        }

        @Test
        void mismoRolNoCambiaNada() {
            WorkspaceMember pm = member(creator, WorkspaceRole.OWNER);

            workspaceService.updateMemberRole(SLUG, creator.getId(), new UpdateMemberRoleRequest(WorkspaceRole.OWNER));

            assertThat(pm.getRole()).isEqualTo(WorkspaceRole.OWNER);
            verify(memberRepository, never()).findOthersWithRole(any(), any(), any());
        }
    }

    @Nested
    class QuitarMiembro {

        @Test
        void unMiembroPuedeSalirse() {
            WorkspaceMember dev = member(user("Beto"), WorkspaceRole.MEMBER);
            UUID devId = dev.getUser().getId();

            workspaceService.removeMember(SLUG, devId, devId);

            verify(projectMemberRepository).deleteAllInWorkspace(workspace.getId(), devId);
            // Deja de ser asignado por defecto en las plantillas de los proyectos del workspace
            verify(templateRepository).removeAssigneeInWorkspace(workspace.getId(), devId);
            // Y de sus tareas: ya no las ve, no puede seguir asignado ni recibir sus avisos
            verify(issueRepository).removeAssigneeInWorkspace(workspace.getId(), devId);
            verify(memberRepository).delete(dev);
        }

        @Test
        void unMiembroNoPuedeQuitarAOtro() {
            User dev = user("Beto");
            member(dev, WorkspaceRole.MEMBER);
            User other = user("Dani");

            assertThatThrownBy(() -> workspaceService.removeMember(SLUG, dev.getId(), other.getId()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("project manager");
            verify(memberRepository, never()).delete(any());
        }

        @Test
        void elProjectManagerQuitaAUnMiembro() {
            member(creator, WorkspaceRole.OWNER);
            WorkspaceMember dev = member(user("Beto"), WorkspaceRole.MEMBER);

            workspaceService.removeMember(SLUG, creator.getId(), dev.getUser().getId());

            verify(memberRepository).delete(dev);
        }

        @Test
        void elUltimoProjectManagerNoPuedeSalir() {
            member(creator, WorkspaceRole.OWNER);
            otherManagers(creator);

            assertThatThrownBy(() -> workspaceService.removeMember(SLUG, creator.getId(), creator.getId()))
                    .isInstanceOf(ConflictException.class);
            verify(memberRepository, never()).delete(any());
        }

        @Test
        void unProjectManagerPuedeSalirSiQuedaOtro() {
            User second = user("Carla");
            WorkspaceMember pm = member(creator, WorkspaceRole.OWNER);
            otherManagers(creator, new WorkspaceMember(workspace, second, WorkspaceRole.OWNER));

            workspaceService.removeMember(SLUG, creator.getId(), creator.getId());

            verify(memberRepository).delete(pm);
            assertThat(workspace.getOwner()).isSameAs(second);
        }

        @Test
        void unProjectManagerQuitaAOtroProjectManager() {
            User second = user("Carla");
            member(creator, WorkspaceRole.OWNER);
            WorkspaceMember secondPm = member(second, WorkspaceRole.OWNER);
            otherManagers(second, new WorkspaceMember(workspace, creator, WorkspaceRole.OWNER));

            workspaceService.removeMember(SLUG, creator.getId(), second.getId());

            verify(memberRepository).delete(secondPm);
            assertThat(workspace.getOwner()).isSameAs(creator);
        }
    }
}
