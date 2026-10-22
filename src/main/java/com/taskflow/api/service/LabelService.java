package com.taskflow.api.service;

import com.taskflow.api.dto.request.LabelRequest;
import com.taskflow.api.dto.response.LabelResponse;

import java.util.List;
import java.util.UUID;

public interface LabelService {

    List<LabelResponse> getLabels(String slug, UUID projectId);

    LabelResponse create(String slug, UUID projectId, LabelRequest request);

    LabelResponse update(String slug, UUID projectId, UUID labelId, LabelRequest request);

    void delete(String slug, UUID projectId, UUID labelId);
}
