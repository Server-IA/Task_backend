package com.taskflow.api.imports;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lo que espera a las personas a las que se invitó desde una importación. Quedan apuntadas por correo y, cuando la
 * persona acepta la invitación:
 * <ul>
 *   <li>entra a los proyectos de esa importación, aunque no tenga tareas en ellos;</li>
 *   <li>se le asignan las tareas que en el origen eran suyas (en TaskFlow una tarea solo se le asigna a quien ya es
 *       miembro).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ImportPendingAssignments {

    private final ImportStore store;
    private final IssueRepository issueRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    /** Corre dentro de la transacción en la que la persona acepta la invitación. */
    public void apply(Workspace workspace, User user) {
        String email = user.getEmail().toLowerCase(Locale.ROOT);
        List<UUID> projectIds = store.pendingProjects(workspace.getId(), email);
        List<UUID> issueIds = store.pendingIssues(workspace.getId(), email);
        if (projectIds.isEmpty() && issueIds.isEmpty()) {
            return;
        }
        for (Project project : projectRepository.findAllById(projectIds)) {
            join(project, user);
        }
        for (Issue issue : issueRepository.findAllById(issueIds)) {
            join(issue.getProject(), user);
            store.assign(issue.getId(), user.getId());
        }
        store.deletePending(workspace.getId(), email);
    }

    private void join(Project project, User user) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), user.getId())) {
            projectMemberRepository.saveAndFlush(new ProjectMember(project, user));
        }
    }
}
