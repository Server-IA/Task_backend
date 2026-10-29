package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.UpdateProjectRequest;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ProjectColor;
import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.mapper.UserMapperImpl;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectCounts;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectMemberSummary;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private IssueTemplateRepository templateRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IssueRepository issueRepository;
    @Spy
    private UserMapper userMapper = new UserMapperImpl();
    @Spy
    private AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota", null);

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    void unProyectoNuevoNaceConLasPlantillasPorDefecto() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        User pm = new User();
        pm.setId(UUID.randomUUID());
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(lookup.user(pm.getId())).thenReturn(pm);

        projectService.create(SLUG, pm.getId(), new CreateProjectRequest("Sitio web", "web", null, null));

        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(project.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IssueTemplate>> templates = ArgumentCaptor.forClass(List.class);
        verify(templateRepository).saveAll(templates.capture());
        assertThat(templates.getValue())
                .extracting(IssueTemplate::getName)
                .containsExactly("Reporte de bug", "Historia de usuario", "Tarea técnica");
        assertThat(templates.getValue()).allSatisfy(template ->
                assertThat(template.getProject()).isSameAs(project.getValue()));
        verify(stateRepository).saveAll(anyList());
    }

    private Workspace workspaceWithManager() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(lookup.user(any())).thenReturn(new User());
        return workspace;
    }

    private Project savedProject() {
        ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(project.capture());
        return project.getValue();
    }

    @Test
    void sinColorRecibeElMenosUsadoDelWorkspace() {
        Workspace workspace = workspaceWithManager();
        when(projectRepository.findColorsByWorkspaceId(workspace.getId()))
                .thenReturn(List.of(ProjectColor.TEAL, ProjectColor.AMBER, ProjectColor.TEAL));

        projectService.create(SLUG, UUID.randomUUID(), new CreateProjectRequest("App", "APP", null, null));

        assertThat(savedProject().getColor()).isEqualTo(ProjectColor.BLUE);
    }

    @Test
    void conColorUsaElElegido() {
        workspaceWithManager();

        projectService.create(SLUG, UUID.randomUUID(),
                new CreateProjectRequest("App", "APP", null, ProjectColor.PINK));

        assertThat(savedProject().getColor()).isEqualTo(ProjectColor.PINK);
        verify(projectRepository, never()).findColorsByWorkspaceId(any());
    }

    @Test
    void editarCambiaElColorSoloSiSeEnvia() {
        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        project.setColor(ProjectColor.TEAL);
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        projectService.update(SLUG, project.getId(), new UpdateProjectRequest("Sitio", null, null, null));
        assertThat(project.getColor()).isEqualTo(ProjectColor.TEAL);

        projectService.update(SLUG, project.getId(), new UpdateProjectRequest(null, null, null, ProjectColor.VIOLET));
        assertThat(project.getColor()).isEqualTo(ProjectColor.VIOLET);
    }

    @Test
    void quienSaleDelProyectoDejaDeSerAsignadoEnSusPlantillas() {
        Project project = new Project();
        project.setId(UUID.randomUUID());
        UUID userId = UUID.randomUUID();
        ProjectMember member = new ProjectMember(project, new User());
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        when(projectMemberRepository.findByProjectIdAndUserId(project.getId(), userId)).thenReturn(Optional.of(member));

        projectService.removeMember(SLUG, project.getId(), userId);

        verify(projectMemberRepository).delete(member);
        verify(templateRepository).removeAssignee(project.getId(), userId);
        // Y de sus tareas: ya no puede ser asignada ni recibir sus avisos
        verify(issueRepository).removeAssigneeInProject(project.getId(), userId);
    }

    @Test
    void losContadoresSeCalculanEnUnaSolaConsultaConLaFechaDeLaZonaDeLaAplicacion() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Project web = new Project();
        web.setId(UUID.randomUUID());
        Project app = new Project();
        app.setId(UUID.randomUUID());
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        UUID pmId = UUID.randomUUID();
        when(projectAccess.projects(workspace, pmId)).thenReturn(List.of(web, app));
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        when(projectRepository.countIssues(List.of(web.getId(), app.getId()), today))
                .thenReturn(List.<Object[]>of(new Object[] {web.getId(), 7L, 4L, 1L, 2L, 1L}));

        projectService.getProjects(SLUG, pmId);

        verify(projectMapper).toResponse(web, new ProjectCounts(web.getId(), 7, 4, 1, 2, 1),
                ProjectMemberSummary.empty());
        // Sin tareas no aparece en la consulta: todo en cero
        verify(projectMapper).toResponse(app, ProjectCounts.empty(app.getId()), ProjectMemberSummary.empty());
    }

    @Test
    void elResumenDeMiembrosDeTodosLosProyectosSaleDeUnaConsulta() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Project web = new Project();
        web.setId(UUID.randomUUID());
        Project app = new Project();
        app.setId(UUID.randomUUID());
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        UUID pmId = UUID.randomUUID();
        when(projectAccess.projects(workspace, pmId)).thenReturn(List.of(web, app));
        UUID ana = UUID.randomUUID();
        UUID luis = UUID.randomUUID();
        // Filas: proyecto, total de miembros y uno de los primeros cinco (por nombre)
        when(projectMemberRepository.findMemberPreviews(List.of(web.getId(), app.getId()),
                ProjectMemberRepository.MEMBER_PREVIEW)).thenReturn(List.of(
                new Object[] {web.getId(), 7L, ana, "ana@test.com", "Ana", null},
                new Object[] {web.getId(), 7L, luis, "luis@test.com", "Luis", "https://img/luis.png"}));

        projectService.getProjects(SLUG, pmId);

        verify(projectMemberRepository).findMemberPreviews(anyList(), anyInt());
        verify(projectMapper).toResponse(web, ProjectCounts.empty(web.getId()), new ProjectMemberSummary(7, List.of(
                new UserSummaryResponse(ana, "ana@test.com", "Ana", null),
                new UserSummaryResponse(luis, "luis@test.com", "Luis", "https://img/luis.png"))));
        // Sin miembros no aparece en la consulta
        verify(projectMapper).toResponse(app, ProjectCounts.empty(app.getId()), ProjectMemberSummary.empty());
    }

    @Test
    void unProyectoNuevoTieneComoUnicoMiembroAQuienLoCreo() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        User pm = new User();
        pm.setId(UUID.randomUUID());
        pm.setEmail("pm@test.com");
        pm.setFullName("Paula");
        UserSummaryResponse summary = new UserSummaryResponse(pm.getId(), "pm@test.com", "Paula", null);
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(lookup.user(pm.getId())).thenReturn(pm);

        projectService.create(SLUG, pm.getId(), new CreateProjectRequest("Sitio web", "web", null, null));

        verify(projectMapper).toResponse(any(), any(), eq(new ProjectMemberSummary(1, List.of(summary))));
    }
}
