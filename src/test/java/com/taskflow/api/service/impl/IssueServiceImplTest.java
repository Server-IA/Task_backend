package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import com.taskflow.api.service.ActivityService;
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
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;

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
        return new CreateIssueRequest(title, null, null, null, null, null, null, null, null);
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
                    List.of(outsider.getId()), null);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("miembros del proyecto");
            verify(issueRepository, never()).save(any());
        }

        @Test
        void rechazaFechaLimiteAnteriorAlInicio() {
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, null, null, null,
                    LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10), null, null);

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
            return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null);
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
                    null, null);

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
                    null, null, null, null, null, null, null);

            issueService.update(SLUG, project.getId(), issue.getId(), author.getId(), priorityOnly);

            assertThat(issue.getCompletedAt()).isEqualTo(completedAt);
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
    class Update {

        @Test
        void noPermiteQueUnIssueSeaPadreDeSuAncestro() {
            Issue parent = issue(todo, 1);
            Issue child = issue(todo, 2);
            child.setParent(parent);
            UpdateIssueRequest request = new UpdateIssueRequest(null, null, null, null, child.getId(),
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), parent.getId(), author.getId(), request))
                    .isInstanceOf(BadRequestException.class);
        }
    }
}
