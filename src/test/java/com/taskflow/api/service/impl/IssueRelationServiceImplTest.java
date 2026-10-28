package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CreateIssueRelationRequest;
import com.taskflow.api.dto.request.IssueRelationKind;
import com.taskflow.api.dto.response.IssueRelationResponse;
import com.taskflow.api.dto.response.IssueRelationsResponse;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueRelationServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueRelationRepository relationRepository;
    @Mock
    private IssueRepository issueRepository;
    @Mock
    private ActivityService activityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private IssueRelationServiceImpl relationService;

    private Project project;
    private State todo;
    private State done;
    private User actor;
    private Issue web1;
    private Issue web2;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        todo = state(StateGroup.UNSTARTED);
        done = state(StateGroup.COMPLETED);
        actor = new User();
        actor.setId(UUID.randomUUID());
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(lookup.user(actor.getId())).thenReturn(actor);
        lenient().when(issueMapper.toRelated(any(Issue.class))).thenAnswer(invocation -> {
            Issue issue = invocation.getArgument(0);
            return new RelatedIssueResponse(issue.getId(), issue.getCode(), issue.getTitle(), null, List.of());
        });
        web1 = issue(1, todo);
        web2 = issue(2, todo);
    }

    private State state(StateGroup group) {
        State state = new State(project, group.name(), "#000000", group, 0);
        state.setId(UUID.randomUUID());
        return state;
    }

    private Issue issue(int sequenceId, State state) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setSequenceId(sequenceId);
        issue.setTitle("Tarea " + sequenceId);
        issue.setState(state);
        lenient().when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId()))
                .thenReturn(Optional.of(issue));
        return issue;
    }

    private IssueRelation relation(Issue source, Issue target, IssueRelationType type) {
        IssueRelation relation = new IssueRelation(source, target, type);
        relation.setId(UUID.randomUUID());
        return relation;
    }

    private IssueRelationResponse create(Issue from, IssueRelationKind kind, Issue other) {
        return relationService.create(SLUG, project.getId(), from.getId(), actor.getId(),
                new CreateIssueRelationRequest(kind, other.getId()));
    }

    private IssueRelation savedRelation() {
        ArgumentCaptor<IssueRelation> saved = ArgumentCaptor.forClass(IssueRelation.class);
        verify(relationRepository).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    class Create {

        @Test
        void bloqueaGuardaLaTareaDeLaUrlComoOrigenYLoRegistraEnAmbas() {
            IssueRelationResponse response = create(web1, IssueRelationKind.BLOCKS, web2);

            IssueRelation saved = savedRelation();
            assertThat(saved.getSource()).isSameAs(web1);
            assertThat(saved.getTarget()).isSameAs(web2);
            assertThat(saved.getType()).isEqualTo(IssueRelationType.BLOCKS);
            // La respuesta muestra la otra tarea
            assertThat(response.issue().code()).isEqualTo("WEB-2");
            verify(activityService).record(web1, actor, ActivityField.RELATION, null, "bloquea WEB-2");
            verify(activityService).record(web2, actor, ActivityField.RELATION, null, "bloqueada por WEB-1");
        }

        @Test
        void bloqueadaPorSeGuardaComoElBloqueoInverso() {
            IssueRelationResponse response = create(web1, IssueRelationKind.BLOCKED_BY, web2);

            IssueRelation saved = savedRelation();
            assertThat(saved.getSource()).isSameAs(web2);
            assertThat(saved.getTarget()).isSameAs(web1);
            assertThat(saved.getType()).isEqualTo(IssueRelationType.BLOCKS);
            assertThat(response.issue().code()).isEqualTo("WEB-2");
            verify(activityService).record(web2, actor, ActivityField.RELATION, null, "bloquea WEB-1");
            verify(activityService).record(web1, actor, ActivityField.RELATION, null, "bloqueada por WEB-2");
        }

        @Test
        void relacionadaSeGuardaUnaSolaVez() {
            create(web1, IssueRelationKind.RELATES, web2);

            IssueRelation saved = savedRelation();
            assertThat(saved.getType()).isEqualTo(IssueRelationType.RELATES);
            verify(activityService).record(web1, actor, ActivityField.RELATION, null, "relacionada con WEB-2");
            verify(activityService).record(web2, actor, ActivityField.RELATION, null, "relacionada con WEB-1");
        }

        @Test
        void rechazaRelacionarUnaTareaConsigoMisma() {
            assertThatThrownBy(() -> create(web1, IssueRelationKind.BLOCKS, web1))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Una tarea no puede relacionarse consigo misma");
            verify(relationRepository, never()).save(any());
        }

        @Test
        void rechazaUnBloqueoRepetido() {
            lenient().when(relationRepository.existsBySourceIdAndTargetIdAndType(web1.getId(), web2.getId(),
                    IssueRelationType.BLOCKS)).thenReturn(true);

            assertThatThrownBy(() -> create(web1, IssueRelationKind.BLOCKS, web2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("WEB-1 ya bloquea a WEB-2");
            verify(relationRepository, never()).save(any());
        }

        @Test
        void rechazaUnCicloDirecto() {
            // WEB-2 ya bloquea a WEB-1: WEB-1 no puede bloquear a WEB-2
            lenient().when(relationRepository.existsBySourceIdAndTargetIdAndType(web2.getId(), web1.getId(),
                    IssueRelationType.BLOCKS)).thenReturn(true);

            assertThatThrownBy(() -> create(web1, IssueRelationKind.BLOCKS, web2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("WEB-2 ya bloquea a WEB-1: dos tareas no pueden bloquearse mutuamente");
            verify(relationRepository, never()).save(any());
        }

        @Test
        void rechazaElCicloTambienDesdeBloqueadaPor() {
            // WEB-1 bloquea a WEB-2: WEB-1 no puede estar bloqueada por WEB-2
            lenient().when(relationRepository.existsBySourceIdAndTargetIdAndType(web1.getId(), web2.getId(),
                    IssueRelationType.BLOCKS)).thenReturn(true);

            assertThatThrownBy(() -> create(web1, IssueRelationKind.BLOCKED_BY, web2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("mutuamente");
        }

        @Test
        void rechazaRelacionadaRepetidaEnCualquierDireccion() {
            lenient().when(relationRepository.existsBySourceIdAndTargetIdAndType(web2.getId(), web1.getId(),
                    IssueRelationType.RELATES)).thenReturn(true);

            assertThatThrownBy(() -> create(web1, IssueRelationKind.RELATES, web2))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("WEB-1 ya está relacionada con WEB-2");
        }

        @Test
        void unaTareaPuedeBloquearYEstarRelacionadaConLaMisma() {
            lenient().when(relationRepository.existsBySourceIdAndTargetIdAndType(web1.getId(), web2.getId(),
                    IssueRelationType.RELATES)).thenReturn(true);

            create(web1, IssueRelationKind.BLOCKS, web2);

            assertThat(savedRelation().getType()).isEqualTo(IssueRelationType.BLOCKS);
        }

        @Test
        void laOtraTareaDebeSerDelMismoProyecto() {
            UUID otherProjectIssue = UUID.randomUUID();
            when(issueRepository.findByIdAndProjectId(otherProjectIssue, project.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> relationService.create(SLUG, project.getId(), web1.getId(), actor.getId(),
                    new CreateIssueRelationRequest(IssueRelationKind.BLOCKS, otherProjectIssue)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("La tarea relacionada no existe en este proyecto");
        }
    }

    @Test
    void agrupaLasRelacionesVistasDesdeLaTareaYOrdenaPorNumero() {
        Issue web5 = issue(5, todo);
        Issue web3 = issue(3, todo);
        Issue web4 = issue(4, done);
        Issue web9 = issue(9, todo);
        when(relationRepository.findAllByIssueId(web1.getId())).thenReturn(List.of(
                relation(web1, web5, IssueRelationType.BLOCKS),
                relation(web1, web3, IssueRelationType.BLOCKS),
                relation(web4, web1, IssueRelationType.BLOCKS),
                relation(web9, web1, IssueRelationType.RELATES),
                relation(web1, web2, IssueRelationType.RELATES)));

        IssueRelationsResponse response = relationService.getRelations(SLUG, project.getId(), web1.getId());

        assertThat(response.blocks()).extracting(rel -> rel.issue().code()).containsExactly("WEB-3", "WEB-5");
        assertThat(response.blockedBy()).extracting(rel -> rel.issue().code()).containsExactly("WEB-4");
        assertThat(response.related()).extracting(rel -> rel.issue().code()).containsExactly("WEB-2", "WEB-9");
        assertThat(response.blocks()).allSatisfy(rel -> assertThat(rel.relationId()).isNotNull());
    }

    @Nested
    class Delete {

        @Test
        void quitaLaRelacionYLoRegistraEnAmbasTareas() {
            IssueRelation relation = relation(web2, web1, IssueRelationType.BLOCKS);
            when(relationRepository.findByIdAndIssueId(relation.getId(), web1.getId())).thenReturn(Optional.of(relation));

            relationService.delete(SLUG, project.getId(), web1.getId(), relation.getId(), actor.getId());

            verify(relationRepository).delete(relation);
            verify(activityService).record(web2, actor, ActivityField.RELATION, "bloquea WEB-1", null);
            verify(activityService).record(web1, actor, ActivityField.RELATION, "bloqueada por WEB-2", null);
        }

        @Test
        void unaRelacionDeOtraTareaNoExiste() {
            UUID relationId = UUID.randomUUID();
            when(relationRepository.findByIdAndIssueId(relationId, web1.getId())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> relationService.delete(SLUG, project.getId(), web1.getId(), relationId,
                    actor.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Relación no encontrada");
            verify(relationRepository, never()).delete(any());
            verifyNoInteractions(activityService);
        }
    }

    @Nested
    class NotifyUnblocked {

        private Issue blocker;

        @BeforeEach
        void setUpBlocker() {
            blocker = issue(12, done);
        }

        @Test
        void avisaSiNoQuedanOtrosBloqueosAbiertos() {
            when(relationRepository.findIssuesBlockedBy(blocker.getId())).thenReturn(List.of(web2));
            when(relationRepository.countOpenBlockersExcept(web2.getId(), blocker.getId(), StateGroup.CLOSED))
                    .thenReturn(0L);

            relationService.notifyUnblocked(blocker, actor);

            verify(notificationService).notifyUnblocked(web2, blocker, actor);
        }

        @Test
        void noAvisaSiOtraTareaAbiertaLaSigueBloqueando() {
            when(relationRepository.findIssuesBlockedBy(blocker.getId())).thenReturn(List.of(web2));
            when(relationRepository.countOpenBlockersExcept(web2.getId(), blocker.getId(), StateGroup.CLOSED))
                    .thenReturn(1L);

            relationService.notifyUnblocked(blocker, actor);

            verify(notificationService, never()).notifyUnblocked(any(), any(), any());
        }

        @Test
        void noAvisaALasTareasQueYaEstanCerradas() {
            Issue closed = issue(20, done);
            when(relationRepository.findIssuesBlockedBy(blocker.getId())).thenReturn(List.of(closed, web2));
            when(relationRepository.countOpenBlockersExcept(eq(web2.getId()), eq(blocker.getId()), any()))
                    .thenReturn(0L);

            relationService.notifyUnblocked(blocker, actor);

            verify(notificationService).notifyUnblocked(web2, blocker, actor);
            verify(notificationService, never()).notifyUnblocked(eq(closed), any(), any());
            verify(relationRepository, never()).countOpenBlockersExcept(eq(closed.getId()), any(), any());
        }
    }
}
