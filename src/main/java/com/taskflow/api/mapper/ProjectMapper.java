package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.IssueTemplateResponse;
import com.taskflow.api.dto.response.LabelResponse;
import com.taskflow.api.dto.response.ProjectMemberResponse;
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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = UserMapper.class)
public interface ProjectMapper {

    @Mapping(target = "workspaceId", source = "workspace.id")
    ProjectResponse toResponse(Project project);

    @Mapping(target = "joinedAt", source = "createdAt")
    ProjectMemberResponse toMemberResponse(ProjectMember member);

    @Mapping(target = "group", source = "stateGroup")
    StateResponse toStateResponse(State state);

    LabelResponse toLabelResponse(Label label);

    IssueTemplateResponse toTemplateResponse(IssueTemplate template);

    TemplateChecklistItemResponse toTemplateItemResponse(TemplateChecklistItem item);

    /** {@code mine}: la vista es del usuario que la pide; {@code issueCount}: tareas que cumplen sus filtros. */
    SavedViewResponse toSavedViewResponse(SavedView view, boolean mine, long issueCount);
}
