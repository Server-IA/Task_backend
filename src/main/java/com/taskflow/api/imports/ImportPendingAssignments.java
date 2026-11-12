package com.taskflow.api.imports;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Las tareas de las personas a las que se invitó desde una importación: en el origen eran suyas, pero en TaskFlow una
 * tarea solo se le asigna a quien ya es miembro. Quedan apuntadas por correo y, cuando la persona acepta la
 * invitación, se le asignan y entra a los proyectos de esas tareas.
 */
@Component
@RequiredArgsConstructor
public class ImportPendingAssignments {

    private final ImportStore store;
    private final IssueRepository issueRepository;
    private final ProjectMemberRepository projectMemberRepository;

    /** Corre dentro de la transacción en la que la persona acepta la invitación. */
    public void apply(Workspace workspace, User user) {
        String email = user.getEmail().toLowerCase(Locale.ROOT);
        List<UUID> issueIds = store.pendingIssues(workspace.getId(), email);
        if (issueIds.isEmpty()) {
            return;
        }
        for (Issue issue : issueRepository.findAllById(issueIds)) {
            Project project = issue.getProject();
            if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), user.getId())) {
                projectMemberRepository.saveAndFlush(new ProjectMember(project, user));
            }
            store.assign(issue.getId(), user.getId());
        }
        store.deletePending(workspace.getId(), email);
    }
}
