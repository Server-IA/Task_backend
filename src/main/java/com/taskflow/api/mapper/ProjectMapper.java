package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.IssueTemplateResponse;
import com.taskflow.api.dto.response.LabelResponse;
import com.taskflow.api.dto.response.ProjectMemberResponse;
import com.taskflow.api.dto.response.ProjectRefResponse;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.dto.response.SavedViewResponse;
import com.taskflow.api.dto.response.StateResponse;
import com.taskflow.api.dto.response.TemplateChecklistItemResponse;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.SavedView;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.TemplateChecklistItem;
import com.taskflow.api.repository.ProjectCounts;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = UserMapper.class)
public interface ProjectMapper {

    /** {@code counts}: sus contadores ({@code ProjectRepository.countIssues}). */
    @Mapping(target = "id", source = "project.id")
    @Mapping(target = "workspaceId", source = "project.workspace.id")
    @Mapping(target = "createdAt", source = "project.createdAt")
    @Mapping(target = "updatedAt", source = "project.updatedAt")
    ProjectResponse toResponse(Project project, ProjectCounts counts);

    /** Referencia corta (nombre, identificador y color): la de las tareas, el feed y las dependencias. */
    ProjectRefResponse toRef(Project project);

    @Mapping(target = "joinedAt", source = "createdAt")
    ProjectMemberResponse toMemberResponse(ProjectMember member);

    @Mapping(target = "group", source = "stateGroup")
    StateResponse toStateResponse(State state);

    LabelResponse toLabelResponse(Label label);

    IssueTemplateResponse toTemplateResponse(IssueTemplate template);

    TemplateChecklistItemResponse toTemplateItemResponse(TemplateChecklistItem item);

    /**
     * {@code mine}: la vista es del usuario que la pide; {@code issueCount}: tareas que cumplen sus filtros
     * ({@code null} cuando no se calcula, por ejemplo al reordenar).
     */
    SavedViewResponse toSavedViewResponse(SavedView view, boolean mine, Long issueCount);
}
