package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.RetroNoteRequest;
import com.taskflow.api.dto.request.UpdateRetroNoteRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.dto.response.RetroResponse;
import com.taskflow.api.dto.response.RetroVoteResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.RetroNote;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.RetroColumn;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.mapper.PlanningMapper;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.RetroNoteRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetroServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private CycleRepository cycleRepository;
    @Mock
    private RetroNoteRepository noteRepository;
    @Mock
    private StateRepository stateRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private IssueService issueService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ProjectAccess projectAccess;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private PlanningMapper mapper;

    @InjectMocks
    private RetroServiceImpl retroService;

    private final Instant now = Instant.now();
    private Workspace workspace;
    private Project project;
    private State done;
    private User member;
    private User otherMember;
    private User pm;
    private Cycle cycle;
    private final List<RetroNote> notes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        project.setWorkspace(workspace);
        done = new State(project, "Hecho", "#16A34A", StateGroup.COMPLETED, 3);
        member = user("Ana", false);
        otherMember = user("Beto", false);
        pm = user("Paula", true);
        cycle = cycle("Sprint 3");
        cycle.setRetroStartedAt(now);

        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(noteRepository.save(any(RetroNote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(noteRepository.saveAndFlush(any(RetroNote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(noteRepository.findAllByCycleId(cycle.getId())).thenReturn(notes);
        lenient().when(mapper.toRetroNoteResponse(any(RetroNote.class), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    RetroNote note = invocation.getArgument(0);
                    return new RetroNoteResponse(note.getId(), note.getColumn(), note.getText(), null, null, null,
                            invocation.getArgument(1), invocation.getArgument(2), null);
                });
    }

    private User user(String name, boolean manager) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        lenient().when(lookup.user(user.getId())).thenReturn(user);
        lenient().when(projectAccess.isManager(workspace, user.getId())).thenReturn(manager);
        return user;
    }

    private Cycle cycle(String name) {
        Cycle cycle = new Cycle();
        cycle.setId(UUID.randomUUID());
        cycle.setProject(project);
        cycle.setName(name);
        lenient().when(cycleRepository.findByIdAndProjectId(cycle.getId(), project.getId()))
                .thenReturn(Optional.of(cycle));
        lenient().when(cycleRepository.findForUpdate(cycle.getId(), project.getId())).thenReturn(Optional.of(cycle));
        return cycle;
    }

    private RetroNote note(Cycle cycle, RetroColumn column, User author, User... voters) {
        RetroNote note = new RetroNote(cycle, column, "Nota " + column, author);
        note.setId(UUID.randomUUID());
        note.getVoters().addAll(List.of(voters));
        lenient().when(noteRepository.findByIdAndCycleId(note.getId(), cycle.getId())).thenReturn(Optional.of(note));
        return note;
    }

    private RetroNote note(RetroColumn column, User author, User... voters) {
        RetroNote note = note(cycle, column, author, voters);
        notes.add(note);
        return note;
    }

    private Issue completedIssue(Duration createdAgo, Duration completedAgo) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setState(done);
        issue.setCreatedAt(now.minus(createdAgo));
        issue.setStartedAt(now.minus(createdAgo));
        issue.setCompletedAt(now.minus(completedAgo));
        return issue;
    }

    @Nested
    class GetRetro {

        @Test
        void sinEmpezarVieneVaciaPeroConElResumen() {
            cycle.setRetroStartedAt(null);
            cycle.getIssues().add(completedIssue(Duration.ofDays(3), Duration.ofDays(1)));
            Issue cancelledIssue = new Issue();
            cancelledIssue.setState(new State(project, "Cancelada", "#DC2626", StateGroup.CANCELLED, 4));
            cycle.getIssues().add(cancelledIssue);
            cycle.setCarriedToCycle(2);
            cycle.setCarriedToBacklog(1);
            when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(cycle));

            RetroResponse retro = retroService.getRetro(SLUG, project.getId(), cycle.getId(), member.getId());

            assertThat(retro.started()).isFalse();
            assertThat(retro.notes()).isEmpty();
            verify(noteRepository, never()).findAllByCycleId(cycle.getId());
            assertThat(retro.myVotesLeft()).isEqualTo(RetroServiceImpl.MAX_VOTES);
            // La cancelada no suma al total (como en el avance del ciclo): 1 completada + 2 + 1 que se movieron
            assertThat(retro.summary()).isEqualTo(new RetroResponse.RetroSummary(1, 4, 2, 1, 2.0));
            assertThat(retro.previousActions()).isEmpty();
        }

        @Test
        void unCicloCerradoUsaLaFotoDelCierreAunqueSusTareasCambienDespues() {
            cycle.setClosedAt(now);
            cycle.setClosedCompletedCount(10);
            cycle.setClosedTotalCount(12);
            cycle.setClosedCycleTimeDays(3.5);
            cycle.setCarriedToCycle(2);
            // Después del cierre una tarea se reabrió y pasó a otro ciclo: en el ciclo queda solo una
            cycle.getIssues().add(completedIssue(Duration.ofDays(3), Duration.ofDays(1)));
            when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(cycle));

            RetroResponse retro = retroService.getRetro(SLUG, project.getId(), cycle.getId(), member.getId());

            assertThat(retro.summary()).isEqualTo(new RetroResponse.RetroSummary(10, 12, 2, 0, 3.5));
        }

        @Test
        void lasHistoriasNoCuentanEnElResumen() {
            cycle.setRetroStartedAt(null);
            Issue story = completedIssue(Duration.ofDays(9), Duration.ofDays(1));
            story.setType(com.taskflow.api.entity.enums.IssueType.STORY);
            cycle.getIssues().add(story);
            cycle.getIssues().add(completedIssue(Duration.ofDays(3), Duration.ofDays(1)));
            when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(cycle));

            RetroResponse retro = retroService.getRetro(SLUG, project.getId(), cycle.getId(), member.getId());

            assertThat(retro.summary()).isEqualTo(new RetroResponse.RetroSummary(1, 1, 0, 0, 2.0));
        }

        @Test
        void traeLasNotasConSusVotosYLasAccionesDelCicloCerradoAnterior() {
            cycle.setClosedAt(now);
            RetroNote good = note(RetroColumn.WENT_WELL, otherMember, member, otherMember);
            RetroNote action = note(RetroColumn.ACTION, pm);
            Cycle previous = cycle("Sprint 2");
            previous.setClosedAt(now.minus(Duration.ofDays(14)));
            Cycle older = cycle("Sprint 1");
            older.setClosedAt(now.minus(Duration.ofDays(28)));
            Cycle laterClosed = cycle("Sprint 4");
            laterClosed.setClosedAt(now.plus(Duration.ofMinutes(5)));
            RetroNote previousAction = note(previous, RetroColumn.ACTION, pm);
            RetroNote previousImprove = note(previous, RetroColumn.IMPROVE, member);
            when(noteRepository.findAllByCycleId(previous.getId())).thenReturn(List.of(previousImprove, previousAction));
            when(cycleRepository.findAllByProjectId(project.getId()))
                    .thenReturn(List.of(laterClosed, cycle, previous, older));

            RetroResponse retro = retroService.getRetro(SLUG, project.getId(), cycle.getId(), member.getId());

            assertThat(retro.started()).isTrue();
            assertThat(retro.notes()).extracting(RetroNoteResponse::id).containsExactly(good.getId(), action.getId());
            assertThat(retro.notes().getFirst().votes()).isEqualTo(2);
            assertThat(retro.notes().getFirst().votedByMe()).isTrue();
            assertThat(retro.notes().get(1).votedByMe()).isFalse();
            assertThat(retro.myVotesLeft()).isEqualTo(4);
            assertThat(retro.previousActions()).extracting(RetroNoteResponse::id)
                    .containsExactly(previousAction.getId());
        }

        @Test
        void siElCicloSigueAbiertoLasAccionesPreviasSonLasDelUltimoCerrado() {
            Cycle previous = cycle("Sprint 2");
            previous.setClosedAt(now.minus(Duration.ofDays(1)));
            Cycle older = cycle("Sprint 1");
            older.setClosedAt(now.minus(Duration.ofDays(15)));

            assertThat(RetroServiceImpl.previousClosedCycle(cycle, List.of(older, cycle, previous))).isSameAs(previous);
            assertThat(RetroServiceImpl.previousClosedCycle(older, List.of(older, cycle, previous))).isNull();
        }
    }

    @Nested
    class Notes {

        @Test
        void cualquieraAgregaUnaNotaConElTextoRecortado() {
            RetroNoteResponse response = retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.IMPROVE, "  Revisar PRs antes  ", null));

            ArgumentCaptor<RetroNote> saved = ArgumentCaptor.forClass(RetroNote.class);
            verify(noteRepository).save(saved.capture());
            assertThat(saved.getValue().getCycle()).isSameAs(cycle);
            assertThat(saved.getValue().getAuthor()).isSameAs(member);
            assertThat(saved.getValue().getColumn()).isEqualTo(RetroColumn.IMPROVE);
            assertThat(response.text()).isEqualTo("Revisar PRs antes");
            assertThat(response.votes()).isZero();
        }

        @Test
        void sinEmpezarOTerminadaNoAdmiteNotas() {
            cycle.setRetroStartedAt(null);
            assertThatThrownBy(() -> retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.WENT_WELL, "Buen ritmo", null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.NOT_STARTED_MESSAGE);

            cycle.setRetroStartedAt(now.minus(Duration.ofHours(1)));
            cycle.setRetroFinishedAt(now);
            assertThatThrownBy(() -> retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.WENT_WELL, "Buen ritmo", null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.FINISHED_MESSAGE);
            verify(noteRepository, never()).save(any());
        }

        @Test
        void elAutorEditaSuNota() {
            RetroNote mine = note(RetroColumn.WENT_WELL, member);

            retroService.updateNote(SLUG, project.getId(), cycle.getId(), mine.getId(), member.getId(),
                    new UpdateRetroNoteRequest(" Deploys sin sustos ", null, null));

            assertThat(mine.getText()).isEqualTo("Deploys sin sustos");
        }

        @Test
        void unaAccionPuedeTenerResponsableQueSeaMiembroDelProyecto() {
            when(projectMemberRepository.existsByProjectIdAndUserId(project.getId(), otherMember.getId()))
                    .thenReturn(true);

            retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.ACTION, "Dividir WEB-2", otherMember.getId()));

            ArgumentCaptor<RetroNote> saved = ArgumentCaptor.forClass(RetroNote.class);
            verify(noteRepository).save(saved.capture());
            assertThat(saved.getValue().getOwner()).isSameAs(otherMember);
        }

        @Test
        void elResponsableDebeSerMiembroYSoloLasAccionesLoTienen() {
            when(projectMemberRepository.existsByProjectIdAndUserId(project.getId(), pm.getId())).thenReturn(false);

            assertThatThrownBy(() -> retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.ACTION, "Dividir WEB-2", pm.getId())))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(RetroServiceImpl.OWNER_NOT_MEMBER_MESSAGE);
            assertThatThrownBy(() -> retroService.createNote(SLUG, project.getId(), cycle.getId(), member.getId(),
                    new RetroNoteRequest(RetroColumn.IMPROVE, "Menos reuniones", otherMember.getId())))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(RetroServiceImpl.ONLY_ACTIONS_OWNER_MESSAGE);
            verify(noteRepository, never()).save(any());
        }

        @Test
        void editarElResponsableSinTocarElTextoYQuitarlo() {
            RetroNote action = note(RetroColumn.ACTION, member);
            when(projectMemberRepository.existsByProjectIdAndUserId(project.getId(), otherMember.getId()))
                    .thenReturn(true);

            retroService.updateNote(SLUG, project.getId(), cycle.getId(), action.getId(), member.getId(),
                    new UpdateRetroNoteRequest(null, otherMember.getId(), null));
            assertThat(action.getOwner()).isSameAs(otherMember);
            assertThat(action.getText()).isEqualTo("Nota ACTION");

            retroService.updateNote(SLUG, project.getId(), cycle.getId(), action.getId(), member.getId(),
                    new UpdateRetroNoteRequest(null, null, true));
            assertThat(action.getOwner()).isNull();
        }

        @Test
        void rechazaUnTextoEnBlancoAlEditar() {
            RetroNote mine = note(RetroColumn.WENT_WELL, member);

            assertThatThrownBy(() -> retroService.updateNote(SLUG, project.getId(), cycle.getId(), mine.getId(),
                    member.getId(), new UpdateRetroNoteRequest("   ", null, null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void otroMiembroNoEditaNiBorraUnaNotaAjena() {
            RetroNote mine = note(RetroColumn.WENT_WELL, member);

            assertThatThrownBy(() -> retroService.updateNote(SLUG, project.getId(), cycle.getId(), mine.getId(),
                    otherMember.getId(), new UpdateRetroNoteRequest("Otra cosa", null, null)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el autor de la nota o el project manager pueden editarla");
            assertThatThrownBy(() -> retroService.deleteNote(SLUG, project.getId(), cycle.getId(), mine.getId(),
                    otherMember.getId()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Solo el autor de la nota o el project manager pueden eliminarla");
            assertThat(mine.getText()).isEqualTo("Nota WENT_WELL");
            verify(noteRepository, never()).delete(any());
        }

        @Test
        void elProjectManagerEditaYBorraNotasAjenas() {
            RetroNote mine = note(RetroColumn.IMPROVE, member);

            retroService.updateNote(SLUG, project.getId(), cycle.getId(), mine.getId(), pm.getId(),
                    new UpdateRetroNoteRequest("Texto moderado", null, null));
            retroService.deleteNote(SLUG, project.getId(), cycle.getId(), mine.getId(), pm.getId());

            assertThat(mine.getText()).isEqualTo("Texto moderado");
            verify(noteRepository).delete(mine);
        }

        @Test
        void conLaRetroTerminadaNiElAutorEditaNiBorra() {
            RetroNote mine = note(RetroColumn.IMPROVE, member);
            cycle.setRetroFinishedAt(now);

            assertThatThrownBy(() -> retroService.updateNote(SLUG, project.getId(), cycle.getId(), mine.getId(),
                    member.getId(), new UpdateRetroNoteRequest("Otra cosa", null, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.FINISHED_MESSAGE);
            assertThatThrownBy(() -> retroService.deleteNote(SLUG, project.getId(), cycle.getId(), mine.getId(),
                    pm.getId()))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Votes {

        @Test
        void votarSumaElVotoYDescuentaDeLosRestantes() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember, otherMember);
            when(noteRepository.countVotesByUser(cycle.getId(), member.getId())).thenReturn(2L);

            RetroVoteResponse response = retroService.vote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId());

            assertThat(idea.getVoters()).containsExactlyInAnyOrder(otherMember, member);
            assertThat(response.note().votes()).isEqualTo(2);
            assertThat(response.note().votedByMe()).isTrue();
            assertThat(response.myVotesLeft()).isEqualTo(2);
            // Los votos se cuentan con la fila del ciclo bloqueada.
            verify(cycleRepository).findForUpdate(cycle.getId(), project.getId());
            verify(cycleRepository, never()).findByIdAndProjectId(any(), any());
        }

        @Test
        void unSoloVotoPorNota() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember, member);

            assertThatThrownBy(() -> retroService.vote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.ALREADY_VOTED_MESSAGE);
        }

        @Test
        void comoMaximoCincoVotosPorRetrospectiva() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember);
            when(noteRepository.countVotesByUser(cycle.getId(), member.getId())).thenReturn(5L);

            assertThatThrownBy(() -> retroService.vote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Ya usaste tus 5 votos en esta retrospectiva");
            assertThat(idea.getVoters()).isEmpty();
            verify(noteRepository, never()).saveAndFlush(any());
        }

        @Test
        void conLaRetroTerminadaNoSeVota() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember, member);
            cycle.setRetroFinishedAt(now);

            assertThatThrownBy(() -> retroService.vote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    otherMember.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.FINISHED_MESSAGE);
            assertThatThrownBy(() -> retroService.unvote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.FINISHED_MESSAGE);
            assertThat(idea.getVoters()).containsExactly(member);
        }

        @Test
        void quitarElVotoLoDevuelve() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember, member, otherMember);
            when(noteRepository.countVotesByUser(cycle.getId(), member.getId())).thenReturn(5L);

            RetroVoteResponse response = retroService.unvote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId());

            assertThat(idea.getVoters()).containsExactly(otherMember);
            assertThat(response.note().votedByMe()).isFalse();
            assertThat(response.myVotesLeft()).isEqualTo(1);
        }

        @Test
        void quitarUnVotoQueNoExisteNoCambiaNada() {
            RetroNote idea = note(RetroColumn.IMPROVE, otherMember, otherMember);
            when(noteRepository.countVotesByUser(cycle.getId(), member.getId())).thenReturn(3L);

            RetroVoteResponse response = retroService.unvote(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    member.getId());

            assertThat(idea.getVoters()).containsExactly(otherMember);
            assertThat(response.myVotesLeft()).isEqualTo(2);
            verify(noteRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    class Convert {

        private final UUID unstartedId = UUID.randomUUID();
        private final UUID issueId = UUID.randomUUID();
        private Issue created;

        @BeforeEach
        void issueCreation() {
            State unstarted = new State(project, "Todo", "#3A3A3A", StateGroup.UNSTARTED, 1);
            unstarted.setId(unstartedId);
            lenient().when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                    StateGroup.UNSTARTED)).thenReturn(Optional.of(unstarted));
            lenient().when(issueService.create(eq(SLUG), eq(project.getId()), eq(pm.getId()),
                    any(CreateIssueRequest.class))).thenReturn(issueResponse());
            created = new Issue();
            created.setId(issueId);
            lenient().when(issueRepository.getReferenceById(issueId)).thenReturn(created);
        }

        private IssueResponse issueResponse() {
            return new IssueResponse(issueId, "WEB-42", 42, project.getId(), null, null, "Automatizar el deploy", null, 0,
                    null,
                    null, null, null, null, null, null, 0, List.of(), List.of(), null, List.of(), 0, 0, 0, List.of(), List.of(), 0, 0,
                    0, 0, null, null, null, null, null, null, 0, 0, 0, 0, 0, false, null, null);
        }

        @Test
        void creaLaTareaEnElPrimerEstadoSinEmpezarYLaEnlazaALaNota() {
            RetroNote action = note(RetroColumn.ACTION, member);
            action.setText("Automatizar el deploy");

            IssueResponse issue = retroService.convert(SLUG, project.getId(), cycle.getId(), action.getId(),
                    pm.getId());

            ArgumentCaptor<CreateIssueRequest> request = ArgumentCaptor.forClass(CreateIssueRequest.class);
            verify(issueService).create(eq(SLUG), eq(project.getId()), eq(pm.getId()), request.capture());
            assertThat(request.getValue().title()).isEqualTo("Automatizar el deploy");
            assertThat(request.getValue().description())
                    .isEqualTo("Acción acordada en la retrospectiva del ciclo «Sprint 3».");
            assertThat(request.getValue().stateId()).isEqualTo(unstartedId);
            assertThat(action.getIssue()).isSameAs(created);
            verify(noteRepository).save(action);
            assertThat(issue.code()).isEqualTo("WEB-42");
        }

        @Test
        void laTareaSeAsignaAlResponsableDeLaAccion() {
            RetroNote action = note(RetroColumn.ACTION, member);
            action.setOwner(otherMember);
            when(projectMemberRepository.existsByProjectIdAndUserId(project.getId(), otherMember.getId()))
                    .thenReturn(true);

            retroService.convert(SLUG, project.getId(), cycle.getId(), action.getId(), pm.getId());

            ArgumentCaptor<CreateIssueRequest> request = ArgumentCaptor.forClass(CreateIssueRequest.class);
            verify(issueService).create(eq(SLUG), eq(project.getId()), eq(pm.getId()), request.capture());
            assertThat(request.getValue().assigneeIds()).containsExactly(otherMember.getId());
        }

        @Test
        void siElResponsableYaNoEsMiembroLaTareaQuedaSinAsignar() {
            RetroNote action = note(RetroColumn.ACTION, member);
            action.setOwner(otherMember);
            when(projectMemberRepository.existsByProjectIdAndUserId(project.getId(), otherMember.getId()))
                    .thenReturn(false);

            retroService.convert(SLUG, project.getId(), cycle.getId(), action.getId(), pm.getId());

            ArgumentCaptor<CreateIssueRequest> request = ArgumentCaptor.forClass(CreateIssueRequest.class);
            verify(issueService).create(eq(SLUG), eq(project.getId()), eq(pm.getId()), request.capture());
            assertThat(request.getValue().assigneeIds()).isNull();
        }

        @Test
        void sinEstadosSinEmpezarUsaElEstadoPorDefectoYCortaLosTitulosLargos() {
            when(stateRepository.findFirstByProjectIdAndStateGroupOrderByPositionAsc(project.getId(),
                    StateGroup.UNSTARTED)).thenReturn(Optional.empty());
            RetroNote action = note(RetroColumn.ACTION, member);
            String text = "Acordar\n" + "x".repeat(400);
            action.setText(text);

            retroService.convert(SLUG, project.getId(), cycle.getId(), action.getId(), pm.getId());

            ArgumentCaptor<CreateIssueRequest> request = ArgumentCaptor.forClass(CreateIssueRequest.class);
            verify(issueService).create(eq(SLUG), eq(project.getId()), eq(pm.getId()), request.capture());
            assertThat(request.getValue().stateId()).isNull();
            assertThat(request.getValue().title()).hasSize(RetroServiceImpl.MAX_TITLE_LENGTH)
                    .startsWith("Acordar xxx").endsWith("…");
            // El texto completo queda en la descripción.
            assertThat(request.getValue().description()).endsWith("> Acordar\n> " + "x".repeat(400));
        }

        @Test
        void soloLasAccionesSeConviertenYUnaSolaVez() {
            RetroNote idea = note(RetroColumn.IMPROVE, member);
            assertThatThrownBy(() -> retroService.convert(SLUG, project.getId(), cycle.getId(), idea.getId(),
                    pm.getId()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(RetroServiceImpl.ONLY_ACTIONS_MESSAGE);

            RetroNote converted = note(RetroColumn.ACTION, member);
            Issue existing = new Issue();
            existing.setProject(project);
            existing.setSequenceId(7);
            converted.setIssue(existing);
            assertThatThrownBy(() -> retroService.convert(SLUG, project.getId(), cycle.getId(), converted.getId(),
                    pm.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Esta acción ya se convirtió en la tarea WEB-7");
            verifyNoInteractions(issueService);
        }

        @Test
        void conLaRetroTerminadaTambienSeConvierte() {
            cycle.setRetroFinishedAt(now);
            RetroNote action = note(RetroColumn.ACTION, member);

            retroService.convert(SLUG, project.getId(), cycle.getId(), action.getId(), pm.getId());

            assertThat(action.getIssue()).isSameAs(created);
        }
    }

    @Nested
    class StartAndFinish {

        @Test
        void abrirLaRetroUnaSolaVez() {
            cycle.setRetroStartedAt(null);
            lenient().when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(cycle));

            RetroResponse retro = retroService.start(SLUG, project.getId(), cycle.getId(), pm.getId());
            Instant startedAt = cycle.getRetroStartedAt();
            retroService.start(SLUG, project.getId(), cycle.getId(), pm.getId());

            assertThat(retro.started()).isTrue();
            assertThat(startedAt).isNotNull();
            assertThat(cycle.getRetroStartedAt()).isEqualTo(startedAt);
            // Avisa al equipo solo la primera vez
            verify(notificationService).notifyRetroStarted(cycle, pm);
        }

        @Test
        void terminarLaRetroLaDejaDeSoloLectura() {
            lenient().when(cycleRepository.findAllByProjectId(project.getId())).thenReturn(List.of(cycle));

            RetroResponse retro = retroService.finish(SLUG, project.getId(), cycle.getId(), pm.getId());

            assertThat(cycle.getRetroFinishedAt()).isNotNull();
            assertThat(retro.finishedAt()).isEqualTo(cycle.getRetroFinishedAt());
        }

        @Test
        void noSeTerminaUnaRetroQueNoEmpezo() {
            cycle.setRetroStartedAt(null);

            assertThatThrownBy(() -> retroService.finish(SLUG, project.getId(), cycle.getId(), pm.getId()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage(RetroServiceImpl.NOT_STARTED_MESSAGE);
            assertThat(cycle.getRetroFinishedAt()).isNull();
        }
    }

    @Test
    void elTituloDeUnaAccionLargaConEmojisNoParteUnEmoji() {
        // 253 letras y emojis: el corte en 254 caía en medio de un emoji y la tarea quedaba con «?…» en el título
        String text = "a".repeat(253) + "😀".repeat(100);

        String title = RetroServiceImpl.issueTitle(text);

        assertThat(title).isEqualTo("a".repeat(253) + "…");
        assertThat(title.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
    }
}
