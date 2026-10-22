package com.taskflow.api.service;

import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;

import java.util.List;
import java.util.UUID;

public interface ActivityService {

    List<ActivityResponse> getActivity(String slug, UUID projectId, UUID issueId);

    /** Registra un cambio en el historial del issue. Lo usan los demás servicios dentro de su transacción. */
    void record(Issue issue, User actor, ActivityField field, String oldValue, String newValue);
}
