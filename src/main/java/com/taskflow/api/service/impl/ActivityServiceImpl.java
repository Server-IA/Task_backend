package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.IssueActivityRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final IssueActivityRepository activityRepository;
    private final IssueRepository issueRepository;
    private final ResourceLookup lookup;
    private final CollaborationMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<ActivityResponse> getActivity(String slug, UUID projectId, UUID issueId) {
        Project project = lookup.project(slug, projectId);
        Issue issue = issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
        return activityRepository.findAllByIssueIdWithActor(issue.getId()).stream()
                .map(mapper::toActivityResponse)
                .toList();
    }

    @Override
    @Transactional
    public void record(Issue issue, User actor, ActivityField field, String oldValue, String newValue) {
        IssueActivity activity = new IssueActivity();
        activity.setIssue(issue);
        activity.setActor(actor);
        activity.setField(field);
        activity.setOldValue(oldValue);
        activity.setNewValue(newValue);
        activityRepository.save(activity);
    }
}
