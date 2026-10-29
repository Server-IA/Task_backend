package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.UpdateChecklistItemRequest;
import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueChecklistItemRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.IssueChecklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueChecklistServiceImpl implements IssueChecklistService {

    /** Máximo de criterios y pasos (sumados) por tarea. */
    static final int MAX_ITEMS_PER_ISSUE = 100;

    private final IssueChecklistItemRepository itemRepository;
    private final IssueRepository issueRepository;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ChecklistItemResponse> getItems(String slug, UUID projectId, UUID issueId) {
        Issue issue = findIssue(slug, projectId, issueId);
        return itemRepository.findAllByIssueIdOrderByPositionAscCreatedAtAsc(issue.getId()).stream()
                .sorted(Comparator.comparing(IssueChecklistItem::getKind)
                        .thenComparingInt(IssueChecklistItem::getPosition))
                .map(issueMapper::toChecklistItemResponse)
                .toList();
    }

    @Override
    @Transactional
    public ChecklistItemResponse create(String slug, UUID projectId, UUID issueId, ChecklistItemRequest request) {
        Issue issue = findLockedIssue(slug, projectId, issueId);
        if (itemRepository.countByIssueId(issue.getId()) >= MAX_ITEMS_PER_ISSUE) {
            throw new ConflictException("Una tarea admite como máximo " + MAX_ITEMS_PER_ISSUE
                    + " criterios y pasos de checklist");
        }
        List<IssueChecklistItem> siblings = siblings(issue, request.kind());
        int position = siblings.isEmpty() ? 0 : siblings.getLast().getPosition() + 1;
        IssueChecklistItem item = new IssueChecklistItem(issue, request.kind(), request.text().trim(), position);
        ChecklistItemResponse response = issueMapper.toChecklistItemResponse(itemRepository.save(item));
        issueRepository.touchChecklist(issue.getId(), Instant.now());
        return response;
    }

    @Override
    @Transactional
    public ChecklistItemResponse update(String slug, UUID projectId, UUID issueId, UUID itemId, UUID userId,
                                        UpdateChecklistItemRequest request) {
        Issue issue = findLockedIssue(slug, projectId, issueId);
        IssueChecklistItem item = findItem(issue, itemId);
        Instant now = Instant.now();
        if (request.text() != null) {
            String text = request.text().trim();
            if (text.isEmpty()) {
                throw new BadRequestException("El texto no puede estar vacío");
            }
            item.setText(text);
        }
        if (request.done() != null && request.done() != item.isDone()) {
            markDone(item, request.done(), request.done() ? lookup.user(userId) : null, now);
        }
        if (request.position() != null) {
            List<IssueChecklistItem> ordered = new ArrayList<>(siblings(issue, item.getKind()));
            ordered.removeIf(other -> other.getId().equals(item.getId()));
            ordered.add(Math.min(request.position(), ordered.size()), item);
            renumber(ordered);
        }
        // El flush ejecuta @PreUpdate para que la respuesta lleve el updatedAt nuevo.
        ChecklistItemResponse response = issueMapper.toChecklistItemResponse(itemRepository.saveAndFlush(item));
        issueRepository.touchChecklist(issue.getId(), now);
        return response;
    }

    @Override
    @Transactional
    public void delete(String slug, UUID projectId, UUID issueId, UUID itemId) {
        Issue issue = findLockedIssue(slug, projectId, issueId);
        IssueChecklistItem item = findItem(issue, itemId);
        itemRepository.delete(item);
        // Los que quedan se renumeran para que la posición siga siendo su índice.
        renumber(siblings(issue, item.getKind()).stream()
                .filter(other -> !other.getId().equals(item.getId()))
                .toList());
        issueRepository.touchChecklist(issue.getId(), Instant.now());
    }

    /**
     * Marca o desmarca el elemento: al marcarlo guarda quién y cuándo; al desmarcarlo los borra.
     *
     * @param user quien lo marca, o {@code null} al desmarcarlo
     */
    static void markDone(IssueChecklistItem item, boolean done, User user, Instant now) {
        item.setDone(done);
        item.setCheckedBy(done ? user : null);
        item.setCheckedAt(done ? now : null);
    }

    @Override
    @Transactional
    public List<IssueChecklistItem> createAll(Issue issue, List<ChecklistItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Map<ChecklistItemKind, Integer> nextPosition = new EnumMap<>(ChecklistItemKind.class);
        List<IssueChecklistItem> items = requests.stream()
                .map(request -> new IssueChecklistItem(issue, request.kind(), request.text().trim(),
                        nextPosition.merge(request.kind(), 1, Integer::sum) - 1))
                .toList();
        return itemRepository.saveAll(items);
    }

    /** Elementos del mismo tipo de la tarea, en orden. */
    private List<IssueChecklistItem> siblings(Issue issue, ChecklistItemKind kind) {
        return itemRepository.findAllByIssueIdAndKindOrderByPositionAscCreatedAtAsc(issue.getId(), kind);
    }

    /** Deja las posiciones como 0, 1, 2… en el orden de la lista. */
    private static void renumber(List<IssueChecklistItem> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
    }

    /**
     * La tarea, con su fila bloqueada antes de leer la checklist: los cambios de la checklist de una misma tarea van en
     * fila, así el máximo de {@value #MAX_ITEMS_PER_ISSUE} se respeta con peticiones simultáneas y las posiciones no se
     * repiten ni quedan huecos.
     */
    private Issue findLockedIssue(String slug, UUID projectId, UUID issueId) {
        issueRepository.lockById(issueId);
        return findIssue(slug, projectId, issueId);
    }

    private Issue findIssue(String slug, UUID projectId, UUID issueId) {
        return issueRepository.findByIdAndProjectId(issueId, lookup.project(slug, projectId).getId())
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }

    private IssueChecklistItem findItem(Issue issue, UUID itemId) {
        return itemRepository.findByIdAndIssueId(itemId, issue.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Elemento de la checklist no encontrado"));
    }
}
