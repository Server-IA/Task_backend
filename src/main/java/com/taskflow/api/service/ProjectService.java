package com.taskflow.api.service;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.UpdateProjectRequest;
import com.taskflow.api.dto.response.ProjectMemberResponse;
import com.taskflow.api.dto.response.ProjectResponse;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

    /** El project manager ve todos los proyectos; los demás, solo aquellos de los que son miembros. */
    List<ProjectResponse> getProjects(String slug, UUID userId);

    /** Crea el proyecto con sus estados por defecto y agrega al creador como miembro. */
    ProjectResponse create(String slug, UUID userId, CreateProjectRequest request);

    ProjectResponse get(String slug, UUID projectId);

    ProjectResponse update(String slug, UUID projectId, UpdateProjectRequest request);

    void delete(String slug, UUID projectId);

    List<ProjectMemberResponse> getMembers(String slug, UUID projectId);

    ProjectMemberResponse addMember(String slug, UUID projectId, AddProjectMemberRequest request);

    void removeMember(String slug, UUID projectId, UUID userId);
}
