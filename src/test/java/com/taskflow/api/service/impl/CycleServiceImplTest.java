package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.CloseCycleRequest;
import com.taskflow.api.dto.request.CloseCycleRequest.IssueMove;
import com.taskflow.api.dto.request.CycleMoveTarget;
import com.taskflow.api.dto.request.IssueIdsRequest;
import com.taskflow.api.dto.request.UpdateCycleRequest;
import com.taskflow.api.dto.response.CloseCyclePreviewResponse;
import com.taskflow.api.dto.response.CloseCycleResponse;
import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleServiceImplTest {

    private static final String SLUG = "mi-equipo";
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    @Mock
    private CycleRepository cycleRepository;
    @Mock
    private ActivityService activityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PlanningSupport support;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private PlanningMapper mapper;
    @Mock
    private IssueMapper issueMapper;

    private CycleServiceImpl cycleService;

    private final LocalDate today = LocalDate.now(ZONE);
    private final Instant now = Instant.now();
    private Project project;
    private User pm;
    private State todo;
    private State inProgress;
    private State done;
    private State cancelled;
    private int nextSequence = 1;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota", null);
        cycleService = new CycleServiceImpl(cycleRepository, activityService, notificationService, support, lookup,
                mapper, issueMapper, properties);
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        pm = new User();
        pm.setId(UUID.randomUUID());
        todo = state("Todo", StateGroup.UNSTARTED);
        inProgress = state("En curso", StateGroup.STARTED);
        done = state("Hecho", StateGroup.COMPLETED);
        cancelled = state("Cancelada", StateGroup.CANCELLED);

        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(pm.getId())).thenReturn(pm);
        lenient().when(cycleRepository.findProgress(anyCollection(), eq(StateGroup.COMPLETED))).thenReturn(List.of());
        lenient().when(cycleRepository.saveAndFlush(any(Cycle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(mapper.toCycleResponse(any(Cycle.class), any(CycleStatus.class), any(ProgressResponse.class)))
                .thenAnswer(invocation -> {
                    Cycle cycle = invocation.getArgument(0);
                    return new CycleResponse(cycle.getId(), cycle.getName(), cycle.getDescription(),
                            cycle.getStartDate(), cycle.getEndDate(), invocation.getArgument(1), cycle.getClosedAt(),
                            null, invocation.getArgument(2), null, null);
                });
        lenient().when(issueMapper.toRelated(any(Issue.class))).thenAnswer(invocation -> {
            Issue issue = invocation.getArgument(0);
            return new RelatedIssueResponse(issue.getId(), issue.getCode(), issue.getTitle(), null, List.of(), null,
                    issue.getDueDate(), 0, 0, 0, 0, 0);
        });
    }

    private State state(String name, StateGroup group) {
        State state = new State(project, name, "#000000", group, 0);
        state.setId(UUID.randomUUID());
        return state;
    }

    private Issue issue(State state) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setSequenceId(nextSequence++);
        issue.setTitle("Tarea " + issue.getSequenceId());
        issue.setState(state);
        issue.setCreatedAt(now.minus(Duration.ofDays(10)));
        return issue;
    }

    /** Completada; el tiempo de ciclo se mide desde que empezó (pasó a un estado STARTED). */
    private Issue completedIssue(Duration startedAgo, Duration completedAgo) {
        Issue issue = issue(done);
        issue.setStartedAt(now.minus(startedAgo));
        issue.setCompletedAt(now.minus(completedAgo));
        return issue;
    }

    private Cycle cycle(String name, LocalDate start, LocalDate end, Issue... issues) {
        Cycle cycle = new Cycle();
        cycle.setId(UUID.randomUUID());
        cycle.setProject(project);
        cycle.setName(name);
        cycle.setStartDate(start);
        cycle.setEndDate(end);
        cycle.setCreatedAt(now);
        cycle.getIssues().addAll(Arrays.asList(issues));
        lenient().when(cycleRepository.findByIdAndProjectId(cycle.getId(), project.getId()))
                .thenReturn(Optional.of(cycle));
        lenient().when(cycleRepository.findForUpdate(cycle.getId(), project.getId())).thenReturn(Optional.of(cycle));
        return cycle;
    }

    private Issue story(State state) {
        Issue story = issue(state);
        story.setType(IssueType.STORY);
        return story;
    }

    private Cycle closed(Cycle cycle) {
        cycle.setClosedAt(now.minus(Duration.ofDays(1)));
        return cycle;
    }

    private void projectCycles(Cycle... cycles) {
        lenient().when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(Arrays.asList(cycles));
    }

    private static CloseCycleRequest closeRequest(CycleMoveTarget moveTo, UUID targetCycleId, boolean startRetro,
                                                  IssueMove... overrides) {
        return new CloseCycleRequest(moveTo, targetCycleId, List.of(overrides), startRetro);
    }

    @Nested
    class ClosePreview {

        @Test
        void separaCompletadasYSinTerminarConSuCycleTimeYElSiguienteCiclo() {
            Issue slow = completedIssue(Duration.ofDays(4), Duration.ofDays(1));
            Issue fast = completedIssue(Duration.ofDays(2), Duration.ofDays(1));
            // Nunca pasó por un estado STARTED: no cuenta para el tiempo de ciclo.
            Issue skipped = issue(done);
            skipped.setCompletedAt(now.minus(Duration.ofDays(1)));
            Issue started = issue(inProgress);
            Issue pending = issue(todo);
            Issue dropped = issue(cancelled);
            Cycle sprint = cycle("Sprint 3", today.minusDays(7), today.plusDays(7),
                    pending, dropped, started, fast, slow, skipped);
            Cycle next = cycle("Sprint 4", today.plusDays(8), today.plusDays(21));
            Cycle later = cycle("Sprint 5", today.plusDays(22), today.plusDays(35));
            Cycle closedOne = closed(cycle("Sprint 2", today.plusDays(1), today.plusDays(2)));
            Cycle draft = cycle("Ideas", null, null);
            projectCycles(later, next, closedOne, draft, sprint);

            CloseCyclePreviewResponse preview = cycleService.closePreview(SLUG, project.getId(), sprint.getId());

            assertThat(preview.cycle().id()).isEqualTo(sprint.getId());
            assertThat(preview.completed()).extracting(RelatedIssueResponse::id)
                    .containsExactly(slow.getId(), fast.getId(), skipped.getId());
            // Las canceladas no son «completadas» ni «sin terminar»: se quedan en el ciclo.
            assertThat(preview.unfinished()).extracting(RelatedIssueResponse::id)
                    .containsExactly(started.getId(), pending.getId());
            assertThat(preview.completedCount()).isEqualTo(3);
            assertThat(preview.unfinishedCount()).isEqualTo(2);
            // Mediana de 3 y 1 días de En curso a Hecho (la que no empezó no cuenta)
            assertThat(preview.cycleTimeDays()).isEqualTo(2.0);
            assertThat(preview.nextCycle().id()).isEqualTo(next.getId());
        }

        @Test
        void sinCompletadasNiCicloSiguiente() {
            Cycle sprint = cycle("Sprint 3", today.minusDays(7), today.plusDays(7), issue(todo));
            projectCycles(sprint);

            CloseCyclePreviewResponse preview = cycleService.closePreview(SLUG, project.getId(), sprint.getId());

            assertThat(preview.completed()).isEmpty();
            assertThat(preview.cycleTimeDays()).isNull();
            assertThat(preview.nextCycle()).isNull();
        }

        @Test
        void paraUnCicloSinFechasElSiguienteEsElPrimeroQueNoHaTerminado() {
            Cycle draft = cycle("Ideas", null, null);
            Cycle ended = cycle("Sprint 1", today.minusDays(20), today.minusDays(6));
            Cycle current = cycle("Sprint 2", today.minusDays(5), today.plusDays(9));
            Cycle upcoming = cycle("Sprint 3", today.plusDays(10), today.plusDays(24));

            assertThat(CycleServiceImpl.nextCycle(draft, List.of(upcoming, current, ended, draft), today))
                    .isSameAs(current);
        }
    }

    @Nested
    class Close {

        private Issue finished;
        private Issue dropped;
        private Issue started;
        private Issue pending;
        private Cycle sprint;
        private Cycle next;

        @BeforeEach
        void cycles() {
            finished = completedIssue(Duration.ofDays(3), Duration.ofDays(1));
            dropped = issue(cancelled);
            started = issue(inProgress);
            pending = issue(todo);
            sprint = cycle("Sprint 3", today.minusDays(7), today.plusDays(7), finished, dropped, started, pending);
            next = cycle("Sprint 4", today.plusDays(8), today.plusDays(21));
        }

        @Test
        void porDefectoPasaLasSinTerminarAlSiguienteCicloYLoCierra() {
            projectCycles(next, sprint);

            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false));

            assertThat(sprint.getIssues()).containsExactlyInAnyOrder(finished, dropped);
            assertThat(next.getIssues()).containsExactlyInAnyOrder(started, pending);
            verify(activityService).record(started, pm, ActivityField.CYCLE, "Sprint 3", "Sprint 4");
            verify(activityService).record(pending, pm, ActivityField.CYCLE, "Sprint 3", "Sprint 4");
            assertThat(sprint.getClosedAt()).isNotNull();
            assertThat(sprint.getClosedBy()).isSameAs(pm);
            assertThat(sprint.getCarriedToCycle()).isEqualTo(2);
            assertThat(sprint.getCarriedToBacklog()).isZero();
            // Terminaba después de hoy: su fin pasa a hoy.
            assertThat(sprint.getEndDate()).isEqualTo(today);
            assertThat(sprint.getRetroStartedAt()).isNull();
            // El estado de las tareas no cambia.
            assertThat(started.getState()).isSameAs(inProgress);

            assertThat(response.movedToCycle()).isEqualTo(2);
            assertThat(response.movedToBacklog()).isZero();
            assertThat(response.retroStarted()).isFalse();
            verifyNoInteractions(notificationService);
            assertThat(response.cycle().status()).isEqualTo(CycleStatus.COMPLETED);
            assertThat(response.cycle().closedAt()).isEqualTo(sprint.getClosedAt());
        }

        @Test
        void lasExcepcionesMandanTareasAlBacklogSinCambiarSuEstado() {
            projectCycles(next, sprint);

            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false,
                            new IssueMove(pending.getId(), CycleMoveTarget.BACKLOG),
                            // Una excepción sobre una tarea ya terminada no la mueve.
                            new IssueMove(finished.getId(), CycleMoveTarget.BACKLOG)));

            assertThat(sprint.getIssues()).containsExactlyInAnyOrder(finished, dropped);
            assertThat(next.getIssues()).containsExactly(started);
            verify(activityService).record(started, pm, ActivityField.CYCLE, "Sprint 3", "Sprint 4");
            verify(activityService).record(pending, pm, ActivityField.CYCLE, "Sprint 3", null);
            assertThat(pending.getState()).isSameAs(todo);
            assertThat(sprint.getCarriedToCycle()).isEqualTo(1);
            assertThat(sprint.getCarriedToBacklog()).isEqualTo(1);
            assertThat(response.movedToCycle()).isEqualTo(1);
            assertThat(response.movedToBacklog()).isEqualTo(1);
        }

        @Test
        void alBacklogNoHaceFaltaCicloSiguienteYPuedeAbrirLaRetro() {
            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, null, true));

            assertThat(sprint.getIssues()).containsExactlyInAnyOrder(finished, dropped);
            verify(cycleRepository, never()).findAllByProjectId(any());
            assertThat(sprint.getCarriedToBacklog()).isEqualTo(2);
            assertThat(sprint.getRetroStartedAt()).isNotNull();
            assertThat(response.retroStarted()).isTrue();
            // Abrir la retro avisa al equipo
            verify(notificationService).notifyRetroStarted(sprint, pm);
        }

        @Test
        void cierraConLaFilaBloqueada() {
            cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, null, false));

            // Dos cierres a la vez (o agregar una tarea mientras se cierra) van en fila
            verify(cycleRepository).findForUpdate(sprint.getId(), project.getId());
        }

        @Test
        void guardaLaFotoDelCierreParaLaRetrospectiva() {
            projectCycles(next, sprint);

            cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false));

            // 1 completada; total = completadas + las 2 que pasaron al siguiente (la cancelada no cuenta)
            assertThat(sprint.getClosedCompletedCount()).isEqualTo(1);
            assertThat(sprint.getClosedTotalCount()).isEqualTo(3);
            assertThat(sprint.getClosedCycleTimeDays()).isEqualTo(2.0);
        }

        @Test
        void lasHistoriasSeMuevenConSusTareasPeroNoCuentan() {
            Issue openStory = story(inProgress);
            Issue doneStory = story(done);
            doneStory.setStartedAt(now.minus(Duration.ofDays(30)));
            doneStory.setCompletedAt(now);
            sprint.getIssues().addAll(List.of(openStory, doneStory));
            projectCycles(next, sprint);

            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false));

            assertThat(next.getIssues()).contains(openStory);
            assertThat(response.movedToCycle()).isEqualTo(2);
            assertThat(sprint.getCarriedToCycle()).isEqualTo(2);
            assertThat(sprint.getClosedCompletedCount()).isEqualTo(1);
            assertThat(sprint.getClosedCycleTimeDays()).isEqualTo(2.0);
        }

        @Test
        void laVistaPreviaNoCuentaLasHistorias() {
            sprint.getIssues().addAll(List.of(story(inProgress), story(done)));
            projectCycles(next, sprint);

            CloseCyclePreviewResponse preview = cycleService.closePreview(SLUG, project.getId(), sprint.getId());

            assertThat(preview.completedCount()).isEqualTo(1);
            assertThat(preview.unfinishedCount()).isEqualTo(2);
        }

        @Test
        void siLaRetroYaEstabaAbiertaNoVuelveAAvisar() {
            Instant opened = now.minus(Duration.ofHours(1));
            sprint.setRetroStartedAt(opened);

            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, null, true));

            assertThat(sprint.getRetroStartedAt()).isEqualTo(opened);
            assertThat(response.retroStarted()).isTrue();
            verifyNoInteractions(notificationService);
        }

        @Test
        void conCicloDestinoIndicadoLasMueveAEse() {
            Cycle later = cycle("Sprint 6", today.plusDays(30), today.plusDays(44));

            cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, later.getId(), false));

            assertThat(later.getIssues()).containsExactlyInAnyOrder(started, pending);
            assertThat(next.getIssues()).isEmpty();
            verify(activityService).record(started, pm, ActivityField.CYCLE, "Sprint 3", "Sprint 6");
        }

        @Test
        void sinCicloSiguienteYConTareasHaciaNextCycleResponde400() {
            projectCycles(sprint);

            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(CycleServiceImpl.NO_NEXT_CYCLE_MESSAGE);
            assertThat(sprint.getClosedAt()).isNull();
            assertThat(sprint.getIssues()).hasSize(4);
            verifyNoInteractions(activityService);
        }

        @Test
        void sinTareasSinTerminarNoHaceFaltaCicloSiguiente() {
            Cycle allDone = cycle("Sprint 9", today.minusDays(7), today.minusDays(1), finished);
            projectCycles(allDone);

            CloseCycleResponse response = cycleService.close(SLUG, project.getId(), allDone.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, null, false));

            assertThat(response.movedToCycle()).isZero();
            assertThat(allDone.getClosedAt()).isNotNull();
            // Ya había terminado: conserva su fecha de fin.
            assertThat(allDone.getEndDate()).isEqualTo(today.minusDays(1));
        }

        @Test
        void elCicloDestinoNoPuedeSerElMismoNiEstarCerrado() {
            Cycle closedTarget = closed(cycle("Sprint 2", today.minusDays(21), today.minusDays(8)));

            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, sprint.getId(), false)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El ciclo destino debe ser otro ciclo");
            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.NEXT_CYCLE, closedTarget.getId(), false)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("El ciclo destino está cerrado");
            UUID unknown = UUID.randomUUID();
            when(cycleRepository.findByIdAndProjectId(unknown, project.getId())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, unknown, false)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("El ciclo destino no existe en este proyecto");
            assertThat(sprint.getClosedAt()).isNull();
        }

        @Test
        void cerrarUnCicloYaCerradoResponde409() {
            closed(sprint);

            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, null, false)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CycleServiceImpl.ALREADY_CLOSED_MESSAGE);
            verify(cycleRepository, never()).saveAndFlush(any());
        }

        @Test
        void unCicloQueNoHaEmpezadoNoSePuedeCerrar() {
            assertThatThrownBy(() -> cycleService.close(SLUG, project.getId(), next.getId(), pm.getId(),
                    closeRequest(CycleMoveTarget.BACKLOG, null, false)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CycleServiceImpl.NOT_STARTED_MESSAGE);
        }
    }

    @Nested
    class EditarFechas {

        private Cycle sprint;
        private LocalDate start;
        private LocalDate end;

        @BeforeEach
        void sprint() {
            start = today.plusDays(1);
            end = today.plusDays(14);
            sprint = cycle("Sprint 4", start, end);
        }

        private void update(UpdateCycleRequest request) {
            cycleService.update(SLUG, project.getId(), sprint.getId(), request);
        }

        @Test
        void quitaSoloElInicio() {
            update(new UpdateCycleRequest(null, null, null, null, null, true, null));

            assertThat(sprint.getStartDate()).isNull();
            assertThat(sprint.getEndDate()).isEqualTo(end);
        }

        @Test
        void quitaSoloElFin() {
            update(new UpdateCycleRequest(null, null, null, null, null, null, true));

            assertThat(sprint.getStartDate()).isEqualTo(start);
            assertThat(sprint.getEndDate()).isNull();
        }

        @Test
        void clearDatesSigueQuitandoLasDos() {
            update(new UpdateCycleRequest(null, null, null, null, true));

            assertThat(sprint.getStartDate()).isNull();
            assertThat(sprint.getEndDate()).isNull();
        }

        @Test
        void elFinNoPuedeQuedarAntesDelInicio() {
            assertThatThrownBy(() -> update(new UpdateCycleRequest(null, null, null, start.minusDays(1), null)))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> update(new UpdateCycleRequest(null, null, end.plusDays(1), null, null, null,
                    null)))
                    .isInstanceOf(BadRequestException.class);
            // Nada cambió
            assertThat(sprint.getStartDate()).isEqualTo(start);
            assertThat(sprint.getEndDate()).isEqualTo(end);
            verify(cycleRepository, never()).saveAndFlush(any());
        }

        @Test
        void enviarUnaFechaYPedirQuitarlaEsUnError() {
            assertThatThrownBy(() -> update(new UpdateCycleRequest(null, null, null, end.plusDays(1), null, null,
                    true)))
                    .isInstanceOf(BadRequestException.class);
            assertThat(sprint.getEndDate()).isEqualTo(end);
        }

        @Test
        void enUnCicloCerradoQuitarUnaFechaTambienEsCambiarla() {
            closed(sprint);

            assertThatThrownBy(() -> update(new UpdateCycleRequest(null, null, null, null, null, true, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CycleServiceImpl.CLOSED_EDIT_MESSAGE);
            assertThat(sprint.getStartDate()).isEqualTo(start);
        }
    }

    @Nested
    class ClosedCycle {

        private Cycle sprint;

        @BeforeEach
        void closedCycle() {
            sprint = closed(cycle("Sprint 3", today.minusDays(7), today.plusDays(7)));
            sprint.setDescription("Objetivo: pagos");
        }

        @Test
        void noCambiaDeNombreNiDeFechas() {
            List<UpdateCycleRequest> requests = List.of(
                    new UpdateCycleRequest("Sprint 3b", null, null, null, null),
                    new UpdateCycleRequest(null, null, today.minusDays(6), null, null),
                    new UpdateCycleRequest(null, null, null, today.plusDays(1), null),
                    new UpdateCycleRequest(null, null, null, null, true));
            for (UpdateCycleRequest request : requests) {
                assertThatThrownBy(() -> cycleService.update(SLUG, project.getId(), sprint.getId(), request))
                        .isInstanceOf(ConflictException.class)
                        .hasMessage(CycleServiceImpl.CLOSED_EDIT_MESSAGE);
            }
            assertThat(sprint.getName()).isEqualTo("Sprint 3");
            verify(cycleRepository, never()).saveAndFlush(any());
        }

        @Test
        void siSePuedeCambiarSuDescripcionAunqueLleguenElMismoNombreYFechas() {
            cycleService.update(SLUG, project.getId(), sprint.getId(), new UpdateCycleRequest(" Sprint 3 ",
                    "Objetivo: pagos y facturas", sprint.getStartDate(), sprint.getEndDate(), false));

            assertThat(sprint.getDescription()).isEqualTo("Objetivo: pagos y facturas");
        }

        @Test
        void noRecibeTareas() {
            assertThatThrownBy(() -> cycleService.addIssues(SLUG, project.getId(), sprint.getId(), pm.getId(),
                    new IssueIdsRequest(List.of(UUID.randomUUID()))))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CycleServiceImpl.CLOSED_ADD_MESSAGE);
            verifyNoInteractions(support, activityService);
        }

        @Test
        void noSeLeQuitanTareas() {
            Issue finished = completedIssue(Duration.ofDays(3), Duration.ofDays(1));
            sprint.getIssues().add(finished);

            assertThatThrownBy(() -> cycleService.removeIssue(SLUG, project.getId(), sprint.getId(),
                    finished.getId(), pm.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(CycleServiceImpl.CLOSED_REMOVE_MESSAGE);
            assertThat(sprint.getIssues()).contains(finished);
            verifyNoInteractions(activityService);
        }

        @Test
        void unaTareaQueSaleDeElHaciaOtroCicloQuedaEnSuHistorial() {
            Issue reopened = issue(todo);
            sprint.getIssues().add(reopened);
            reopened.getCycles().add(sprint);
            Cycle sprint4 = cycle("Sprint 4", today, today.plusDays(14));
            when(support.findIssues(project, List.of(reopened.getId()))).thenReturn(List.of(reopened));

            cycleService.addIssues(SLUG, project.getId(), sprint4.getId(), pm.getId(),
                    new IssueIdsRequest(List.of(reopened.getId())));

            verify(cycleRepository).removeIssuesFromOtherCycles(sprint4.getId(), List.of(reopened.getId()));
            // «de Sprint 3 a Sprint 4»; el resumen de Sprint 3 no cambia (lo guarda la foto del cierre)
            verify(activityService).record(reopened, pm, ActivityField.CYCLE, "Sprint 3", "Sprint 4");
        }

        @Test
        void suEstadoEsCompletedAunqueSusFechasNoHayanTerminado() {
            CycleResponse response = cycleService.get(SLUG, project.getId(), sprint.getId());

            assertThat(response.status()).isEqualTo(CycleStatus.COMPLETED);
            assertThat(response.closedAt()).isEqualTo(sprint.getClosedAt());
        }
    }

    /** QA: la lista de ciclos (y la hoja de ruta, que usa la misma) en orden y con el avance de la retrospectiva. */
    @Nested
    class ListaDeCiclos {

        private com.taskflow.api.repository.IssueProgress live(Cycle cycle, long total, long completed) {
            return new com.taskflow.api.repository.IssueProgress() {
                @Override
                public UUID getId() {
                    return cycle.getId();
                }

                @Override
                public long getTotal() {
                    return total;
                }

                @Override
                public long getCompleted() {
                    return completed;
                }
            };
        }

        @Test
        void unCicloCerradoMuestraElAvanceDeLaFotoDelCierre() {
            // Sprint 1 se cerró con 1 de 2: la tarea sin terminar salió del ciclo y ahora solo queda la completada
            Cycle sprint1 = closed(cycle("Sprint 1", today.minusDays(14), today.minusDays(1)));
            sprint1.setClosedCompletedCount(1);
            sprint1.setClosedTotalCount(2);
            sprint1.setCarriedToBacklog(1);
            // Cerrado antes de la foto (V25): a lo que queda se suman las tareas que se llevó el cierre
            Cycle sprint0 = closed(cycle("Sprint 0", today.minusDays(28), today.minusDays(15)));
            sprint0.setCarriedToCycle(2);
            Cycle sprint2 = cycle("Sprint 2", today, today.plusDays(13));
            projectCycles(sprint2, sprint1, sprint0);
            when(cycleRepository.findProgress(anyCollection(), eq(StateGroup.COMPLETED))).thenReturn(List.of(
                    live(sprint1, 1, 1), live(sprint0, 2, 2), live(sprint2, 4, 1)));

            List<CycleResponse> cycles = cycleService.getCycles(SLUG, project.getId());

            assertThat(cycles).extracting(CycleResponse::name).containsExactly("Sprint 2", "Sprint 1", "Sprint 0");
            assertThat(cycles.get(0).progress()).isEqualTo(ProgressResponse.of(4, 1));
            assertThat(cycles.get(1).progress()).isEqualTo(ProgressResponse.of(2, 1));
            assertThat(cycles.get(1).progress().percentage()).isEqualTo(50);
            assertThat(cycles.get(2).progress()).isEqualTo(ProgressResponse.of(4, 2));
            // Igual que el resumen de la retrospectiva
            assertThat(RetroServiceImpl.summary(sprint1).completedCount()).isEqualTo(1);
            assertThat(RetroServiceImpl.summary(sprint1).totalCount()).isEqualTo(2);
        }

        @Test
        void elDetalleDeUnCicloCerradoTambienUsaLaFoto() {
            Cycle sprint1 = closed(cycle("Sprint 1", today.minusDays(14), today.minusDays(1)));
            sprint1.setClosedCompletedCount(3);
            sprint1.setClosedTotalCount(4);
            when(cycleRepository.findProgress(anyCollection(), eq(StateGroup.COMPLETED)))
                    .thenReturn(List.of(live(sprint1, 3, 3)));

            assertThat(cycleService.get(SLUG, project.getId(), sprint1.getId()).progress())
                    .isEqualTo(ProgressResponse.of(4, 3));
        }

        @Test
        void primeroElEnCursoDespuesLosProximosLosBorradoresYLosCerrados() {
            Cycle oldClosed = closed(cycle("Sprint 1", today.minusDays(28), today.minusDays(15)));
            oldClosed.setClosedAt(now.minus(Duration.ofDays(14)));
            Cycle lastClosed = closed(cycle("Sprint 2", today.minusDays(14), today.minusDays(1)));
            lastClosed.setClosedAt(now.minus(Duration.ofDays(1)));
            Cycle current = cycle("Sprint 3", today.minusDays(1), today.plusDays(12));
            Cycle later = cycle("Sprint 5", today.plusDays(27), today.plusDays(40));
            Cycle next = cycle("Sprint 4", today.plusDays(13), today.plusDays(26));
            Cycle draft = cycle("Ideas", null, null);
            Cycle ended = cycle("Sin cerrar", today.minusDays(10), today.minusDays(2));
            // En el orden de la base de datos (por inicio, del más reciente al más antiguo; sin fechas al final)
            projectCycles(later, next, current, lastClosed, ended, oldClosed, draft);

            List<CycleResponse> cycles = cycleService.getCycles(SLUG, project.getId());

            assertThat(cycles).extracting(CycleResponse::name).containsExactly(
                    "Sprint 3", "Sin cerrar", "Sprint 4", "Sprint 5", "Ideas", "Sprint 2", "Sprint 1");
        }
    }
}
