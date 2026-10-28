package com.taskflow.api.service;

import com.taskflow.api.dto.request.IssueTemplateRequest;
import com.taskflow.api.dto.request.UpdateIssueTemplateRequest;
import com.taskflow.api.dto.response.IssueTemplateResponse;

import java.util.List;
import java.util.UUID;

public interface IssueTemplateService {

    /** Plantillas del proyecto en su orden. */
    List<IssueTemplateResponse> getTemplates(String slug, UUID projectId);

    /** El nombre no se repite en el proyecto (409) y las etiquetas deben ser del proyecto (400). */
    IssueTemplateResponse create(String slug, UUID projectId, IssueTemplateRequest request);

    IssueTemplateResponse update(String slug, UUID projectId, UUID templateId, UpdateIssueTemplateRequest request);

    void delete(String slug, UUID projectId, UUID templateId);
}
