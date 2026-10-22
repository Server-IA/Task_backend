package com.taskflow.api.service;

import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.ModuleRequest;
import com.taskflow.api.dto.request.UpdateModuleRequest;
import com.taskflow.api.dto.response.ModuleResponse;

import java.util.List;
import java.util.UUID;

public interface ModuleService {

    List<ModuleResponse> getModules(String slug, UUID projectId);

    ModuleResponse create(String slug, UUID projectId, ModuleRequest request);

    ModuleResponse get(String slug, UUID projectId, UUID moduleId);

    ModuleResponse update(String slug, UUID projectId, UUID moduleId, UpdateModuleRequest request);

    void delete(String slug, UUID projectId, UUID moduleId);

    /** Un issue puede estar en varios módulos a la vez. */
    ModuleResponse addIssues(String slug, UUID projectId, UUID moduleId, UUID userId, IssueIdsRequest request);

    ModuleResponse removeIssue(String slug, UUID projectId, UUID moduleId, UUID issueId, UUID userId);
}
