package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.ChecklistItemRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.DescriptionConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueTemplateRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.FileService;
import com.taskflow.api.service.IssueChecklistService;
import com.taskflow.api.service.IssueRelationService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.util.SortOrderCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private IssueTemplateRepository templateRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private LabelRepository labelRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock
    private ActivityService activityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private IssueRelationService relationService;
    @Mock
    private IssueChecklistService checklistService;
    @Mock
    private StoryProgress storyProgress;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private FileService fileService;

    @InjectMocks
    private IssueServiceImpl issueService;

    private Project project;
    private State todo;
    private State done;
    private User author;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        todo = state("Todo", StateGroup.UNSTARTED);
        done = state("Done", StateGroup.COMPLETED);
        author = new User();
        author.setId(UUID.randomUUID());
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
    }

    private State state(String name, StateGroup group) {
        State state = new State(project, name, "#000000", group, 0);
        state.setId(UUID.randomUUID());
        return state;
    }

    private Issue issue(State state, double sortOrder) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setState(state);
        issue.setSortOrder(sortOrder);
        when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId())).thenReturn(Optional.of(issue));
        return issue;
    }

    private static CreateIssueRequest createRequest(String title) {
        return new CreateIssueRequest(title, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Nested
    class Create {

        @BeforeEach
        void defaults() {
            lenient().when(lookup.user(author.getId())).thenReturn(author);
            when(stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId())).thenReturn(Optional.of(todo));
        }

        @Test
        void asignaElSiguienteNumeroDelProyectoYGeneraElCodigo() {
            when(projectRepository.findIssueCounter(project.getId())).thenReturn(12);

            issueService.create(SLUG, project.getId(), author.getId(), createRequest("  Login con Google "));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getSequenceId()).isEqualTo(12);
            assertThat(saved.getValue().getCode()).isEqualTo("WEB-12");
            assertThat(saved.getValue().getTitle()).isEqualTo("Login con Google");
            assertThat(saved.getValue().getPriority()).isEqualTo(IssuePriority.NONE);
            assertThat(saved.getValue().getState()).isSameAs(todo);
            assertThat(saved.getValue().getCreatedBy()).isSameAs(author);

            // Primero se incrementa el contador (bloquea la fila) y luego se lee el valor nuevo
            InOrder order = inOrder(projectRepository);
            order.verify(projectRepository).incrementIssueCounter(project.getId());
            order.verify(projectRepository).findIssueCounter(project.getId());
        }

        @Test
        void quedaAlFinalDeSuColumna() {
            when(issueRepository.findMaxSortOrder(todo.getId())).thenReturn(3 * SortOrderCalculator.GAP);
            when(projectRepository.findIssueCounter(project.getId())).thenReturn(1);

            issueService.create(SLUG, project.getId(), author.getId(), createRequest("Tarea"));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getSortOrder()).isEqualTo(4 * SortOrderCalculator.GAP);
        }

        @Test
        void rechazaAsignadosQueNoSonMiembrosDelProyecto() {
            User outsider = new User();
            outsider.setId(UUID.randomUUID());
            when(userRepository.findAllById(Set.of(outsider.getId()))).thenReturn(List.of(outsider));
            when(projectMemberRepository.countMembers(eq(project.getId()), anyCollection())).thenReturn(0L);
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null, null, null,
                    List.of(outsider.getId()), null, null, null, null, null, null);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("miembros del proyecto");
            verify(issueRepository, never()).save(any());
        }

        @Test
        void creaLaChecklistDeLaPlantillaYDevuelveSusConteos() {
            List<ChecklistItemRequest> checklist = List.of(
                    new ChecklistItemRequest(ChecklistItemKind.CRITERION, "Dado …, cuando …, entonces …"),
                    new ChecklistItemRequest(ChecklistItemKind.CHECK, "Reproducir el error"),
                    new ChecklistItemRequest(ChecklistItemKind.CHECK, "Corregir"));
            when(checklistService.createAll(any(Issue.class), eq(checklist))).thenAnswer(invocation -> {
                Issue created = invocation.getArgument(0);
                return checklist.stream()
                        .map(item -> new IssueChecklistItem(created, item.kind(), item.text(), 0))
                        .toList();
            });
            CreateIssueRequest request = new CreateIssueRequest("[Bug] Login", null, null, null, null, null, null,
                    null, null, checklist, null, null, null, null);

            issueService.create(SLUG, project.getId(), author.getId(), request);

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            verify(checklistService).createAll(saved.getValue(), checklist);
            assertThat(saved.getValue().getChecklistUpdatedAt()).isNotNull();
            assertThat(saved.getValue().getCriteriaTotal()).isEqualTo(1);
            assertThat(saved.getValue().getChecklistTotal()).isEqualTo(2);
            assertThat(saved.getValue().getChecklistDone()).isZero();
            assertThat(saved.getValue().getCriteriaDone()).isZero();
        }

        @Test
        void conPlantillaSumaUnUsoALaPlantilla() {
            UUID templateId = UUID.randomUUID();
            when(templateRepository.incrementUsage(templateId, project.getId())).thenReturn(1);
            CreateIssueRequest request = new CreateIssueRequest("[Bug] Login", null, null, null, null, null, null,
                    null, null, null, templateId, null, null, null);

            issueService.create(SLUG, project.getId(), author.getId(), request);

            verify(templateRepository).incrementUsage(templateId, project.getId());
            verify(issueRepository).save(any(Issue.class));
        }

        @Test
        void unaPlantillaDeOtroProyectoResponde400() {
            UUID templateId = UUID.randomUUID();
            when(templateRepository.incrementUsage(templateId, project.getId())).thenReturn(0);
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null, null, null,
                    null, null, null, templateId, null, null, null);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La plantilla no existe en este proyecto");
            verify(issueRepository, never()).save(any());
        }

        @Test
        void sinPlantillaNoCuentaUsos() {
            when(projectRepository.findIssueCounter(project.getId())).thenReturn(1);

            issueService.create(SLUG, project.getId(), author.getId(), createRequest("Tarea"));

            verifyNoInteractions(templateRepository);
            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            // Sin checklist, la checklist nunca se actualizó; en un estado sin empezar, la tarea no empezó
            assertThat(saved.getValue().getChecklistUpdatedAt()).isNull();
            assertThat(saved.getValue().getStartedAt()).isNull();
        }

        @Test
        void rechazaFechaLimiteAnteriorAlInicio() {
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null,
                    LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10), null, null, null, null, null, null, null);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Move {

        @BeforeEach
        void stubs() {
            when(stateRepository.findByIdAndProjectId(done.getId(), project.getId())).thenReturn(Optional.of(done));
            lenient().when(issueRepository.saveAndFlush(any(Issue.class))).thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        void entreDosTarjetasQuedaEnElPromedioYCambiaDeEstado() {
            Issue moving = issue(todo, 50);
            Issue above = issue(done, 100);
            Issue below = issue(done, 200);

            issueService.move(SLUG, project.getId(), moving.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), above.getId(), below.getId()));

            assertThat(moving.getSortOrder()).isEqualTo(150);
            assertThat(moving.getState()).isSameAs(done);
            // Solo cambia la tarjeta movida
            assertThat(above.getSortOrder()).isEqualTo(100);
            assertThat(below.getSortOrder()).isEqualTo(200);
        }

        @Test
        void sinVecinasVaAlFinalDeLaColumna() {
            Issue moving = issue(todo, 50);
            when(issueRepository.findMaxSortOrder(done.getId())).thenReturn(1000d);

            issueService.move(SLUG, project.getId(), moving.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), null, null));

            assertThat(moving.getSortOrder()).isEqualTo(1000d + SortOrderCalculator.GAP);
        }

        @Test
        void renumeraLaColumnaCuandoNoQuedaEspacio() {
            Issue moving = issue(todo, 50);
            Issue above = issue(done, 1.0);
            Issue below = issue(done, 1.0 + 1e-9);
            when(issueRepository.findAllByStateIdOrderBySortOrderAsc(done.getId())).thenReturn(List.of(above, below));

            issueService.move(SLUG, project.getId(), moving.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), above.getId(), below.getId()));

            assertThat(above.getSortOrder()).isEqualTo(SortOrderCalculator.GAP);
            assertThat(below.getSortOrder()).isEqualTo(2 * SortOrderCalculator.GAP);
            assertThat(moving.getSortOrder()).isEqualTo(1.5 * SortOrderCalculator.GAP);
        }

        @Test
        void rechazaVecinasDeOtroEstado() {
            Issue moving = issue(todo, 50);
            Issue otherColumn = issue(todo, 100);

            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), moving.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), otherColumn.getId(), null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void rechazaVecinasEnOrdenInvertido() {
            Issue moving = issue(todo, 50);
            Issue above = issue(done, 300);
            Issue below = issue(done, 100);

            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), moving.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), above.getId(), below.getId())))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class CompletedAt {

        private UpdateIssueRequest updateState(UUID stateId) {
            return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @BeforeEach
        void stubs() {
            lenient().when(lookup.user(author.getId())).thenReturn(author);
            lenient().when(issueRepository.saveAndFlush(any(Issue.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            lenient().when(stateRepository.findByIdAndProjectId(done.getId(), project.getId()))
                    .thenReturn(Optional.of(done));
            lenient().when(stateRepository.findByIdAndProjectId(todo.getId(), project.getId()))
                    .thenReturn(Optional.of(todo));
        }

        @Test
        void unaTareaCreadaEnUnEstadoCompletadoQuedaCompletada() {
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, done.getId(), null, null, null, null,
                    null, null, null, null, null, null, null);

            issueService.create(SLUG, project.getId(), author.getId(), request);

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getCompletedAt()).isNotNull();
        }

        @Test
        void alEditarSeFijaAlEntrarAlGrupoCompletadoYSeBorraAlSalir() {
            Issue issue = issue(todo, 1);
            Instant before = Instant.now();

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), updateState(done.getId()));
            assertThat(issue.getCompletedAt()).isNotNull().isAfterOrEqualTo(before);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), updateState(todo.getId()));
            assertThat(issue.getCompletedAt()).isNull();
        }

        @Test
        void alMoverEnElKanbanSeFijaYSeBorra() {
            Issue issue = issue(todo, 1);

            issueService.move(SLUG, project.getId(), issue.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), null, null));
            assertThat(issue.getCompletedAt()).isNotNull();

            issueService.move(SLUG, project.getId(), issue.getId(), author.getId(),
                    new MoveIssueRequest(todo.getId(), null, null));
            assertThat(issue.getCompletedAt()).isNull();
        }

        @Test
        void moverEntreDosEstadosCompletadosConservaLaFecha() {
            State deployed = state("Deployed", StateGroup.COMPLETED);
            when(stateRepository.findByIdAndProjectId(deployed.getId(), project.getId()))
                    .thenReturn(Optional.of(deployed));
            Issue issue = issue(done, 1);
            Instant completedAt = Instant.parse("2026-10-20T15:00:00Z");
            issue.setCompletedAt(completedAt);

            issueService.move(SLUG, project.getId(), issue.getId(), author.getId(),
                    new MoveIssueRequest(deployed.getId(), null, null));

            assertThat(issue.getCompletedAt()).isEqualTo(completedAt);
        }

        @Test
        void cambiarOtrosCamposNoLaToca() {
            Issue issue = issue(done, 1);
            Instant completedAt = Instant.parse("2026-10-20T15:00:00Z");
            issue.setCompletedAt(completedAt);
            UpdateIssueRequest priorityOnly = new UpdateIssueRequest(null, null, null, IssuePriority.HIGH, null,
                    null, null, null, null, null, null, null, null, null, null);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), priorityOnly);

            assertThat(issue.getCompletedAt()).isEqualTo(completedAt);
        }

    }

    /** {@code startedAt}: la primera vez que la tarea entra a un estado del grupo STARTED. */
    @Nested
    class StartedAt {

        private State inProgress;
        private State review;

        @BeforeEach
        void stubs() {
            inProgress = state("En curso", StateGroup.STARTED);
            review = state("En revisión", StateGroup.STARTED);
            lenient().when(lookup.user(author.getId())).thenReturn(author);
            lenient().when(issueRepository.saveAndFlush(any(Issue.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            for (State state : List.of(todo, inProgress, review, done)) {
                lenient().when(stateRepository.findByIdAndProjectId(state.getId(), project.getId()))
                        .thenReturn(Optional.of(state));
            }
        }

        private UpdateIssueRequest updateState(UUID stateId) {
            return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        void unaTareaCreadaEnCursoEmpiezaAlCrearse() {
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, inProgress.getId(), null, null, null,
                    null, null, null, null, null, null, null, null);

            issueService.create(SLUG, project.getId(), author.getId(), request);

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getStartedAt()).isNotNull();
        }

        @Test
        void seFijaLaPrimeraVezYNoCambiaAlVolverAEmpezar() {
            Issue issue = issue(todo, 1);
            Instant before = Instant.now();

            issueService.move(SLUG, project.getId(), issue.getId(), author.getId(),
                    new MoveIssueRequest(inProgress.getId(), null, null));
            Instant startedAt = issue.getStartedAt();
            assertThat(startedAt).isNotNull().isAfterOrEqualTo(before);

            // Pasar a otro estado STARTED, volver a sin empezar y empezar otra vez no la cambia
            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), updateState(review.getId()));
            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), updateState(todo.getId()));
            assertThat(issue.getStartedAt()).isEqualTo(startedAt);
            issueService.move(SLUG, project.getId(), issue.getId(), author.getId(),
                    new MoveIssueRequest(inProgress.getId(), null, null));
            assertThat(issue.getStartedAt()).isEqualTo(startedAt);
        }

        @Test
        void completarSinPasarPorEnCursoNoLaFija() {
            Issue issue = issue(todo, 1);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), updateState(done.getId()));

            assertThat(issue.getCompletedAt()).isNotNull();
            assertThat(issue.getStartedAt()).isNull();
        }
    }

    /** Completar una tarea que bloquea a otras avisa a las que quedan desbloqueadas. */
    @Nested
    class Unblock {

        private State cancelled;

        @BeforeEach
        void stubs() {
            cancelled = state("Cancelled", StateGroup.CANCELLED);
            lenient().when(lookup.user(author.getId())).thenReturn(author);
            lenient().when(issueRepository.saveAndFlush(any(Issue.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            for (State state : List.of(todo, done, cancelled)) {
                lenient().when(stateRepository.findByIdAndProjectId(state.getId(), project.getId()))
                        .thenReturn(Optional.of(state));
            }
        }

        private UpdateIssueRequest updateState(UUID stateId) {
            return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        void alMoverAUnEstadoCompletadoAvisaALasTareasQueBloqueaba() {
            Issue blocker = issue(todo, 1);

            issueService.move(SLUG, project.getId(), blocker.getId(), author.getId(),
                    new MoveIssueRequest(done.getId(), null, null));

            verify(relationService).notifyUnblocked(blocker, author);
        }

        @Test
        void alEditarElEstadoTambienAvisa() {
            Issue blocker = issue(todo, 1);

            issueService.update(SLUG, project.getId(), blocker.getId(), author.getId(), updateState(done.getId()));

            verify(relationService).notifyUnblocked(blocker, author);
        }

        @Test
        void moverEntreEstadosCompletadosOCancelarNoAvisa() {
            State deployed = state("Deployed", StateGroup.COMPLETED);
            when(stateRepository.findByIdAndProjectId(deployed.getId(), project.getId()))
                    .thenReturn(Optional.of(deployed));
            Issue completed = issue(done, 1);
            Issue open = issue(todo, 2);

            issueService.move(SLUG, project.getId(), completed.getId(), author.getId(),
                    new MoveIssueRequest(deployed.getId(), null, null));
            issueService.update(SLUG, project.getId(), open.getId(), author.getId(), updateState(cancelled.getId()));

            verify(relationService, never()).notifyUnblocked(any(), any());
        }
    }

    @Nested
    class Delete {

        private User creator;
        private Issue issue;

        @BeforeEach
        void setUpIssue() {
            creator = new User();
            creator.setId(UUID.randomUUID());
            issue = issue(todo, 1);
            issue.setCreatedBy(creator);
        }

        @Test
        void quienCreoLaTareaPuedeEliminarla() {
            issueService.delete(SLUG, project.getId(), issue.getId(), creator.getId());

            verify(issueRepository).delete(issue);
            verifyNoInteractions(workspaceMemberRepository);
        }

        @Test
        void elProjectManagerPuedeEliminarCualquierTarea() {
            UUID pmId = UUID.randomUUID();
            when(workspaceMemberRepository.findRole(SLUG, pmId)).thenReturn(Optional.of(WorkspaceRole.OWNER));

            issueService.delete(SLUG, project.getId(), issue.getId(), pmId);

            verify(issueRepository).delete(issue);
        }

        @Test
        void otroMiembroRecibe403() {
            UUID memberId = UUID.randomUUID();
            when(workspaceMemberRepository.findRole(SLUG, memberId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));

            assertThatThrownBy(() -> issueService.delete(SLUG, project.getId(), issue.getId(), memberId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager o quien creó la tarea puede eliminarla");
            verify(issueRepository, never()).delete(any(Issue.class));
        }

        @Test
        void unaTareaSinCreadorSoloLaEliminaElProjectManager() {
            issue.setCreatedBy(null);
            UUID memberId = UUID.randomUUID();
            when(workspaceMemberRepository.findRole(SLUG, memberId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));

            assertThatThrownBy(() -> issueService.delete(SLUG, project.getId(), issue.getId(), memberId))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class Listado {

        @Test
        @SuppressWarnings("unchecked")
        void porPrioridadDesempataPorFechaLimiteYNumero() {
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(issueRepository.findAll(any(Specification.class), pageable.capture()))
                    .thenReturn(Page.empty());

            issueService.getIssues(SLUG, project.getId(), new IssueFilter(null, null, null, null, null, null, null, null, null),
                    PageRequest.of(1, 50, Sort.by(Sort.Direction.ASC, "priorityRank")));

            assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
            assertThat(pageable.getValue().getSort().stream().map(Sort.Order::getProperty))
                    .containsExactly("priorityRank", "dueDate", "sequenceId");
        }

        @Test
        @SuppressWarnings("unchecked")
        void cualquierOrdenTerminaPorNumero() {
            Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "dueDate"));
            Pageable expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "dueDate", "sequenceId"));
            when(issueRepository.findAll(any(Specification.class), eq(expected)))
                    .thenReturn(new PageImpl<>(List.of(), expected, 0));

            issueService.getIssues(SLUG, project.getId(), new IssueFilter(null, null, null, null, null, null, null, null, null), requested);

            verify(issueRepository).findAll(any(Specification.class), eq(expected));
        }
    }

    @Nested
    class Update {

        @Test
        void noPermiteQueUnIssueSeaPadreDeSuAncestro() {
            Issue parent = issue(todo, 1);
            Issue child = issue(todo, 2);
            child.setParent(parent);
            UpdateIssueRequest request = new UpdateIssueRequest(null, null, null, null, child.getId(),
                    null, null, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), parent.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class);
        }

        private UpdateIssueRequest description(String description) {
            return description(description, null);
        }

        private UpdateIssueRequest description(String description, Integer baseVersion) {
            return new UpdateIssueRequest(null, description, null, null, null, null, null, null, null, null, null, null,
                    baseVersion, null, null);
        }

        @Test
        void cambiarLaDescripcionLaRegistraComoUnaEdicion() {
            Issue issue = issue(todo, 1);
            issue.setDescription("## Antes");
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("## Después"));

            // Una entrada por sesión de edición (la agrupación la hace el ActivityService), sin el texto
            verify(activityService).recordDescriptionEdit(issue, author);
            verify(activityService, never()).record(any(), any(), eq(ActivityField.DESCRIPTION), any(), any());
            assertThat(issue.getDescription()).isEqualTo("## Después");
        }

        @Test
        void guardarLaMismaDescripcionNoDejaHistorial() {
            Issue issue = issue(todo, 1);
            issue.setDescription("## Igual");
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("  ## Igual  "));

            verify(activityService, never()).recordDescriptionEdit(any(), any());
            verify(notificationService, never()).notifyMentionedInDescription(any(), any(), anyCollection());
        }

        @Test
        void avisaSoloALosMencionadosPorPrimeraVez() {
            Workspace workspace = new Workspace();
            workspace.setId(UUID.randomUUID());
            project.setWorkspace(workspace);
            UUID ana = UUID.randomUUID();
            UUID luis = UUID.randomUUID();
            Issue issue = issue(todo, 1);
            issue.setDescription("Con @[Ana](" + ana + ")");
            User luisUser = new User();
            luisUser.setId(luis);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);
            when(userRepository.findMentionable(Set.of(luis), project.getId(), workspace.getId()))
                    .thenReturn(List.of(luisUser));

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(),
                    description("Con @[Ana](" + ana + ") y @[Luis](" + luis + ")"));

            verify(notificationService).notifyMentionedInDescription(issue, author, List.of(luisUser));
        }

        @Test
        void cambiarLaDescripcionSubeSuVersionYGuardaQuienYCuando() {
            Issue issue = issue(todo, 1);
            issue.setDescription("Antes");
            issue.setDescriptionVersion(4);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);
            Instant before = Instant.now();

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("Después", 4));

            assertThat(issue.getDescription()).isEqualTo("Después");
            assertThat(issue.getDescriptionVersion()).isEqualTo(5);
            assertThat(issue.getDescriptionUpdatedBy()).isSameAs(author);
            assertThat(issue.getDescriptionUpdatedAt()).isAfterOrEqualTo(before);
        }

        @Test
        void bloqueaLaFilaAntesDeLeerLaTareaCuandoCambiaLaDescripcion() {
            Issue issue = issue(todo, 1);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("Nueva", 0));

            InOrder order = inOrder(issueRepository);
            order.verify(issueRepository).lockById(issue.getId());
            order.verify(issueRepository).findByIdAndProjectId(issue.getId(), project.getId());
        }

        @Test
        void siOtraPersonaLaCambioDespuesDeLaVersionBaseResponde409SinCambiarNada() {
            Issue issue = issue(todo, 1);
            issue.setDescription("Lo que guardó Luis");
            issue.setDescriptionVersion(6);
            issue.setPriority(IssuePriority.LOW);
            UpdateIssueRequest mine = new UpdateIssueRequest(null, "Lo mío", null, IssuePriority.HIGH, null, null,
                    null, null, null, null, null, null, 5, null, null);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), mine))
                    .isInstanceOf(DescriptionConflictException.class)
                    .hasMessage(DescriptionConflictException.MESSAGE);

            // La respuesta lleva la tarea como está (el mapper la arma antes de lanzar) y nada cambió
            verify(issueMapper).toResponse(issue);
            assertThat(issue.getDescription()).isEqualTo("Lo que guardó Luis");
            assertThat(issue.getDescriptionVersion()).isEqualTo(6);
            assertThat(issue.getPriority()).isEqualTo(IssuePriority.LOW);
            verify(issueRepository, never()).saveAndFlush(any());
            verifyNoInteractions(activityService, notificationService);
        }

        @Test
        void sinVersionBaseGanaElUltimoQueGuarda() {
            Issue issue = issue(todo, 1);
            issue.setDescription("Lo que guardó Luis");
            issue.setDescriptionVersion(6);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("Lo mío"));

            assertThat(issue.getDescription()).isEqualTo("Lo mío");
            assertThat(issue.getDescriptionVersion()).isEqualTo(7);
        }

        @Test
        void conUnaVersionViejaPeroElMismoTextoNoHayConflicto() {
            // Las dos personas dejaron el mismo texto: no hay nada que pisar
            Issue issue = issue(todo, 1);
            issue.setDescription("Igual");
            issue.setDescriptionVersion(6);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), description("Igual", 2));

            assertThat(issue.getDescriptionVersion()).isEqualTo(6);
            verifyNoInteractions(activityService);
        }

        @Test
        void cambiarOtrosCamposNoSubeLaVersionDeLaDescripcion() {
            Issue issue = issue(todo, 1);
            issue.setDescription("Texto");
            issue.setDescriptionVersion(3);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);
            UpdateIssueRequest priorityOnly = new UpdateIssueRequest(null, null, null, IssuePriority.HIGH, null,
                    null, null, null, null, null, null, null, 1, null, null);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), priorityOnly);

            // Sin descripción, la versión base no se mira; la fila se bloquea igual (ningún cambio guarda una copia
            // vieja de la tarea encima de otro)
            assertThat(issue.getDescriptionVersion()).isEqualTo(3);
            assertThat(issue.getPriority()).isEqualTo(IssuePriority.HIGH);
            verify(issueRepository).lockById(issue.getId());
        }

        @Test
        void marcarUnaCasillaLaRegistraSinEditoLaDescripcion() {
            Issue issue = issue(todo, 1);
            issue.setDescription(String.join("\n", "## Pasos", "- [ ] Plantilla del correo", "- [ ] Enviar"));
            issue.setDescriptionVersion(2);
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(),
                    description(String.join("\n", "## Pasos", "- [x] Plantilla del correo", "- [ ] Enviar"), 2));

            verify(activityService).recordDescriptionTask(issue, author, "Plantilla del correo", true);
            verify(activityService, never()).recordDescriptionEdit(any(), any());
            assertThat(issue.getDescriptionVersion()).isEqualTo(3);
        }

        @Test
        void editarYMarcarAlaVezRegistraLasDosCosas() {
            Issue issue = issue(todo, 1);
            issue.setDescription(String.join("\n", "- [x] Plantilla", "- [ ] Enviar"));
            when(lookup.user(author.getId())).thenReturn(author);
            when(issueRepository.saveAndFlush(issue)).thenReturn(issue);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(),
                    description(String.join("\n", "Intro", "", "- [ ] Plantilla", "- [ ] Enviar"), 0));

            verify(activityService).recordDescriptionEdit(issue, author);
            verify(activityService).recordDescriptionTask(issue, author, "Plantilla", false);
        }
    }
}
