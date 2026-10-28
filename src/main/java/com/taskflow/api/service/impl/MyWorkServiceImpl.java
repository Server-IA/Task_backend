package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.MyWorkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MyWorkServiceImpl implements MyWorkService {

    /** Fecha límite más próxima primero (sin fecha al final), luego prioridad (URGENT primero) y número. */
    static final Comparator<Issue> MY_WORK_ORDER = Comparator
            .comparing(Issue::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Issue::getPriority)
            .thenComparingInt(Issue::getSequenceId);

    private static final Set<StateGroup> CLOSED = StateGroup.CLOSED;

    private final IssueRepository issueRepository;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public MyWorkResponse getMyWork(String slug, UUID userId, MyWorkView view) {
        Workspace workspace = lookup.workspace(slug);
        List<UUID> projectIds = projectAccess.projectIds(workspace, userId);
        if (projectIds.isEmpty()) {
            return new MyWorkResponse(List.of(), 0, 0, 0, 0, 0);
        }

        List<Issue> issues = switch (view) {
            case ASSIGNED -> issueRepository.findAssignedTo(projectIds, userId, CLOSED);
            case CREATED -> issueRepository.findCreatedBy(projectIds, userId, CLOSED);
            case COMMENTED -> issueRepository.findCommentedBy(projectIds, userId, CLOSED);
        };
        ZoneId zone = ZoneId.of(properties.timezone());
        Instant weekStart = ProjectStatsCalculator.startOfWeek(LocalDate.now(zone)).atStartOfDay(zone).toInstant();

        return new MyWorkResponse(
                issues.stream().sorted(MY_WORK_ORDER).map(issueMapper::toResponse).toList(),
                issueRepository.countAssignedTo(projectIds, userId, CLOSED),
                issueRepository.countCreatedBy(projectIds, userId, CLOSED),
                issueRepository.countCommentedBy(projectIds, userId, CLOSED),
                issueRepository.countAssignedCompletedSince(projectIds, userId, weekStart),
                issueRepository.countAssignedInGroup(projectIds, userId, StateGroup.STARTED));
    }
}
