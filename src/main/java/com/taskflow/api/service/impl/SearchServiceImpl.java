package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueSpecifications;
import com.taskflow.api.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    static final int MAX_LIMIT = 20;
    /** Máximo de códigos distintos por consulta de referencias. */
    static final int MAX_CODES = 50;

    private static final Sort RECENTLY_UPDATED = Sort.by(Sort.Direction.DESC, "updatedAt");

    private final IssueRepository issueRepository;
    private final ProjectAccess projectAccess;
    private final ResourceLookup lookup;
    private final IssueMapper issueMapper;

    @Override
    @Transactional(readOnly = true)
    public List<IssueResponse> searchIssues(String slug, UUID userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<UUID> projectIds = projectAccess.projectIds(lookup.workspace(slug), userId);
        if (projectIds.isEmpty()) {
            return List.of();
        }
        String text = query.trim();
        int max = Math.clamp(limit, 1, MAX_LIMIT);

        // Primero la coincidencia exacta de código; después el resto, sin repetir.
        Map<UUID, Issue> results = new LinkedHashMap<>();
        if (IssueSpecifications.isFullCode(text)) {
            issueRepository.findAll(IssueSpecifications.codeEquals(projectIds, text), RECENTLY_UPDATED)
                    .forEach(issue -> results.put(issue.getId(), issue));
        }
        issueRepository.findAll(IssueSpecifications.searchInProjects(projectIds, text),
                        PageRequest.of(0, max, RECENTLY_UPDATED))
                .forEach(issue -> results.putIfAbsent(issue.getId(), issue));

        return results.values().stream().limit(max).map(issueMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IssueResponse> findByCodes(String slug, UUID userId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<String> valid = codes.stream()
                .filter(IssueSpecifications::isFullCode)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .limit(MAX_CODES)
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        List<UUID> projectIds = projectAccess.projectIds(lookup.workspace(slug), userId);
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return issueRepository.findAll(IssueSpecifications.codeIn(projectIds, valid)).stream()
                .map(issueMapper::toResponse)
                .toList();
    }
}
