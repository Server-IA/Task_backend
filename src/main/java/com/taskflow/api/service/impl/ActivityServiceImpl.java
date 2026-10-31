package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.response.ActivityFeedItemResponse;
import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.dto.response.ActivitySummaryResponse;
import com.taskflow.api.dto.response.ActivitySummaryResponse.ProjectActivity;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.StoredFile;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.IssueActivityRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.util.PageCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_SUMMARY_DAYS = 365;
    /** Una edición de la descripción dentro de este tiempo desde la anterior sigue en la misma entrada. */
    static final Duration DESCRIPTION_SESSION = Duration.ofMinutes(10);
    /** Marcar y desmarcar la misma casilla dentro de este tiempo no deja nada en el historial. */
    static final Duration TASK_UNDO = Duration.ofMinutes(10);

    private final IssueActivityRepository activityRepository;
    private final IssueRepository issueRepository;
    private final ProjectAccess projectAccess;
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

    @Override
    @Transactional
    public void recordDescriptionEdit(Issue issue, User actor) {
        Instant now = Instant.now();
        Optional<IssueActivity> last = activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId());
        if (last.isPresent() && continuesDescriptionSession(last.get(), actor, now)) {
            // Misma sesión: la entrada se queda donde está y solo cambia su hora de actualización
            last.get().setUpdatedAt(now);
            activityRepository.save(last.get());
            return;
        }
        record(issue, actor, ActivityField.DESCRIPTION, null, null);
    }

    @Override
    @Transactional
    public void recordAttachment(Issue issue, User actor, StoredFile file, boolean added) {
        IssueActivity activity = new IssueActivity();
        activity.setIssue(issue);
        activity.setActor(actor);
        activity.setField(ActivityField.ATTACHMENT);
        activity.setOldValue(added ? null : file.getFileName());
        activity.setNewValue(added ? file.getFileName() : null);
        activity.setFile(added ? file : null);
        activityRepository.save(activity);
    }

    @Override
    @Transactional
    public void recordDescriptionTask(Issue issue, User actor, String text, boolean checked) {
        ActivityField field = checked ? ActivityField.DESCRIPTION_CHECKED : ActivityField.DESCRIPTION_UNCHECKED;
        ActivityField opposite = checked ? ActivityField.DESCRIPTION_UNCHECKED : ActivityField.DESCRIPTION_CHECKED;
        Optional<IssueActivity> last = activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId());
        if (last.isPresent() && last.get().getField() == opposite && Objects.equals(last.get().getNewValue(), text)
                && sameActor(last.get(), actor)
                && last.get().getCreatedAt() != null
                && last.get().getCreatedAt().isAfter(Instant.now().minus(TASK_UNDO))) {
            activityRepository.delete(last.get());
            return;
        }
        record(issue, actor, field, null, text);
    }

    private static boolean sameActor(IssueActivity entry, User actor) {
        return actor != null && entry.getActor() != null && entry.getActor().getId().equals(actor.getId());
    }

    /**
     * Si {@code last} (la última entrada del historial de la tarea) es una edición de la descripción de
     * {@code actor} actualizada hace menos de {@link #DESCRIPTION_SESSION}.
     */
    static boolean continuesDescriptionSession(IssueActivity last, User actor, Instant now) {
        return last.getField() == ActivityField.DESCRIPTION
                && sameActor(last, actor)
                && last.getUpdatedAt() != null
                && last.getUpdatedAt().isAfter(now.minus(DESCRIPTION_SESSION));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ActivityFeedItemResponse> getWorkspaceFeed(String slug, UUID userId, ActivityFeedType type,
                                                                   UUID projectId, UUID actorId, int page, int size,
                                                                   String before) {
        PageCursor cursor = PageCursor.parse(before);
        // Igual que el resto de listados paginados: página negativa = 0 y tamaño acotado.
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), pageSize);
        List<UUID> projectIds = projectAccess.projectIds(lookup.workspace(slug), userId);
        if (projectId != null) {
            // Un proyecto sin acceso (o de otro workspace) simplemente no tiene actividad visible.
            projectIds = projectIds.contains(projectId) ? List.of(projectId) : List.of();
        }
        if (cursor != null) {
            List<IssueActivity> rows = projectIds.isEmpty()
                    ? List.of()
                    : activityRepository.findFeedBefore(projectIds, type.fields(), actorId, cursor.createdAt(),
                            cursor.id(), Limit.of(pageSize + 1));
            return PageResponse.fromCursor(rows, pageSize, mapper::toFeedItem, ActivityServiceImpl::cursorOf);
        }
        Page<IssueActivity> activities = projectIds.isEmpty()
                ? new PageImpl<>(List.of(), pageable, 0)
                : activityRepository.findFeed(projectIds, type.fields(), actorId, pageable);
        return PageResponse.from(activities, mapper::toFeedItem, ActivityServiceImpl::cursorOf);
    }

    private static String cursorOf(IssueActivity activity) {
        return PageCursor.format(activity.getCreatedAt(), activity.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public ActivitySummaryResponse getWorkspaceSummary(String slug, UUID userId, int days) {
        if (days < 1 || days > MAX_SUMMARY_DAYS) {
            throw new BadRequestException("El parámetro days debe estar entre 1 y " + MAX_SUMMARY_DAYS);
        }
        Workspace workspace = lookup.workspace(slug);
        List<UUID> projectIds = projectAccess.projectIds(workspace, userId);
        if (projectIds.isEmpty()) {
            return new ActivitySummaryResponse(0, 0, 0, 0, List.of());
        }
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<ProjectActivity> byProject = activityRepository.countByProjectSince(projectIds, since).stream()
                .map(row -> new ProjectActivity(row.projectId(), row.name(), row.identifier(), row.color(),
                        row.count()))
                .toList();
        return new ActivitySummaryResponse(
                byProject.stream().mapToLong(ProjectActivity::count).sum(),
                activityRepository.countByFieldSince(projectIds, ActivityField.COMMENT, since),
                issueRepository.countCompletedSince(projectIds, since),
                issueRepository.countCreatedSince(projectIds, since),
                byProject);
    }
}
