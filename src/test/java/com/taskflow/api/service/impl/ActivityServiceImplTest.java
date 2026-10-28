package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.response.ActivitySummaryResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.ProjectColor;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.IssueActivityRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectActivityCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueActivityRepository activityRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private CollaborationMapper mapper;

    @InjectMocks
    private ActivityServiceImpl activityService;

    private final UUID userId = UUID.randomUUID();
    private final UUID web = UUID.randomUUID();
    private final UUID app = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        // lenient: los casos que validan los parámetros fallan antes de consultar los proyectos
        lenient().when(lookup.workspace(SLUG)).thenReturn(workspace);
        lenient().when(projectAccess.projectIds(workspace, userId)).thenReturn(List.of(web, app));
    }

    @Nested
    class Feed {

        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<Collection<ActivityField>> fields = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        private final ArgumentCaptor<Collection<UUID>> projects = ArgumentCaptor.forClass(Collection.class);
        private final ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        private void feed(ActivityFeedType type, UUID projectId, UUID actorId, int page, int size) {
            when(activityRepository.findFeed(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
            activityService.getWorkspaceFeed(SLUG, userId, type, projectId, actorId, page, size);
            verify(activityRepository).findFeed(projects.capture(), fields.capture(), any(), pageable.capture());
        }

        @Test
        void filtraPorTipo() {
            feed(ActivityFeedType.STATE, null, null, 0, 30);
            assertThat(fields.getValue()).containsExactly(ActivityField.STATE);
        }

        @Test
        void comentarios() {
            feed(ActivityFeedType.COMMENTS, null, null, 0, 30);
            assertThat(fields.getValue()).containsExactly(ActivityField.COMMENT);
        }

        @Test
        void asignaciones() {
            feed(ActivityFeedType.ASSIGNMENTS, null, null, 0, 30);
            assertThat(fields.getValue()).containsExactly(ActivityField.ASSIGNEES);
        }

        @Test
        void todoIncluyeCualquierCampoDeLosProyectosAccesibles() {
            feed(ActivityFeedType.ALL, null, null, 0, 30);
            assertThat(fields.getValue()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ActivityField.class));
            assertThat(projects.getValue()).containsExactly(web, app);
        }

        @Test
        void filtraPorProyectoYPersona() {
            UUID actorId = UUID.randomUUID();
            when(activityRepository.findFeed(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

            activityService.getWorkspaceFeed(SLUG, userId, ActivityFeedType.ALL, app, actorId, 0, 30);

            verify(activityRepository).findFeed(eq(List.of(app)), any(), eq(actorId), any());
        }

        @Test
        void unProyectoSinAccesoNoDevuelveNada() {
            PageResponse<?> page = activityService.getWorkspaceFeed(SLUG, userId, ActivityFeedType.ALL,
                    UUID.randomUUID(), null, 0, 30);

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isZero();
            verifyNoInteractions(activityRepository);
        }

        @Test
        void acotaLaPaginaYElTamano() {
            feed(ActivityFeedType.ALL, null, null, -3, 500);
            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(ActivityServiceImpl.MAX_PAGE_SIZE);
        }

        @Test
        void leeElTipoDelParametro() {
            assertThat(ActivityFeedType.from(null)).isEqualTo(ActivityFeedType.ALL);
            assertThat(ActivityFeedType.from("comments")).isEqualTo(ActivityFeedType.COMMENTS);
            assertThat(ActivityFeedType.from("Assignments")).isEqualTo(ActivityFeedType.ASSIGNMENTS);
            assertThatThrownBy(() -> ActivityFeedType.from("labels")).isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Resumen {

        @Test
        void sumaLaActividadDeLosProyectosAccesibles() {
            when(activityRepository.countByProjectSince(eq(List.of(web, app)), any(Instant.class))).thenReturn(List.of(
                    new ProjectActivityCount(web, "Sitio web", "WEB", ProjectColor.TEAL, 29),
                    new ProjectActivityCount(app, "App", "APP", ProjectColor.AMBER, 9)));
            when(activityRepository.countByFieldSince(eq(List.of(web, app)), eq(ActivityField.COMMENT),
                    any(Instant.class))).thenReturn(12L);
            when(issueRepository.countCompletedSince(eq(List.of(web, app)), any(Instant.class))).thenReturn(9L);
            when(issueRepository.countCreatedSince(eq(List.of(web, app)), any(Instant.class))).thenReturn(5L);

            ActivitySummaryResponse summary = activityService.getWorkspaceSummary(SLUG, userId, 7);

            assertThat(summary.changes()).isEqualTo(38);
            assertThat(summary.comments()).isEqualTo(12);
            assertThat(summary.completed()).isEqualTo(9);
            assertThat(summary.created()).isEqualTo(5);
            assertThat(summary.byProject()).extracting(ActivitySummaryResponse.ProjectActivity::identifier)
                    .containsExactly("WEB", "APP");
        }

        @Test
        void rechazaUnPeriodoInvalido() {
            assertThatThrownBy(() -> activityService.getWorkspaceSummary("otro", userId, 0))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> activityService.getWorkspaceSummary("otro", userId, 400))
                    .isInstanceOf(BadRequestException.class);
            verifyNoInteractions(activityRepository, issueRepository);
        }
    }
}
