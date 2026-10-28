package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.DailyNoteRequest;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.dto.response.DailyResponse;
import com.taskflow.api.dto.response.DailyResponse.DailyBlocker;
import com.taskflow.api.dto.response.DailyResponse.DailyChange;
import com.taskflow.api.dto.response.DailyResponse.DailyPerson;
import com.taskflow.api.dto.response.DailyResponse.DailySummary;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.DailyNote;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.DailyNoteRepository;
import com.taskflow.api.repository.IssueActivityRepository;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.StateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyServiceImplTest {

    private static final String SLUG = "mi-equipo";
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    /** Miércoles. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 28);
    /** Quien consulta la daily. */
    private static final UUID VIEWER = UUID.randomUUID();

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private CycleRepository cycleRepository;
    @Mock
    private IssueActivityRepository activityRepository;
    @Mock
    private IssueRelationRepository relationRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private DailyNoteRepository noteRepository;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private CollaborationMapper collaborationMapper;

    private DailyServiceImpl dailyService;

    private Project project;
    private State todo;
    private State inProgress;
    private State review;
    private State done;
    private State cancelled;
    private int nextSequence = 1;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota", null);
        dailyService = new DailyServiceImpl(issueRepository, cycleRepository, activityRepository, relationRepository,
                projectMemberRepository, stateRepository, noteRepository, projectAccess, lookup, issueMapper,
                userMapper, collaborationMapper, properties);
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        todo = state("Todo", StateGroup.UNSTARTED, 1);
        inProgress = state("En curso", StateGroup.STARTED, 2);
        review = state("En revisión", StateGroup.STARTED, 3);
        done = state("Hecho", StateGroup.COMPLETED, 4);
        cancelled = state("Cancelada", StateGroup.CANCELLED, 5);

        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()))
                .thenReturn(List.of(todo, inProgress, review, done));
        lenient().when(issueMapper.toRelated(any(Issue.class)))
                .thenAnswer(invocation -> related(invocation.getArgument(0), true));
        lenient().when(issueMapper.toRestrictedRelated(any(Issue.class)))
                .thenAnswer(invocation -> related(invocation.getArgument(0), false));
        lenient().when(userMapper.toSummary(any(User.class)))
                .thenAnswer(invocation -> summary(invocation.getArgument(0)));
    }

    private static RelatedIssueResponse related(Issue issue, boolean full) {
        return new RelatedIssueResponse(issue.getId(), issue.getCode(), full ? issue.getTitle() : null, null,
                full ? issue.getAssignees().stream().map(DailyServiceImplTest::summary).toList() : List.of(), null,
                issue.getDueDate(), issue.getBlockedByCount(), 0, 0, 0, 0);
    }

    private static UserSummaryResponse summary(User user) {
        return new UserSummaryResponse(user.getId(), user.getEmail(), user.getFullName(), null);
    }

    private State state(String name, StateGroup group, int position) {
        State state = new State(project, name, "#000000", group, position);
        state.setId(UUID.randomUUID());
        return state;
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        user.setEmail(name.toLowerCase() + "@taskflow.dev");
        return user;
    }

    private Issue issue(State state, LocalDate dueDate, User... assignees) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setSequenceId(nextSequence++);
        issue.setTitle("Tarea " + issue.getSequenceId());
        issue.setState(state);
        issue.setPriority(IssuePriority.NONE);
        issue.setDueDate(dueDate);
        issue.getAssignees().addAll(List.of(assignees));
        return issue;
    }

    private static IssueActivity activity(User actor, Issue issue, ActivityField field, String newValue,
                                          Instant at) {
        IssueActivity activity = new IssueActivity();
        activity.setId(UUID.randomUUID());
        activity.setActor(actor);
        activity.setIssue(issue);
        activity.setField(field);
        activity.setNewValue(newValue);
        activity.setCreatedAt(at);
        return activity;
    }

    private Cycle cycle(String name, LocalDate start, LocalDate end) {
        Cycle cycle = new Cycle();
        cycle.setId(UUID.randomUUID());
        cycle.setProject(project);
        cycle.setName(name);
        cycle.setStartDate(start);
        cycle.setEndDate(end);
        return cycle;
    }

    private static IssueActivity stateChange(User actor, Issue issue, String from, String to, Instant at) {
        IssueActivity activity = activity(actor, issue, ActivityField.STATE, to, at);
        activity.setOldValue(from);
        return activity;
    }

    private void members(User... users) {
        List<ProjectMember> members = new ArrayList<>();
        for (User user : users) {
            members.add(new ProjectMember(project, user));
        }
        when(projectMemberRepository.findAllByProjectIdWithUser(project.getId())).thenReturn(members);
    }

    private void openIssues(Issue... issues) {
        when(issueRepository.findAssignedInProject(project.getId(), StateGroup.CLOSED)).thenReturn(List.of(issues));
    }

    private void activities(IssueActivity... activities) {
        when(activityRepository.findAllInProjectBetween(eq(project.getId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(activities));
    }

    /** Un instante del día indicado, a la hora dada (hora de Bogotá). */
    private static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    @Test
    void elDiaHabilAnteriorDelLunesYDelFinDeSemanaEsElViernes() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        assertThat(DailyServiceImpl.previousWorkingDay(LocalDate.of(2026, 10, 26))).isEqualTo(friday);
        assertThat(DailyServiceImpl.previousWorkingDay(LocalDate.of(2026, 10, 25))).isEqualTo(friday);
        assertThat(DailyServiceImpl.previousWorkingDay(LocalDate.of(2026, 10, 24))).isEqualTo(friday);
        assertThat(DailyServiceImpl.previousWorkingDay(LocalDate.of(2026, 10, 27))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(DailyServiceImpl.previousWorkingDay(WEDNESDAY)).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(DailyServiceImpl.previousWorkingDay(friday)).isEqualTo(LocalDate.of(2026, 10, 22));
    }

    @Nested
    class GetDaily {

        private User ana;
        private User beto;
        private User carla;

        @BeforeEach
        void people() {
            ana = user("Ana");
            beto = user("beto");
            carla = user("Carla");
        }

        @Test
        void personasMiembrosYAsignadosPorNombreAunqueNoTenganNada() {
            // Beto no es miembro del proyecto, pero tiene una tarea abierta asignada.
            members(carla, ana);
            openIssues(issue(todo, null, beto));
            activities();

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            assertThat(daily.date()).isEqualTo(WEDNESDAY);
            assertThat(daily.previousDate()).isEqualTo(LocalDate.of(2026, 10, 27));
            assertThat(daily.people()).extracting(person -> person.user().fullName())
                    .containsExactly("Ana", "beto", "Carla");
            DailyPerson carlaDaily = daily.people().get(2);
            assertThat(carlaDaily.yesterday()).isEmpty();
            assertThat(carlaDaily.today()).isEmpty();
            assertThat(carlaDaily.blockers()).isEmpty();
            assertThat(daily.note()).isNull();
        }

        @Test
        void ayerEsUnaEntradaPorTareaConElCambioMasImportante() {
            members(ana, beto);
            LocalDate tuesday = WEDNESDAY.minusDays(1);
            Issue completed = issue(done, null);
            Issue reviewed = issue(review, null, ana);
            Issue commented = issue(todo, null);
            Issue edited = issue(todo, null);
            Issue byBeto = issue(todo, null);
            openIssues(reviewed);
            activities(
                    activity(ana, completed, ActivityField.COMMENT, "Listo", at(tuesday, 9)),
                    activity(ana, edited, ActivityField.PRIORITY, "HIGH", at(tuesday, 9)),
                    activity(ana, reviewed, ActivityField.CREATED, "Tarea", at(tuesday, 10)),
                    activity(ana, completed, ActivityField.STATE, "hecho", at(tuesday, 11)),
                    activity(ana, reviewed, ActivityField.STATE, "En curso", at(tuesday, 12)),
                    stateChange(ana, reviewed, "En curso", "En revisión", at(tuesday, 15)),
                    activity(ana, commented, ActivityField.COMMENT, "¿Lo vemos?", at(tuesday, 16)),
                    activity(beto, byBeto, ActivityField.CREATED, "Tarea", at(tuesday, 8)));

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            List<DailyChange> yesterday = daily.people().getFirst().yesterday();
            assertThat(yesterday).extracting(change -> change.issue().id())
                    .containsExactly(completed.getId(), reviewed.getId(), commented.getId(), edited.getId());
            // El nombre del estado se compara sin importar mayúsculas; entre dos cambios de estado, el último.
            assertThat(yesterday).extracting(DailyChange::change)
                    .containsExactly("completó", "movió a En revisión", "comentó", "actualizó");
            // Los cambios de estado traen el estado de origen y el de destino («En curso → En revisión»)
            assertThat(yesterday.get(1).fromState()).isEqualTo("En curso");
            assertThat(yesterday.get(1).toState()).isEqualTo("En revisión");
            assertThat(yesterday.get(2).fromState()).isNull();
            assertThat(yesterday.get(2).toState()).isNull();
            assertThat(daily.people().get(1).yesterday()).extracting(DailyChange::change).containsExactly("creó");

            // Desde el inicio del día hábil anterior hasta el inicio del día de la reunión, hora de Bogotá.
            verify(activityRepository).findAllInProjectBetween(project.getId(), at(tuesday, 0), at(WEDNESDAY, 0));
        }

        @Test
        void elLunesIncluyeLaActividadDesdeElViernes() {
            members(ana);
            openIssues();
            activities();
            LocalDate monday = LocalDate.of(2026, 10, 26);

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, monday);

            assertThat(daily.previousDate()).isEqualTo(LocalDate.of(2026, 10, 23));
            verify(activityRepository).findAllInProjectBetween(project.getId(), at(LocalDate.of(2026, 10, 23), 0),
                    at(monday, 0));
        }

        @Test
        void comoMaximoOchoEntradasPorLista() {
            members(ana);
            Issue[] started = new Issue[10];
            IssueActivity[] comments = new IssueActivity[10];
            for (int i = 0; i < 10; i++) {
                started[i] = issue(inProgress, null, ana);
                comments[i] = activity(ana, started[i], ActivityField.COMMENT, "…", at(WEDNESDAY.minusDays(1), 8 + i));
            }
            openIssues(started);
            activities(comments);

            DailyPerson anaDaily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY).people()
                    .getFirst();

            assertThat(anaDaily.yesterday()).hasSize(DailyServiceImpl.MAX_ITEMS);
            // Del más reciente al más antiguo.
            assertThat(anaDaily.yesterday().getFirst().issue().id()).isEqualTo(started[9].getId());
            assertThat(anaDaily.today()).hasSize(DailyServiceImpl.MAX_ITEMS);
        }

        @Test
        void hoySonLasEnCursoOConFechaLimiteHastaElDiaPorFechaLimite() {
            members(ana);
            Issue startedNoDate = issue(inProgress, null, ana);
            Issue dueToday = issue(todo, WEDNESDAY, ana);
            Issue overdue = issue(todo, WEDNESDAY.minusDays(3), ana);
            Issue dueLater = issue(todo, WEDNESDAY.plusDays(1), ana);
            Issue startedDueLater = issue(review, WEDNESDAY.plusDays(5), ana);
            openIssues(startedNoDate, dueToday, dueLater, overdue, startedDueLater);
            activities();

            DailyPerson anaDaily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY).people()
                    .getFirst();

            assertThat(anaDaily.today()).extracting(RelatedIssueResponse::id).containsExactly(
                    overdue.getId(), dueToday.getId(), startedDueLater.getId(), startedNoDate.getId());
        }

        @Test
        void bloqueosConLasTareasAbiertasQueLosCausan() {
            members(ana, beto);
            Issue blocked = issue(todo, null, ana);
            blocked.setBlockedByCount(2);
            Issue free = issue(todo, null, ana);
            Issue blockerB = issue(inProgress, null, beto);
            Issue blockerA = issue(todo, null);
            openIssues(blocked, free, blockerB);
            activities();
            when(relationRepository.findOpenBlockersOf(List.of(blocked.getId()), StateGroup.CLOSED)).thenReturn(List.of(
                    new IssueRelation(blockerB, blocked, IssueRelationType.BLOCKS),
                    new IssueRelation(blockerA, blocked, IssueRelationType.BLOCKS)));

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            List<DailyBlocker> blockers = daily.people().getFirst().blockers();
            assertThat(blockers).hasSize(1);
            assertThat(blockers.getFirst().issue().id()).isEqualTo(blocked.getId());
            assertThat(blockers.getFirst().blockedBy()).extracting(RelatedIssueResponse::code)
                    .containsExactly(blockerB.getCode(), blockerA.getCode());
            // Quién tiene cada tarea que bloquea, aunque esa tarea no aparezca en otra parte de la daily
            assertThat(blockers.getFirst().blockedBy().getFirst().assignees()).extracting(UserSummaryResponse::id)
                    .containsExactly(beto.getId());
            assertThat(blockers.getFirst().blockedBy().get(1).assignees()).isEmpty();
            assertThat(daily.summary().blocked()).isEqualTo(1);
            assertThat(daily.people().get(1).blockers()).isEmpty();
        }


        @Test
        void unBloqueoDeOtroProyectoSinAccesoVaSinTituloNiAsignados() {
            members(ana);
            Workspace workspace = new Workspace();
            workspace.setId(UUID.randomUUID());
            project.setWorkspace(workspace);
            Project app = new Project();
            app.setId(UUID.randomUUID());
            app.setIdentifier("AMC");
            Project api = new Project();
            api.setId(UUID.randomUUID());
            api.setIdentifier("API");
            Issue blocked = issue(todo, null, ana);
            blocked.setBlockedByCount(2);
            Issue visible = issue(todo, null, beto);
            visible.setProject(app);
            Issue hidden = issue(todo, null, beto);
            hidden.setProject(api);
            openIssues(blocked);
            activities();
            when(relationRepository.findOpenBlockersOf(List.of(blocked.getId()), StateGroup.CLOSED)).thenReturn(List.of(
                    new IssueRelation(hidden, blocked, IssueRelationType.BLOCKS),
                    new IssueRelation(visible, blocked, IssueRelationType.BLOCKS)));
            when(projectAccess.projectIds(workspace, VIEWER)).thenReturn(List.of(project.getId(), app.getId()));

            DailyBlocker blocker = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY).people()
                    .getFirst().blockers().getFirst();

            // Por código: AMC antes que API
            assertThat(blocker.blockedBy()).extracting(RelatedIssueResponse::code)
                    .containsExactly(visible.getCode(), hidden.getCode());
            assertThat(blocker.blockedBy().getFirst().title()).isEqualTo(visible.getTitle());
            assertThat(blocker.blockedBy().getFirst().assignees()).hasSize(1);
            assertThat(blocker.blockedBy().get(1).title()).isNull();
            assertThat(blocker.blockedBy().get(1).assignees()).isEmpty();
        }

        @Test
        void sinTareasBloqueadasNoConsultaLasRelaciones() {
            members(ana);
            openIssues(issue(todo, null, ana));
            activities();

            dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            verify(relationRepository, never()).findOpenBlockersOf(anyCollection(), anyCollection());
            verifyNoInteractions(projectAccess);
        }

        @Test
        void resumenDelPeriodo() {
            members(ana);
            Issue moved = issue(review, null, ana);
            Issue completed = issue(done, null);
            Issue commented = issue(todo, null);
            openIssues(moved);
            LocalDate tuesday = WEDNESDAY.minusDays(1);
            activities(
                    stateChange(ana, moved, "Todo", "En curso", at(tuesday, 9)),
                    stateChange(ana, moved, "En curso", "En revisión", at(tuesday, 10)),
                    stateChange(ana, completed, "En revisión", "Hecho", at(tuesday, 11)),
                    activity(ana, commented, ActivityField.COMMENT, "¿Lo vemos?", at(tuesday, 12)),
                    activity(ana, moved, ActivityField.COMMENT, "Listo", at(tuesday, 13)));
            when(issueRepository.countStartedBetween(project.getId(), at(tuesday, 0), at(WEDNESDAY, 0)))
                    .thenReturn(1L);
            when(issueRepository.countCompletedBetween(project.getId(), at(tuesday, 0), at(WEDNESDAY, 0)))
                    .thenReturn(1L);

            DailySummary summary = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY).summary();

            // Tareas distintas que cambiaron de estado (una que se movió dos veces cuenta una vez)
            assertThat(summary.moved()).isEqualTo(2);
            assertThat(summary.started()).isEqualTo(1);
            assertThat(summary.completed()).isEqualTo(1);
            assertThat(summary.comments()).isEqualTo(2);
            assertThat(summary.blocked()).isZero();
        }

        @Test
        void contadoresDelCicloEnCursoPorPersona() {
            members(ana, beto);
            Cycle sprint = cycle("Sprint 1", WEDNESDAY.minusDays(12), WEDNESDAY.plusDays(2));
            Cycle next = cycle("Sprint 2", WEDNESDAY.plusDays(3), WEDNESDAY.plusDays(17));
            when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(next, sprint));
            Issue anaOpen = issue(todo, null, ana);
            Issue anaDone = issue(done, null, ana);
            Issue shared = issue(inProgress, null, ana, beto);
            Issue anaCancelled = issue(cancelled, null, ana);
            openIssues(anaOpen, shared, issue(todo, null, ana));
            activities();
            when(issueRepository.findAllInCycle(sprint.getId()))
                    .thenReturn(List.of(anaOpen, anaDone, shared, anaCancelled));

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            assertThat(daily.cycle().id()).isEqualTo(sprint.getId());
            assertThat(daily.cycle().name()).isEqualTo("Sprint 1");
            assertThat(daily.cycle().startDate()).isEqualTo(sprint.getStartDate());
            assertThat(daily.cycle().endDate()).isEqualTo(sprint.getEndDate());
            DailyPerson anaDaily = daily.people().getFirst();
            assertThat(anaDaily.openCount()).isEqualTo(3);
            // Las canceladas no cuentan como asignadas en el ciclo
            assertThat(anaDaily.cycleAssignedCount()).isEqualTo(3);
            assertThat(anaDaily.cycleCompletedCount()).isEqualTo(1);
            DailyPerson betoDaily = daily.people().get(1);
            assertThat(betoDaily.openCount()).isEqualTo(1);
            assertThat(betoDaily.cycleAssignedCount()).isEqualTo(1);
            assertThat(betoDaily.cycleCompletedCount()).isZero();
        }

        @Test
        void sinCicloEnCursoNoHayContadoresDelCiclo() {
            members(ana);
            Cycle upcoming = cycle("Sprint 2", WEDNESDAY.plusDays(1), WEDNESDAY.plusDays(14));
            Cycle closedOne = cycle("Sprint 1", WEDNESDAY.minusDays(14), WEDNESDAY.plusDays(1));
            closedOne.setClosedAt(Instant.now());
            when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(upcoming, closedOne));
            openIssues(issue(todo, null, ana));
            activities();

            DailyResponse daily = dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY);

            assertThat(daily.cycle()).isNull();
            assertThat(daily.people().getFirst().openCount()).isEqualTo(1);
            assertThat(daily.people().getFirst().cycleAssignedCount()).isZero();
            verify(issueRepository, never()).findAllInCycle(any());
        }

        @Test
        void traeLaNotaDelDia() {
            members();
            openIssues();
            activities();
            DailyNote note = new DailyNote(project, WEDNESDAY);
            note.setText("Ana pide ayuda con el deploy");
            DailyNoteResponse response = new DailyNoteResponse(note.getText(), null, Instant.now());
            when(noteRepository.findByProjectIdAndDate(project.getId(), WEDNESDAY)).thenReturn(Optional.of(note));
            when(collaborationMapper.toDailyNoteResponse(note)).thenReturn(response);

            assertThat(dailyService.getDaily(SLUG, project.getId(), VIEWER, WEDNESDAY).note()).isSameAs(response);
        }
    }

    @Nested
    class SaveNote {

        private User ana;

        @BeforeEach
        void author() {
            ana = user("Ana");
            lenient().when(lookup.user(ana.getId())).thenReturn(ana);
            lenient().when(noteRepository.saveAndFlush(any(DailyNote.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            lenient().when(collaborationMapper.toDailyNoteResponse(any(DailyNote.class))).thenAnswer(invocation -> {
                DailyNote note = invocation.getArgument(0);
                return new DailyNoteResponse(note.getText(), null, null);
            });
        }

        @Test
        void creaLaNotaDelDiaConSuAutor() {
            when(noteRepository.findByProjectIdAndDate(project.getId(), WEDNESDAY)).thenReturn(Optional.empty());

            Optional<DailyNoteResponse> response = dailyService.saveNote(SLUG, project.getId(), ana.getId(), WEDNESDAY,
                    new DailyNoteRequest("  Bloqueo con el proveedor de pagos \n"));

            ArgumentCaptor<DailyNote> saved = ArgumentCaptor.forClass(DailyNote.class);
            verify(noteRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getProject()).isSameAs(project);
            assertThat(saved.getValue().getDate()).isEqualTo(WEDNESDAY);
            assertThat(saved.getValue().getUpdatedBy()).isSameAs(ana);
            assertThat(response).map(DailyNoteResponse::text).contains("Bloqueo con el proveedor de pagos");
        }

        @Test
        void editaLaNotaExistente() {
            DailyNote existing = new DailyNote(project, WEDNESDAY);
            existing.setText("Antes");
            when(noteRepository.findByProjectIdAndDate(project.getId(), WEDNESDAY)).thenReturn(Optional.of(existing));

            dailyService.saveNote(SLUG, project.getId(), ana.getId(), WEDNESDAY, new DailyNoteRequest("Después"));

            assertThat(existing.getText()).isEqualTo("Después");
            assertThat(existing.getUpdatedBy()).isSameAs(ana);
            verify(noteRepository).saveAndFlush(existing);
        }

        @Test
        void unTextoVacioBorraLaNota() {
            DailyNote existing = new DailyNote(project, WEDNESDAY);
            when(noteRepository.findByProjectIdAndDate(project.getId(), WEDNESDAY)).thenReturn(Optional.of(existing));

            Optional<DailyNoteResponse> response = dailyService.saveNote(SLUG, project.getId(), ana.getId(), WEDNESDAY,
                    new DailyNoteRequest("   "));

            assertThat(response).isEmpty();
            verify(noteRepository).delete(existing);
            verify(noteRepository, never()).saveAndFlush(any());
        }

        @Test
        void sinFechaUsaElDiaDeHoy() {
            LocalDate today = LocalDate.now(ZONE);
            when(noteRepository.findByProjectIdAndDate(project.getId(), today)).thenReturn(Optional.empty());

            Optional<DailyNoteResponse> response = dailyService.saveNote(SLUG, project.getId(), ana.getId(), null,
                    new DailyNoteRequest(null));

            assertThat(response).isEmpty();
            verify(noteRepository, never()).delete(any());
        }
    }
}
