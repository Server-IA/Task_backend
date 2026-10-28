package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.mapper.ProjectMapper;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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

        projectService.create(SLUG, pm.getId(), new CreateProjectRequest("Sitio web", "web", null));

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
}
