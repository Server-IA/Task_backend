package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.MoveIssueRequest;
import com.taskflow.api.dto.request.SplitItemRequest;
import com.taskflow.api.dto.request.SplitStoryRequest;
import com.taskflow.api.dto.request.UpdateIssueRequest;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueTemplate;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.ApiException;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
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
import com.taskflow.api.service.IssueChecklistService;
import com.taskflow.api.service.IssueRelationService;
import com.taskflow.api.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las reglas de las historias en {@link IssueServiceImpl}: cerrar y reabrir solo el project manager (por el selector
 * de estado, el tablero o los endpoints), tareas en una historia cerrada, cambios de tipo y «Partir en tareas».
 */
@ExtendWith(MockitoExtension.class)
class IssueServiceStoryTest {

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

    @InjectMocks
    private IssueServiceImpl issueService;

    private Project project;
    private State backlog;
    private State todo;
    private State inProgress;
    private State done;
    private State cancelled;
    private User pm;
    private User member;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setSlug(SLUG);
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        project.setWorkspace(workspace);
        backlog = state("Backlog", StateGroup.BACKLOG);
        todo = state("Todo", StateGroup.UNSTARTED);
        inProgress = state("In Progress", StateGroup.STARTED);
        done = state("Done", StateGroup.COMPLETED);
        cancelled = state("Cancelled", StateGroup.CANCELLED);
        pm = user("Ana Navarro");
        member = user("Luis Muñoz Pérez");
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(pm.getId())).thenReturn(pm);
        lenient().when(lookup.user(member.getId())).thenReturn(member);
        lenient().when(workspaceMemberRepository.findRole(SLUG, pm.getId())).thenReturn(Optional.of(WorkspaceRole.OWNER));
        lenient().when(workspaceMemberRepository.findRole(SLUG, member.getId()))
                .thenReturn(Optional.of(WorkspaceRole.MEMBER));
        lenient().when(issueRepository.saveAndFlush(any(Issue.class))).thenAnswer(invocation -> invocation.getArgument(0));
        for (State state : List.of(backlog, todo, inProgress, done, cancelled)) {
            lenient().when(stateRepository.findByIdAndProjectId(state.getId(), project.getId()))
                    .thenReturn(Optional.of(state));
        }
        lenient().when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                StateGroup.STARTED)).thenReturn(Optional.of(inProgress));
        lenient().when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                StateGroup.COMPLETED)).thenReturn(Optional.of(done));
        lenient().when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                StateGroup.UNSTARTED)).thenReturn(Optional.of(todo));
        lenient().when(stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId()))
                .thenReturn(Optional.of(backlog));
    }

    private State state(String name, StateGroup group) {
        State state = new State(project, name, "#000000", group, 0);
        state.setId(UUID.randomUUID());
        return state;
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private int sequence = 10;

    private Issue issue(IssueType type, State state) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setType(type);
        issue.setState(state);
        issue.setSequenceId(++sequence);
        issue.setTitle("Tarea " + sequence);
        lenient().when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId()))
                .thenReturn(Optional.of(issue));
        return issue;
    }

    private Issue closedStory() {
        Issue story = issue(IssueType.STORY, done);
        story.setClosedAt(Instant.parse("2026-11-12T10:00:00Z"));
        story.setClosedBy(pm);
        return story;
    }

    private static UpdateIssueRequest updateState(UUID stateId) {
        return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    private static UpdateIssueRequest updateType(IssueType type) {
        return new UpdateIssueRequest(null, null, null, null, null, null, null, null, null, null, null, null, null,
                type, null);
    }

    private static UpdateIssueRequest updateParent(UUID parentId, Boolean reopen) {
        return new UpdateIssueRequest(null, null, null, null, parentId, null, null, null, null, null, null, null, null,
                null, reopen);
    }

    private static CreateIssueRequest create(String title, IssueType type, UUID parentId, Boolean reopen) {
        return new CreateIssueRequest(title, null, null, null, parentId, null, null, null, null, null, null, type,
                reopen, null);
    }

    @Nested
    class CerrarYReabrir {

        @Test
        void unMiembroNoCierraLaHistoriaDesdeElSelectorDeEstado() {
            Issue story = issue(IssueType.STORY, inProgress);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), story.getId(), member.getId(),
                    updateState(done.getId())))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager cierra la historia");
            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), story.getId(), member.getId(),
                    updateState(cancelled.getId())))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void unMiembroNoCierraLaHistoriaArrastrandoEnElTablero() {
            Issue story = issue(IssueType.STORY, inProgress);

            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), story.getId(), member.getId(),
                    new MoveIssueRequest(done.getId(), null, null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager cierra la historia");
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void unMiembroNoReabreUnaHistoriaCerrada() {
            Issue story = closedStory();

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), story.getId(), member.getId(),
                    updateState(inProgress.getId())))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager reabre la historia");
            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), story.getId(), member.getId(),
                    new MoveIssueRequest(todo.getId(), null, null)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void unMiembroSiMueveLaHistoriaEntreEstadosAbiertosYCualquierTareaASusEstadosCerrados() {
            Issue story = issue(IssueType.STORY, todo);
            Issue task = issue(IssueType.TASK, inProgress);

            issueService.update(SLUG, project.getId(), story.getId(), member.getId(), updateState(inProgress.getId()));
            issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    new MoveIssueRequest(done.getId(), null, null));

            assertThat(story.getState()).isSameAs(inProgress);
            assertThat(task.getState()).isSameAs(done);
        }

        @Test
        void elProjectManagerLaCierraDesdeElSelectorYQuedaQuienYCuando() {
            Issue story = issue(IssueType.STORY, inProgress);
            Instant before = Instant.now();

            issueService.update(SLUG, project.getId(), story.getId(), pm.getId(), updateState(done.getId()));

            assertThat(story.getState()).isSameAs(done);
            assertThat(story.getClosedBy()).isSameAs(pm);
            assertThat(story.getClosedAt()).isAfterOrEqualTo(before);
        }

        @Test
        void cerrarHistoriaLaPasaAlPrimerEstadoCompletado() {
            Issue story = issue(IssueType.STORY, inProgress);

            issueService.closeStory(SLUG, project.getId(), story.getId(), pm.getId());

            assertThat(story.getState()).isSameAs(done);
            assertThat(story.getClosedBy()).isSameAs(pm);
            assertThat(story.getClosedAt()).isNotNull();
            assertThat(story.getCompletedAt()).isNotNull();
            verify(activityService).record(story, pm, ActivityField.STATE, "In Progress", "Done");
            verify(relationService).notifyUnblocked(story, pm);
        }

        @Test
        void cerrarAlgoQueNoEsHistoriaOUnaYaCerradaFalla() {
            Issue task = issue(IssueType.TASK, inProgress);
            Issue story = closedStory();

            assertThatThrownBy(() -> issueService.closeStory(SLUG, project.getId(), task.getId(), pm.getId()))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> issueService.closeStory(SLUG, project.getId(), story.getId(), pm.getId()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void reabrirLaVuelveAEnCursoYBorraQuienLaCerro() {
            Issue story = closedStory();

            issueService.reopenStory(SLUG, project.getId(), story.getId(), pm.getId());

            assertThat(story.getState()).isSameAs(inProgress);
            assertThat(story.getClosedAt()).isNull();
            assertThat(story.getClosedBy()).isNull();
            assertThat(story.getCompletedAt()).isNull();
            verify(activityService).record(story, pm, ActivityField.STATE, "Done", "In Progress");
        }

        @Test
        void reabrirUnaHistoriaAbiertaFalla() {
            Issue story = issue(IssueType.STORY, inProgress);

            assertThatThrownBy(() -> issueService.reopenStory(SLUG, project.getId(), story.getId(), pm.getId()))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class HistoriaCerrada {

        @BeforeEach
        void creation() {
            lenient().when(stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId()))
                    .thenReturn(Optional.of(todo));
        }

        @Test
        void agregarUnaTareaSinReabrirlaResponde409ConCodigo() {
            Issue story = closedStory();

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), pm.getId(),
                    create("Mostrar la fecha de alta", IssueType.TASK, story.getId(), null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(story.getCode() + " está cerrada")
                    .extracting(error -> ((ApiException) error).getCode())
                    .isEqualTo(ConflictException.STORY_CLOSED);
            verify(issueRepository, never()).save(any(Issue.class));
        }

        @Test
        void unMiembroNoPuedeReabrirlaParaAgregar() {
            Issue story = closedStory();

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), member.getId(),
                    create("Mostrar la fecha de alta", IssueType.TASK, story.getId(), true)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager reabre la historia");
            assertThat(story.getState()).isSameAs(done);
        }

        @Test
        void elProjectManagerLaReabreYAgregaLaTarea() {
            Issue story = closedStory();

            issueService.create(SLUG, project.getId(), pm.getId(),
                    create("Mostrar la fecha de alta", IssueType.TASK, story.getId(), true));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getParent()).isSameAs(story);
            assertThat(story.getState()).isSameAs(inProgress);
            assertThat(story.getClosedAt()).isNull();
            verify(activityService).record(story, pm, ActivityField.STATE, "Done", "In Progress");
            verify(storyProgress).childChanged(saved.getValue(), null, pm, false);
        }

        @Test
        void unBugSeLigaSinReabrirla() {
            Issue story = closedStory();

            issueService.create(SLUG, project.getId(), member.getId(),
                    create("La foto nueva no aparece", IssueType.BUG, story.getId(), null));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getParent()).isSameAs(story);
            assertThat(saved.getValue().getType()).isEqualTo(IssueType.BUG);
            assertThat(story.getState()).isSameAs(done);
        }

        @Test
        void moverUnaTareaAUnaHistoriaCerradaTambienPideReabrirla() {
            Issue story = closedStory();
            Issue task = issue(IssueType.TASK, todo);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), task.getId(), pm.getId(),
                    updateParent(story.getId(), null)))
                    .isInstanceOf(ConflictException.class);

            issueService.update(SLUG, project.getId(), task.getId(), pm.getId(), updateParent(story.getId(), true));
            assertThat(task.getParent()).isSameAs(story);
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void laTareaSueltaRelacionadaSeCreaEnLaMismaTransaccion() {
            Issue story = closedStory();
            CreateIssueRequest request = new CreateIssueRequest("Mostrar la fecha de alta", null, null, null, null,
                    null, null, null, null, null, null, IssueType.TASK, null, story.getId());

            issueService.create(SLUG, project.getId(), member.getId(), request);

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getParent()).isNull();
            verify(relationService).create(eq(SLUG), eq(project.getId()), eq(saved.getValue().getId()),
                    eq(member.getId()), any());
        }
    }

    @Nested
    class Tipos {

        @Test
        void unMiembroNoCreaHistorias() {
            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), member.getId(),
                    create("Pantalla de perfil", IssueType.STORY, null, null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager crea historias");
        }

        @Test
        void elProjectManagerCreaHistorias() {
            when(stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId()))
                    .thenReturn(Optional.of(backlog));

            issueService.create(SLUG, project.getId(), pm.getId(),
                    create("Pantalla de perfil", IssueType.STORY, null, null));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getType()).isEqualTo(IssueType.STORY);
        }

        @Test
        void sinTipoEsUnaTarea() {
            when(stateRepository.findFirstByProjectIdOrderByPositionAsc(project.getId()))
                    .thenReturn(Optional.of(backlog));

            issueService.create(SLUG, project.getId(), member.getId(), create("Optimizar imágenes", null, null, null));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getType()).isEqualTo(IssueType.TASK);
        }

        @Test
        void unaHistoriaNoPuedeSerHijaDeOtraTarea() {
            Issue parent = issue(IssueType.STORY, todo);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), pm.getId(),
                    create("Otra historia", IssueType.STORY, parent.getId(), null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void unaHistoriaConTareasNoPuedeCambiarDeTipo() {
            Issue story = issue(IssueType.STORY, inProgress);
            when(issueRepository.existsByParentId(story.getId())).thenReturn(true);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), story.getId(), pm.getId(),
                    updateType(IssueType.TASK)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Una historia con tareas no puede cambiar de tipo hasta mover sus tareas");
            assertThat(story.getType()).isEqualTo(IssueType.STORY);
        }

        @Test
        void unaHistoriaSinTareasSiPuedeSerTareaYQuedaEnElHistorial() {
            Issue story = issue(IssueType.STORY, todo);
            when(issueRepository.existsByParentId(story.getId())).thenReturn(false);

            issueService.update(SLUG, project.getId(), story.getId(), pm.getId(), updateType(IssueType.TASK));

            assertThat(story.getType()).isEqualTo(IssueType.TASK);
            verify(activityService).record(story, pm, ActivityField.TYPE, "STORY", "TASK");
        }

        @Test
        void unMiembroNoConvierteEnHistoriaNiDejaDeSerlo() {
            Issue task = issue(IssueType.TASK, todo);
            Issue story = issue(IssueType.STORY, todo);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), task.getId(), member.getId(),
                    updateType(IssueType.STORY)))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), story.getId(), member.getId(),
                    updateType(IssueType.BUG)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void unMiembroSiCambiaEntreTareaYBug() {
            Issue task = issue(IssueType.TASK, todo);
            task.setDescription("Ya tiene descripción");

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(), updateType(IssueType.BUG));

            assertThat(task.getType()).isEqualTo(IssueType.BUG);
            assertThat(task.getDescription()).isEqualTo("Ya tiene descripción");
        }

        @Test
        void alPasarABugConLaDescripcionVaciaAgregaLasSeccionesDelReporte() {
            Issue task = issue(IssueType.TASK, todo);
            IssueTemplate bugTemplate = new IssueTemplate();
            bugTemplate.setDescription("## Pasos para reproducir\n1.");
            when(templateRepository.findFirstByProjectIdAndTypeOrderByPositionAscCreatedAtAsc(project.getId(),
                    IssueType.BUG)).thenReturn(Optional.of(bugTemplate));

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(), updateType(IssueType.BUG));

            assertThat(task.getDescription()).isEqualTo("## Pasos para reproducir\n1.");
            assertThat(task.getDescriptionVersion()).isEqualTo(1);
        }

        @Test
        void cambiarDeTipoAvisaASuHistoria() {
            Issue story = issue(IssueType.STORY, inProgress);
            Issue task = issue(IssueType.TASK, inProgress);
            task.setParent(story);
            task.setDescription("Algo");

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(), updateType(IssueType.BUG));

            verify(storyProgress).childChanged(task, story, member, false);
        }
    }

    @Nested
    class AvanceDeLaHistoria {

        @Test
        void moverUnaTareaAEnCursoAvisaQueEmpezo() {
            Issue story = issue(IssueType.STORY, todo);
            Issue task = issue(IssueType.TASK, todo);
            task.setParent(story);

            issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    new MoveIssueRequest(inProgress.getId(), null, null));

            verify(storyProgress).childChanged(task, null, member, true);
        }

        @Test
        void completarUnaTareaNoEsEmpezar() {
            Issue story = issue(IssueType.STORY, inProgress);
            Issue task = issue(IssueType.TASK, inProgress);
            task.setParent(story);

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(), updateState(done.getId()));

            verify(storyProgress).childChanged(task, story, member, false);
        }

        @Test
        void unaTareaSinPadreNoTocaHistorias() {
            Issue task = issue(IssueType.TASK, todo);

            issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    new MoveIssueRequest(inProgress.getId(), null, null));

            verify(storyProgress, never()).childChanged(any(), any(), any(), anyBoolean());
        }

        @Test
        void crearUnaTareaEnCursoDentroDeLaHistoriaCuentaComoEmpezar() {
            Issue story = issue(IssueType.STORY, todo);
            CreateIssueRequest request = new CreateIssueRequest("Tarea", null, inProgress.getId(), null,
                    story.getId(), null, null, null, null, null, null, IssueType.TASK, null, null);

            issueService.create(SLUG, project.getId(), member.getId(), request);

            verify(storyProgress).childChanged(any(Issue.class), isNull(), eq(member), eq(true));
        }

        @Test
        void eliminarUnaHijaRevisaLaHistoria() {
            Issue story = issue(IssueType.STORY, inProgress);
            Issue bug = issue(IssueType.BUG, inProgress);
            bug.setParent(story);
            bug.setCreatedBy(member);

            issueService.delete(SLUG, project.getId(), bug.getId(), member.getId());

            verify(issueRepository).delete(bug);
            verify(storyProgress).reconcile(story, member, null, false);
        }
    }

    /**
     * Una tarea de una historia cerrada no vuelve a ser trabajo abierto sin que el project manager reabra la historia
     * (409 {@code STORY_CLOSED}, o {@code reopenParent} y PM), por cualquier camino.
     */
    @Nested
    class TrabajoAbiertoEnUnaHistoriaCerrada {

        private Issue story;

        @BeforeEach
        void closed() {
            story = closedStory();
        }

        private Issue child(IssueType type, State state) {
            Issue child = issue(type, state);
            child.setParent(story);
            return child;
        }

        private static MoveIssueRequest moveTo(UUID stateId, Boolean reopenParent) {
            return new MoveIssueRequest(stateId, null, null, reopenParent);
        }

        private static UpdateIssueRequest stateWithReopen(UUID stateId, Boolean reopenParent) {
            return new UpdateIssueRequest(null, null, stateId, null, null, null, null, null, null, null, null, null,
                    null, null, reopenParent);
        }

        @Test
        void sacarUnaTareaTerminadaDeHechoPorElTableroResponde409ConCodigo() {
            Issue task = child(IssueType.TASK, done);

            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    moveTo(todo.getId(), null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(story.getCode() + " está cerrada. Reábrela para volver a abrir esta tarea")
                    .extracting(error -> ((ApiException) error).getCode())
                    .isEqualTo(ConflictException.STORY_CLOSED);
            assertThat(task.getState()).isSameAs(done);
            verify(issueRepository, never()).saveAndFlush(any());
        }

        @Test
        void sacarUnaTareaCanceladaPorElSelectorDeEstadoTambien() {
            Issue task = child(IssueType.TASK, cancelled);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), task.getId(), member.getId(),
                    updateState(inProgress.getId())))
                    .isInstanceOf(ConflictException.class)
                    .extracting(error -> ((ApiException) error).getCode())
                    .isEqualTo(ConflictException.STORY_CLOSED);
        }

        @Test
        void unMiembroNoPuedeReabrirlaAunqueMandeReopenParent() {
            Issue task = child(IssueType.TASK, done);

            assertThatThrownBy(() -> issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    moveTo(todo.getId(), true)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el project manager reabre la historia");
            assertThat(story.getState()).isSameAs(done);
        }

        @Test
        void elProjectManagerLaReabreAlMoverConReopenParent() {
            Issue task = child(IssueType.TASK, done);

            issueService.move(SLUG, project.getId(), task.getId(), pm.getId(), moveTo(todo.getId(), true));

            assertThat(task.getState()).isSameAs(todo);
            assertThat(story.getState()).isSameAs(inProgress);
            assertThat(story.getClosedAt()).isNull();
            verify(activityService).record(story, pm, ActivityField.STATE, "Done", "In Progress");
        }

        @Test
        void elProjectManagerLaReabreAlEditarConReopenParent() {
            Issue task = child(IssueType.TASK, done);

            issueService.update(SLUG, project.getId(), task.getId(), pm.getId(),
                    stateWithReopen(inProgress.getId(), true));

            assertThat(task.getState()).isSameAs(inProgress);
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void entreEstadosCerradosOSiendoBugNoHaceFaltaReabrirla() {
            Issue task = child(IssueType.TASK, done);
            Issue bug = child(IssueType.BUG, done);

            issueService.move(SLUG, project.getId(), task.getId(), member.getId(), moveTo(cancelled.getId(), null));
            // «Bug después de cerrar»: se reabre sin tocar la historia
            issueService.move(SLUG, project.getId(), bug.getId(), member.getId(), moveTo(todo.getId(), null));

            assertThat(task.getState()).isSameAs(cancelled);
            assertThat(bug.getState()).isSameAs(todo);
            assertThat(story.getState()).isSameAs(done);
        }

        @Test
        void convertirEnTareaUnBugDeLaHistoriaCerradaPideReabrirla() {
            Issue bug = child(IssueType.BUG, todo);

            assertThatThrownBy(() -> issueService.update(SLUG, project.getId(), bug.getId(), member.getId(),
                    updateType(IssueType.TASK)))
                    .isInstanceOf(ConflictException.class)
                    .extracting(error -> ((ApiException) error).getCode())
                    .isEqualTo(ConflictException.STORY_CLOSED);

            // (sin base de datos el primer intento no se deshace: otro bug para el project manager)
            Issue other = child(IssueType.BUG, todo);
            issueService.update(SLUG, project.getId(), other.getId(), pm.getId(),
                    new UpdateIssueRequest(null, null, null, null, null, null, null, null, null, null, null, null,
                            null, IssueType.TASK, true));
            assertThat(other.getType()).isEqualTo(IssueType.TASK);
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void editarOtrosCamposDeUnaTareaDeLaHistoriaCerradaNoPideNada() {
            Issue task = child(IssueType.TASK, done);

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(),
                    new UpdateIssueRequest("Nuevo título", null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null));

            assertThat(task.getTitle()).isEqualTo("Nuevo título");
            assertThat(story.getState()).isSameAs(done);
        }
    }

    @Nested
    class Revision {

        @Test
        void reabrirUnaHistoriaQueNoEstaListaVuelveAAvisarCuandoLoEste() {
            Issue story = closedStory();
            story.setReviewNotified(true);
            when(issueRepository.countChildrenOf(story.getId())).thenReturn(List.of(
                    new com.taskflow.api.repository.StoryChildCount(IssueType.TASK, StateGroup.UNSTARTED, 1)));

            issueService.reopenStory(SLUG, project.getId(), story.getId(), pm.getId());

            assertThat(story.isReviewNotified()).isFalse();
        }

        @Test
        void reabrirUnaHistoriaYaListaNoVuelveAAvisar() {
            Issue story = closedStory();
            when(issueRepository.countChildrenOf(story.getId())).thenReturn(List.of(
                    new com.taskflow.api.repository.StoryChildCount(IssueType.TASK, StateGroup.COMPLETED, 2)));

            issueService.reopenStory(SLUG, project.getId(), story.getId(), pm.getId());

            assertThat(story.isReviewNotified()).isTrue();
        }

        @Test
        void convertirEnHistoriaUnaTareaConHijasLaPoneAlDia() {
            Issue task = issue(IssueType.TASK, inProgress);

            issueService.update(SLUG, project.getId(), task.getId(), pm.getId(), updateType(IssueType.STORY));

            verify(storyProgress).reconcile(task, pm, null, false);
        }

        @Test
        void unaHistoriaQueQuedoConPadreSeSigueEditando() {
            // Datos viejos (V24): una historia con padre. V25 los limpia, pero editarla no debe fallar
            Issue parent = issue(IssueType.TASK, todo);
            Issue story = issue(IssueType.STORY, inProgress);
            story.setParent(parent);

            issueService.update(SLUG, project.getId(), story.getId(), member.getId(),
                    new UpdateIssueRequest("Otro título", null, null, IssuePriority.HIGH, null, null, null, null, null,
                            null, null, null, null, null, null));

            assertThat(story.getTitle()).isEqualTo("Otro título");
        }

        @Test
        void soloElProjectManagerEliminaUnaHistoriaAunqueLaHayaCreadoOtro() {
            Issue story = issue(IssueType.STORY, inProgress);
            story.setCreatedBy(member);

            assertThatThrownBy(() -> issueService.delete(SLUG, project.getId(), story.getId(), member.getId()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(StoryRules.DELETE_FORBIDDEN);
            verify(issueRepository, never()).delete(any(Issue.class));

            issueService.delete(SLUG, project.getId(), story.getId(), pm.getId());
            verify(issueRepository).delete(story);
        }
    }

    @Nested
    class Bloqueos {

        @Test
        void editarMoverCerrarReabrirYPartirBloqueanLaFilaAntesDeLeerla() {
            Issue task = issue(IssueType.TASK, todo);
            Issue story = issue(IssueType.STORY, inProgress);
            Issue closed = closedStory();

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(),
                    new UpdateIssueRequest(null, null, null, IssuePriority.HIGH, null, null, null, null, null, null,
                            null, null, null, null, null));
            issueService.move(SLUG, project.getId(), task.getId(), member.getId(),
                    new MoveIssueRequest(inProgress.getId(), null, null));
            issueService.split(SLUG, project.getId(), story.getId(), pm.getId(),
                    new SplitStoryRequest(List.of(new SplitItemRequest("Una", null, null, null)), null));
            issueService.closeStory(SLUG, project.getId(), story.getId(), pm.getId());
            issueService.reopenStory(SLUG, project.getId(), closed.getId(), pm.getId());

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(issueRepository);
            order.verify(issueRepository).lockById(task.getId());
            order.verify(issueRepository).findByIdAndProjectId(task.getId(), project.getId());
            verify(issueRepository, times(2)).lockById(task.getId());
            verify(issueRepository, times(2)).lockById(story.getId());
            verify(issueRepository).lockById(closed.getId());
        }

        @Test
        void laHistoriaSeBloqueaYSeVuelveALeerAntesDeVerSiEstaCerrada() {
            Issue story = closedStory();
            // Mientras se esperaba el bloqueo, el project manager la reabrió (/reopen): al releerla ya está abierta
            doAnswer(invocation -> {
                Issue locked = invocation.getArgument(0);
                locked.setState(inProgress);
                locked.setClosedAt(null);
                return null;
            }).when(storyProgress).lock(story);

            issueService.update(SLUG, project.getId(), issue(IssueType.TASK, todo).getId(), pm.getId(),
                    updateParent(story.getId(), true));

            // No la vuelve a reabrir: un solo «reabrió» en su historial (el del otro)
            verify(activityService, never()).record(eq(story), any(), eq(ActivityField.STATE), any(), any());
            assertThat(story.getState()).isSameAs(inProgress);
        }

        @Test
        void unaHistoriaQueSeCerroMientrasEsperabaPideReabrirla() {
            Issue story = issue(IssueType.STORY, inProgress);
            doAnswer(invocation -> {
                Issue locked = invocation.getArgument(0);
                locked.setState(done);
                return null;
            }).when(storyProgress).lock(story);

            assertThatThrownBy(() -> issueService.create(SLUG, project.getId(), member.getId(),
                    new CreateIssueRequest("Nueva", null, null, null, story.getId(), null, null, null, null, null, null,
                            IssueType.TASK, null, null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void cambiarDeHistoriaBloqueaLasDosAntesDeCambiarElPadre() {
            Issue from = issue(IssueType.STORY, inProgress);
            Issue to = issue(IssueType.STORY, inProgress);
            Issue task = issue(IssueType.TASK, todo);
            task.setParent(from);

            issueService.update(SLUG, project.getId(), task.getId(), member.getId(), updateParent(to.getId(), null));

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(storyProgress, issueRepository);
            order.verify(storyProgress).lockStories(from, to);
            order.verify(storyProgress).lock(to);
            order.verify(issueRepository).saveAndFlush(task);
            order.verify(storyProgress).childChanged(task, from, member, false);
        }

        @Test
        void eliminarUnaHistoriaBloqueaSusHijasAntes() {
            Issue story = issue(IssueType.STORY, inProgress);

            issueService.delete(SLUG, project.getId(), story.getId(), pm.getId());

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(issueRepository);
            order.verify(issueRepository).lockChildrenOf(story.getId());
            order.verify(issueRepository).delete(story);
        }
    }

    @Nested
    class Partir {

        private Issue story;
        private Label frontend;
        private Cycle sprint;
        private ProjectModule profile;

        @BeforeEach
        void story() {
            story = issue(IssueType.STORY, backlog);
            story.setPriority(IssuePriority.MEDIUM);
            frontend = new Label();
            frontend.setId(UUID.randomUUID());
            story.getLabels().add(frontend);
            sprint = new Cycle();
            sprint.setId(UUID.randomUUID());
            story.getCycles().add(sprint);
            profile = new ProjectModule();
            profile.setId(UUID.randomUUID());
            story.getModules().add(profile);
        }

        private SplitStoryRequest request(SplitItemRequest... items) {
            return new SplitStoryRequest(List.of(items), null);
        }

        @Test
        void creaTodasLasLineasComoHijasYHeredanCicloModuloEtiquetasYPrioridad() {
            when(projectRepository.findIssueCounter(project.getId())).thenReturn(41, 42);

            issueService.split(SLUG, project.getId(), story.getId(), pm.getId(), request(
                    new SplitItemRequest("  Validar el correo nuevo ", null, IssuePriority.HIGH, null),
                    new SplitItemRequest("El nombre no se actualiza", IssueType.BUG, null, null)));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository, times(2)).save(saved.capture());
            Issue task = saved.getAllValues().get(0);
            Issue bug = saved.getAllValues().get(1);
            assertThat(task.getTitle()).isEqualTo("Validar el correo nuevo");
            assertThat(task.getType()).isEqualTo(IssueType.TASK);
            assertThat(task.getPriority()).isEqualTo(IssuePriority.HIGH);
            assertThat(task.getSequenceId()).isEqualTo(41);
            assertThat(bug.getType()).isEqualTo(IssueType.BUG);
            assertThat(bug.getPriority()).isEqualTo(IssuePriority.MEDIUM);
            assertThat(bug.getSequenceId()).isEqualTo(42);
            for (Issue child : saved.getAllValues()) {
                assertThat(child.getParent()).isSameAs(story);
                assertThat(child.getState()).isSameAs(todo);
                assertThat(child.getLabels()).containsExactly(frontend);
                assertThat(child.getCycles()).containsExactly(sprint);
                assertThat(child.getModules()).containsExactly(profile);
                assertThat(child.getCreatedBy()).isSameAs(pm);
            }
            assertThat(sprint.getIssues()).containsExactlyInAnyOrderElementsOf(saved.getAllValues());
            assertThat(profile.getIssues()).containsExactlyInAnyOrderElementsOf(saved.getAllValues());
            verify(activityService, times(2)).record(any(Issue.class), eq(pm), eq(ActivityField.CREATED), isNull(),
                    any());
            verify(storyProgress).reconcile(story, pm, null, false);
        }

        @Test
        void unCicloCerradoNoSeHereda() {
            sprint.setClosedAt(Instant.now());
            when(projectRepository.findIssueCounter(project.getId())).thenReturn(41);

            issueService.split(SLUG, project.getId(), story.getId(), pm.getId(),
                    request(new SplitItemRequest("Tarea", null, null, null)));

            ArgumentCaptor<Issue> saved = ArgumentCaptor.forClass(Issue.class);
            verify(issueRepository).save(saved.capture());
            assertThat(saved.getValue().getCycles()).isEmpty();
        }

        @Test
        void soloSePartenHistoriasYSoloEnTareasOBugs() {
            Issue task = issue(IssueType.TASK, todo);

            assertThatThrownBy(() -> issueService.split(SLUG, project.getId(), task.getId(), pm.getId(),
                    request(new SplitItemRequest("Tarea", null, null, null))))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> issueService.split(SLUG, project.getId(), story.getId(), pm.getId(),
                    request(new SplitItemRequest("Otra historia", IssueType.STORY, null, null))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void unaHistoriaCerradaPideReabrirlaParaPartirla() {
            Issue closed = closedStory();

            assertThatThrownBy(() -> issueService.split(SLUG, project.getId(), closed.getId(), pm.getId(),
                    request(new SplitItemRequest("Tarea", null, null, null))))
                    .isInstanceOf(ConflictException.class);
            verify(issueRepository, never()).save(any(Issue.class));
        }
    }
}
