package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.response.IssueRelationResponse;
import com.taskflow.api.dto.response.IssueRelationsResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.IssueRelationService;
import com.taskflow.api.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueRelationServiceImpl implements IssueRelationService {

    private final IssueRelationRepository relationRepository;
    private final IssueRepository issueRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;

    @Override
    @Transactional(readOnly = true)
    public IssueRelationsResponse getRelations(String slug, UUID projectId, UUID issueId) {
        Issue issue = findIssue(lookup.project(slug, projectId), issueId);
        List<IssueRelationResponse> blocks = new ArrayList<>();
        List<IssueRelationResponse> blockedBy = new ArrayList<>();
        List<IssueRelationResponse> related = new ArrayList<>();
        relationRepository.findAllByIssueId(issue.getId()).stream()
                .sorted(Comparator.comparingInt(relation -> relation.otherThan(issue.getId()).getSequenceId()))
                .forEach(relation -> {
                    IssueRelationResponse response = toResponse(relation, issue);
                    if (relation.getType() == IssueRelationType.RELATES) {
                        related.add(response);
                    } else if (relation.getSource().getId().equals(issue.getId())) {
                        blocks.add(response);
                    } else {
                        blockedBy.add(response);
                    }
                });
        return new IssueRelationsResponse(blocks, blockedBy, related);
    }

    @Override
    @Transactional
    public IssueRelationResponse create(String slug, UUID projectId, UUID issueId, UUID userId,
                                        CreateIssueRelationRequest request) {
        Project project = lookup.project(slug, projectId);
        Issue issue = findIssue(project, issueId);
        Issue other = issueRepository.findByIdAndProjectId(request.issueId(), project.getId())
                .orElseThrow(() -> new BadRequestException("La tarea relacionada no existe en este proyecto"));
        if (other.getId().equals(issue.getId())) {
            throw new ConflictException("Una tarea no puede relacionarse consigo misma");
        }

        IssueRelation relation = switch (request.type()) {
            case BLOCKS -> new IssueRelation(issue, other, IssueRelationType.BLOCKS);
            case BLOCKED_BY -> new IssueRelation(other, issue, IssueRelationType.BLOCKS);
            case RELATES -> new IssueRelation(issue, other, IssueRelationType.RELATES);
        };
        ensureAllowed(relation);
        relationRepository.save(relation);

        recordActivity(relation, lookup.user(userId), true);
        return toResponse(relation, issue);
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID issueId, UUID relationId, UUID userId) {
        Issue issue = findIssue(lookup.project(slug, projectId), issueId);
        IssueRelation relation = relationRepository.findByIdAndIssueId(relationId, issue.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Relación no encontrada"));
        relationRepository.delete(relation);
        recordActivity(relation, lookup.user(userId), false);
    }

    @Override
    @Transactional
    public void notifyUnblocked(Issue completedBlocker, User actor) {
        for (Issue blocked : relationRepository.findIssuesBlockedBy(completedBlocker.getId())) {
            // Una tarea ya cerrada no necesita el aviso; si le queda otro bloqueo abierto, sigue bloqueada.
            if (blocked.getState().getStateGroup().isOpen()
                    && relationRepository.countOpenBlockersExcept(
                            blocked.getId(), completedBlocker.getId(), StateGroup.CLOSED) == 0) {
                notificationService.notifyUnblocked(blocked, completedBlocker, actor);
            }
        }
    }

    /** Sin duplicados (RELATES en cualquier dirección) y sin dos tareas bloqueándose mutuamente. */
    private void ensureAllowed(IssueRelation relation) {
        Issue source = relation.getSource();
        Issue target = relation.getTarget();
        if (relation.getType() == IssueRelationType.BLOCKS) {
            if (exists(source, target, IssueRelationType.BLOCKS)) {
                throw new ConflictException(source.getCode() + " ya bloquea a " + target.getCode());
            }
            if (exists(target, source, IssueRelationType.BLOCKS)) {
                throw new ConflictException(target.getCode() + " ya bloquea a " + source.getCode()
                        + ": dos tareas no pueden bloquearse mutuamente");
            }
        } else if (exists(source, target, IssueRelationType.RELATES)
                || exists(target, source, IssueRelationType.RELATES)) {
            throw new ConflictException(source.getCode() + " ya está relacionada con " + target.getCode());
        }
    }

    private boolean exists(Issue source, Issue target, IssueRelationType type) {
        return relationRepository.existsBySourceIdAndTargetIdAndType(source.getId(), target.getId(), type);
    }

    /**
     * Registra la relación agregada ({@code newValue}) o quitada ({@code oldValue}) en el historial de ambas
     * tareas.
     */
    private void recordActivity(IssueRelation relation, User actor, boolean added) {
        Issue source = relation.getSource();
        Issue target = relation.getTarget();
        boolean blocks = relation.getType() == IssueRelationType.BLOCKS;
        String fromSource = (blocks ? "bloquea " : "relacionada con ") + target.getCode();
        String fromTarget = (blocks ? "bloqueada por " : "relacionada con ") + source.getCode();
        activityService.record(source, actor, ActivityField.RELATION,
                added ? null : fromSource, added ? fromSource : null);
        activityService.record(target, actor, ActivityField.RELATION,
                added ? null : fromTarget, added ? fromTarget : null);
    }

    private IssueRelationResponse toResponse(IssueRelation relation, Issue seenFrom) {
        return new IssueRelationResponse(relation.getId(), issueMapper.toRelated(relation.otherThan(seenFrom.getId())));
    }

    private Issue findIssue(Project project, UUID issueId) {
        return issueRepository.findByIdAndProjectId(issueId, project.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }
}
