package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.StoryChildResponse;
import com.taskflow.api.dto.response.StoryResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.StoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoryServiceImpl implements StoryService {

    /** Primero las tareas y después los bugs; dentro de cada grupo, por número. */
    static final Comparator<Issue> CHILD_ORDER = Comparator
            .comparingInt((Issue child) -> child.getType() == IssueType.BUG ? 1 : 0)
            .thenComparingInt(Issue::getSequenceId);

    private final IssueRepository issueRepository;
    private final IssueRelationRepository relationRepository;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getStories(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        List<Issue> stories = issueRepository.findStories(project.getId());
        if (stories.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = stories.stream().map(Issue::getId).toList();

        Map<UUID, List<Issue>> childrenByStory = new HashMap<>();
        for (Issue child : issueRepository.findChildrenOf(ids)) {
            childrenByStory.computeIfAbsent(child.getParent().getId(), id -> new ArrayList<>()).add(child);
        }
        Map<UUID, List<IssueSummaryResponse>> relatedByStory = new HashMap<>();
        for (IssueRelation relation : relationRepository.findRelatesOf(ids)) {
            addRelated(relatedByStory, relation.getSource(), relation.getTarget(), project);
            addRelated(relatedByStory, relation.getTarget(), relation.getSource(), project);
        }

        return stories.stream()
                .map(story -> {
                    List<Issue> children = childrenByStory.getOrDefault(story.getId(), List.of()).stream()
                            .sorted(CHILD_ORDER)
                            .toList();
                    return new StoryResponse(
                            issueMapper.toResponse(story),
                            people(children),
                            children.stream()
                                    .map(child -> issueMapper.toStoryChild(child, afterClose(story, child)))
                                    .toList(),
                            relatedByStory.getOrDefault(story.getId(), List.of()).stream()
                                    .sorted(Comparator.comparing(IssueSummaryResponse::code))
                                    .toList());
                })
                .toList();
    }

    /** Un bug de una historia cerrada creado después de cerrarla («después de cerrar»). */
    static boolean afterClose(Issue story, Issue child) {
        Instant closedAt = story.getClosedAt();
        return closedAt != null && child.getType() == IssueType.BUG && child.getCreatedAt() != null
                && child.getCreatedAt().isAfter(closedAt);
    }

    /** Las personas de sus tareas y bugs (los asignados), sin repetir y por nombre. */
    private List<UserSummaryResponse> people(List<Issue> children) {
        Map<UUID, User> byId = new LinkedHashMap<>();
        children.forEach(child -> child.getAssignees().forEach(user -> byId.putIfAbsent(user.getId(), user)));
        return byId.values().stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(userMapper::toSummary)
                .toList();
    }

    /** Solo las relacionadas del mismo proyecto (las de otros proyectos se ven en el panel de la tarea). */
    private void addRelated(Map<UUID, List<IssueSummaryResponse>> relatedByStory, Issue story, Issue other,
                            Project project) {
        if (story.isStory() && other.getProject().getId().equals(project.getId())) {
            relatedByStory.computeIfAbsent(story.getId(), id -> new ArrayList<>()).add(issueMapper.toSummary(other));
        }
    }
}
