package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SearchServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private SearchServiceImpl searchService;

    private final UUID userId = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
    }

    private void accessible(List<UUID> projectIds) {
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(projectAccess.projectIds(workspace, userId)).thenReturn(projectIds);
    }

    private static Issue issue() {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        return issue;
    }

    @Test
    void sinTextoDevuelveUnaListaVacia() {
        assertThat(searchService.searchIssues(SLUG, userId, null, 8)).isEmpty();
        assertThat(searchService.searchIssues(SLUG, userId, "   ", 8)).isEmpty();
        verifyNoInteractions(issueRepository, projectAccess, lookup);
    }

    @Test
    void elCodigoExactoVaPrimeroSinRepetirse() {
        accessible(List.of(UUID.randomUUID()));
        Issue exact = issue();
        Issue recent = issue();
        when(issueRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(exact));
        when(issueRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(recent, exact)));

        searchService.searchIssues(SLUG, userId, "web-12", 8);

        ArgumentCaptor<Issue> mapped = ArgumentCaptor.forClass(Issue.class);
        verify(issueMapper, times(2)).toResponse(mapped.capture());
        assertThat(mapped.getAllValues()).containsExactly(exact, recent);
    }

    @Test
    void unTextoQueNoEsCodigoSoloBuscaPorTituloYOrdenaPorActualizacion() {
        accessible(List.of(UUID.randomUUID()));
        when(issueRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        searchService.searchIssues(SLUG, userId, " pago ", 50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(issueRepository).findAll(any(Specification.class), pageable.capture());
        verify(issueRepository, never()).findAll(any(Specification.class), any(Sort.class));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(SearchServiceImpl.MAX_LIMIT);
        assertThat(pageable.getValue().getSort().getOrderFor("updatedAt").isDescending()).isTrue();
    }

    @Test
    void sinProyectosAccesiblesNoBusca() {
        accessible(List.of());

        assertThat(searchService.searchIssues(SLUG, userId, "pago", 8)).isEmpty();
        verifyNoInteractions(issueRepository);
    }

    @Test
    void reconoceCodigosCompletos() {
        assertThat(IssueSpecifications.isFullCode("WEB-12")).isTrue();
        assertThat(IssueSpecifications.isFullCode(" web-3 ")).isTrue();
        assertThat(IssueSpecifications.isFullCode("12")).isFalse();
        assertThat(IssueSpecifications.isFullCode("pago")).isFalse();
        assertThat(IssueSpecifications.isFullCode("WEB-")).isFalse();
    }

    @Test
    void porCodigoSinCodigosValidosNoConsulta() {
        assertThat(searchService.findByCodes(SLUG, userId, null)).isEmpty();
        assertThat(searchService.findByCodes(SLUG, userId, List.of("12", "pago", "WEB-"))).isEmpty();
        verifyNoInteractions(issueRepository, projectAccess, lookup);
    }

    @Test
    void porCodigoBuscaUnaVezEnLosProyectosAccesibles() {
        accessible(List.of(UUID.randomUUID()));
        Issue web12 = issue();
        Issue api3 = issue();
        when(issueRepository.findAll(any(Specification.class))).thenReturn(List.of(web12, api3));

        searchService.findByCodes(SLUG, userId, List.of("WEB-12", "web-12", " API-3 ", "nada"));

        verify(issueRepository, times(1)).findAll(any(Specification.class));
        ArgumentCaptor<Issue> mapped = ArgumentCaptor.forClass(Issue.class);
        verify(issueMapper, times(2)).toResponse(mapped.capture());
        assertThat(mapped.getAllValues()).containsExactly(web12, api3);
    }

    @Test
    void porCodigoSinProyectosAccesiblesNoConsultaLasTareas() {
        accessible(List.of());

        assertThat(searchService.findByCodes(SLUG, userId, List.of("WEB-12"))).isEmpty();
        verifyNoInteractions(issueRepository);
    }
}
