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
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
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
