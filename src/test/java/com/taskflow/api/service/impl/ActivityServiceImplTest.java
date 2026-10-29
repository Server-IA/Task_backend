package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.ActivityFeedItemResponse;

import org.springframework.data.domain.Limit;

import com.taskflow.api.dto.request.ActivityFeedType;
import com.taskflow.api.dto.response.ActivitySummaryResponse;
import com.taskflow.api.dto.response.PageResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.User;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
            assertThat(fields.getValue()).containsExactlyInAnyOrder(ActivityField.STATE, ActivityField.STORY_AUTO_STATE);
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
        void conBeforePideLoSiguienteSinContar() {
            Instant at = Instant.parse("2026-10-29T14:03:07.123456Z");
            UUID afterId = UUID.randomUUID();
            List<IssueActivity> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                IssueActivity activity = new IssueActivity();
                activity.setId(UUID.randomUUID());
                activity.setCreatedAt(at.minusSeconds(i + 1));
                rows.add(activity);
            }
            when(activityRepository.findFeedBefore(eq(List.of(web, app)), any(), eq(null), eq(at), eq(afterId),
                    eq(Limit.of(3)))).thenReturn(rows);

            PageResponse<ActivityFeedItemResponse> page = activityService.getWorkspaceFeed(SLUG, userId,
                    ActivityFeedType.ALL, null, null, 4, 2, at + "," + afterId);

            assertThat(page.content()).hasSize(2);
            assertThat(page.nextCursor()).isEqualTo(rows.get(1).getCreatedAt() + "," + rows.get(1).getId());
            assertThat(page.totalPages()).isEqualTo(PageResponse.UNKNOWN);
            verify(activityRepository, never()).findFeed(any(), any(), any(), any());
        }

        @Test
        void laPaginaNormalTraeElCursorDeSuUltimaSiHayMas() {
            IssueActivity last = new IssueActivity();
            last.setId(UUID.randomUUID());
            last.setCreatedAt(Instant.parse("2026-10-29T14:03:07Z"));
            when(activityRepository.findFeed(any(), any(), any(), any()))
                    .thenAnswer(invocation -> new PageImpl<>(List.of(last), invocation.getArgument(3), 10));

            PageResponse<ActivityFeedItemResponse> page = activityService.getWorkspaceFeed(SLUG, userId,
                    ActivityFeedType.ALL, null, null, 0, 1);

            assertThat(page.nextCursor()).isEqualTo("2026-10-29T14:03:07Z," + last.getId());
            assertThat(page.totalElements()).isEqualTo(10);
        }

        @Test
        void conBeforeYUnProyectoSinAccesoNoConsulta() {
            PageResponse<?> page = activityService.getWorkspaceFeed(SLUG, userId, ActivityFeedType.ALL,
                    UUID.randomUUID(), null, 0, 30, "2026-10-29T14:03:07Z," + UUID.randomUUID());

            assertThat(page.content()).isEmpty();
            assertThat(page.nextCursor()).isNull();
            verifyNoInteractions(activityRepository);
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

    /** El editor guarda mientras se escribe: una sesión de edición de la descripción deja una sola entrada. */
    @Nested
    class DescriptionEdits {

        private final Issue issue = new Issue();
        private final User luis = user();
        private final User ana = user();

        @BeforeEach
        void setUpIssue() {
            issue.setId(UUID.randomUUID());
        }

        private static User user() {
            User user = new User();
            user.setId(UUID.randomUUID());
            return user;
        }

        private IssueActivity entry(ActivityField field, User actor, Instant updatedAt) {
            IssueActivity entry = new IssueActivity();
            entry.setIssue(issue);
            entry.setField(field);
            entry.setActor(actor);
            entry.setCreatedAt(updatedAt.minus(Duration.ofMinutes(3)));
            entry.setUpdatedAt(updatedAt);
            when(activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId()))
                    .thenReturn(Optional.of(entry));
            return entry;
        }

        private IssueActivity savedEntry() {
            ArgumentCaptor<IssueActivity> saved = ArgumentCaptor.forClass(IssueActivity.class);
            verify(activityRepository).save(saved.capture());
            return saved.getValue();
        }

        @Test
        void laPrimeraEdicionCreaLaEntrada() {
            when(activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId()))
                    .thenReturn(Optional.empty());

            activityService.recordDescriptionEdit(issue, luis);

            IssueActivity saved = savedEntry();
            assertThat(saved.getField()).isEqualTo(ActivityField.DESCRIPTION);
            assertThat(saved.getActor()).isSameAs(luis);
            assertThat(saved.getIssue()).isSameAs(issue);
            assertThat(saved.getOldValue()).isNull();
            assertThat(saved.getNewValue()).isNull();
        }

        @Test
        void losGuardadosSeguidosDeLaMismaPersonaVanEnLaMismaEntrada() {
            Instant twoMinutesAgo = Instant.now().minus(Duration.ofMinutes(2));
            IssueActivity last = entry(ActivityField.DESCRIPTION, luis, twoMinutesAgo);
            Instant createdAt = last.getCreatedAt();

            activityService.recordDescriptionEdit(issue, luis);

            // No hay una entrada nueva: la misma se guarda con la hora de actualización al día
            assertThat(savedEntry()).isSameAs(last);
            assertThat(last.getUpdatedAt()).isAfter(twoMinutesAgo);
            assertThat(last.getCreatedAt()).isEqualTo(createdAt);
        }

        @Test
        void laSesionSigueMientrasNoPasenDiezMinutosDesdeElUltimoGuardado() {
            Instant now = Instant.now();
            IssueActivity entry = new IssueActivity();
            entry.setField(ActivityField.DESCRIPTION);
            entry.setActor(luis);
            entry.setUpdatedAt(now.minus(Duration.ofMinutes(9)));
            assertThat(ActivityServiceImpl.continuesDescriptionSession(entry, luis, now)).isTrue();
            entry.setUpdatedAt(now.minus(Duration.ofMinutes(10)));
            assertThat(ActivityServiceImpl.continuesDescriptionSession(entry, luis, now)).isFalse();
        }

        @Test
        void despuesDeDiezMinutosEsOtraEntrada() {
            IssueActivity last = entry(ActivityField.DESCRIPTION, luis, Instant.now().minus(Duration.ofMinutes(11)));

            activityService.recordDescriptionEdit(issue, luis);

            IssueActivity saved = savedEntry();
            assertThat(saved).isNotSameAs(last);
            assertThat(saved.getField()).isEqualTo(ActivityField.DESCRIPTION);
        }

        @Test
        void siLaEditoOtraPersonaEsOtraEntrada() {
            IssueActivity last = entry(ActivityField.DESCRIPTION, ana, Instant.now().minus(Duration.ofMinutes(1)));

            activityService.recordDescriptionEdit(issue, luis);

            IssueActivity saved = savedEntry();
            assertThat(saved).isNotSameAs(last);
            assertThat(saved.getActor()).isSameAs(luis);
        }

        @Test
        void siHuboOtroCambioEnMedioEsOtraEntrada() {
            // Luis comentó (o cambió el estado) después de editar: la edición siguiente va en una entrada nueva
            IssueActivity last = entry(ActivityField.COMMENT, luis, Instant.now().minus(Duration.ofMinutes(1)));

            activityService.recordDescriptionEdit(issue, luis);

            IssueActivity saved = savedEntry();
            assertThat(saved).isNotSameAs(last);
            assertThat(saved.getField()).isEqualTo(ActivityField.DESCRIPTION);
        }
    }

    /** Marcar o desmarcar una casilla de la descripción deja su propia entrada («marcó «…» en la descripción»). */
    @Nested
    class DescriptionTasks {

        private final Issue issue = new Issue();
        private final User luis = new User();
        private final User ana = new User();

        @BeforeEach
        void setUpIssue() {
            issue.setId(UUID.randomUUID());
            luis.setId(UUID.randomUUID());
            ana.setId(UUID.randomUUID());
        }

        private IssueActivity last(ActivityField field, User actor, String text, Instant createdAt) {
            IssueActivity entry = new IssueActivity();
            entry.setIssue(issue);
            entry.setField(field);
            entry.setActor(actor);
            entry.setNewValue(text);
            entry.setCreatedAt(createdAt);
            entry.setUpdatedAt(createdAt);
            when(activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId()))
                    .thenReturn(Optional.of(entry));
            return entry;
        }

        @Test
        void marcarRegistraLaCasillaConSuTexto() {
            when(activityRepository.findFirstByIssueIdOrderByCreatedAtDescIdDesc(issue.getId()))
                    .thenReturn(Optional.empty());

            activityService.recordDescriptionTask(issue, luis, "Plantilla del correo", true);

            ArgumentCaptor<IssueActivity> saved = ArgumentCaptor.forClass(IssueActivity.class);
            verify(activityRepository).save(saved.capture());
            assertThat(saved.getValue().getField()).isEqualTo(ActivityField.DESCRIPTION_CHECKED);
            assertThat(saved.getValue().getNewValue()).isEqualTo("Plantilla del correo");
            assertThat(saved.getValue().getActor()).isSameAs(luis);
        }

        @Test
        void desmarcarEsOtraEntrada() {
            last(ActivityField.DESCRIPTION, luis, null, Instant.now());

            activityService.recordDescriptionTask(issue, luis, "Enviar", false);

            ArgumentCaptor<IssueActivity> saved = ArgumentCaptor.forClass(IssueActivity.class);
            verify(activityRepository).save(saved.capture());
            assertThat(saved.getValue().getField()).isEqualTo(ActivityField.DESCRIPTION_UNCHECKED);
        }

        @Test
        void marcarYDesmarcarEnseguidaSeAnulan() {
            IssueActivity checked = last(ActivityField.DESCRIPTION_CHECKED, luis, "Enviar",
                    Instant.now().minus(Duration.ofMinutes(1)));

            activityService.recordDescriptionTask(issue, luis, "Enviar", false);

            verify(activityRepository).delete(checked);
            verify(activityRepository, never()).save(any());
        }

        @Test
        void noSeAnulaSiEsOtraCasillaOtraPersonaOPasoElTiempo() {
            last(ActivityField.DESCRIPTION_CHECKED, ana, "Enviar", Instant.now());
            activityService.recordDescriptionTask(issue, luis, "Enviar", false);

            last(ActivityField.DESCRIPTION_CHECKED, luis, "Otra", Instant.now());
            activityService.recordDescriptionTask(issue, luis, "Enviar", false);

            last(ActivityField.DESCRIPTION_CHECKED, luis, "Enviar", Instant.now().minus(Duration.ofMinutes(11)));
            activityService.recordDescriptionTask(issue, luis, "Enviar", false);

            verify(activityRepository, never()).delete(any());
            verify(activityRepository, times(3)).save(any());
        }
    }
}
