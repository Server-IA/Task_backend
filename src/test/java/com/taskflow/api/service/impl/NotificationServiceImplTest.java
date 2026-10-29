package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.NotificationCountsResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.NotificationRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private CollaborationMapper mapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Project project;
    private Issue issue;
    private Comment comment;
    private User author;
    private User ana;
    private User luis;
    private User creator;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        issue = new Issue();
        issue.setProject(project);
        issue.setSequenceId(12);
        issue.setTitle("Rediseñar la portada");
        author = user("Ana Autora");
        ana = user("Ana");
        luis = user("Luis");
        creator = user("Carla");
        issue.setCreatedBy(creator);
        issue.getAssignees().addAll(List.of(ana, luis));
        comment = new Comment();
        comment.setId(UUID.randomUUID());
        comment.setBody("¿lo revisas antes de la daily?");
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private List<Notification> saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void laMencionGanaAlAvisoDelComentario() {
        notificationService.notifyCommented(issue, author, comment, List.of(ana));

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage)
                .containsExactlyInAnyOrder(
                        tuple("Ana", NotificationType.ISSUE_MENTIONED, "Ana Autora te mencionó en WEB-12: Rediseñar la portada"),
                        tuple("Luis", NotificationType.ISSUE_COMMENTED, "Ana Autora comentó en WEB-12: Rediseñar la portada"),
                        tuple("Carla", NotificationType.ISSUE_COMMENTED, "Ana Autora comentó en WEB-12: Rediseñar la portada"));
        // Todas guardan el comentario para mostrar su extracto en la bandeja
        assertThat(saved()).allSatisfy(notification -> {
            assertThat(notification.getComment()).isSameAs(comment);
            assertThat(notification.getIssue()).isSameAs(issue);
        });
    }

    @Test
    void unMencionadoQueNoEsAsignadoRecibeSoloLaMencion() {
        User pm = user("Paula PM");

        notificationService.notifyCommented(issue, author, comment, List.of(pm));

        assertThat(saved()).filteredOn(n -> n.getType() == NotificationType.ISSUE_MENTIONED)
                .extracting(n -> n.getRecipient().getFullName())
                .containsExactly("Paula PM");
        assertThat(saved()).filteredOn(n -> n.getType() == NotificationType.ISSUE_COMMENTED).hasSize(3);
    }

    @Test
    void nadieRecibeAvisosDeSuPropiaAccion() {
        notificationService.notifyMentioned(issue, author, comment, List.of(author, ana, ana));

        assertThat(saved()).extracting(n -> n.getRecipient().getFullName()).containsExactly("Ana");
    }

    @Test
    void laMencionEnLaDescripcionAvisaSinComentario() {
        notificationService.notifyMentionedInDescription(issue, author, List.of(author, luis));

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage)
                .containsExactly(tuple("Luis", NotificationType.ISSUE_MENTIONED,
                        "Ana Autora te mencionó en la descripción de WEB-12: Rediseñar la portada"));
        assertThat(saved()).allSatisfy(notification -> {
            assertThat(notification.getComment()).isNull();
            assertThat(notification.getIssue()).isSameAs(issue);
        });
    }

    @Test
    void avisaALosAsignadosDeLaTareaDesbloqueada() {
        Issue blocker = new Issue();
        blocker.setProject(issue.getProject());
        blocker.setSequenceId(3);

        notificationService.notifyUnblocked(issue, blocker, luis);

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage,
                        Notification::getIssue, Notification::getComment)
                .containsExactly(tuple("Ana", NotificationType.ISSUE_UNBLOCKED,
                        "WEB-12 ya no está bloqueada: WEB-3 se completó", issue, null));
    }

    @Test
    void sinAsignadosNoHayAvisoDeDesbloqueo() {
        issue.getAssignees().clear();
        Issue blocker = new Issue();
        blocker.setProject(issue.getProject());
        blocker.setSequenceId(3);

        notificationService.notifyUnblocked(issue, blocker, author);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void laRetroAvisaALosMiembrosDelProyectoMenosAQuienLaAbre() {
        Cycle sprint = new Cycle();
        sprint.setProject(project);
        sprint.setName("Sprint 1");
        when(projectMemberRepository.findAllByProjectIdWithUser(project.getId())).thenReturn(List.of(
                new ProjectMember(project, author), new ProjectMember(project, ana), new ProjectMember(project, luis)));

        notificationService.notifyRetroStarted(sprint, author);

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage,
                        Notification::getCycle, Notification::getIssue, Notification::getActor)
                .containsExactly(
                        tuple("Ana", NotificationType.RETRO_STARTED,
                                "Ana Autora abrió la retrospectiva de «Sprint 1»: agrega tus notas", sprint, null,
                                author),
                        tuple("Luis", NotificationType.RETRO_STARTED,
                                "Ana Autora abrió la retrospectiva de «Sprint 1»: agrega tus notas", sprint, null,
                                author));
    }

    @Test
    void sinTipoListaTodosLosTiposYConTipoSoloEsos() {
        UUID me = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        Page<Notification> empty = new PageImpl<>(List.of(), pageable, 0);
        when(notificationRepository.findMine(eq(me), any(), anyBoolean(), eq(pageable))).thenReturn(empty);

        notificationService.getMyNotifications(me, false, null, pageable);
        notificationService.getMyNotifications(me, true, List.of(NotificationType.ISSUE_MENTIONED), pageable);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NotificationType>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Boolean> unread = ArgumentCaptor.forClass(Boolean.class);
        verify(notificationRepository, atLeast(2)).findMine(eq(me), types.capture(), unread.capture(), eq(pageable));
        assertThat(types.getAllValues().get(0)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(NotificationType.class));
        assertThat(types.getAllValues().get(1)).containsExactly(NotificationType.ISSUE_MENTIONED);
        assertThat(unread.getAllValues()).containsExactly(false, true);
    }

    @Test
    void contadoresDeLaBandeja() {
        UUID me = UUID.randomUUID();
        when(notificationRepository.countByRecipientIdAndReadFalse(me)).thenReturn(3L);
        when(notificationRepository.countByRecipientIdAndTypeAndReadFalse(me, NotificationType.ISSUE_MENTIONED))
                .thenReturn(1L);
        when(notificationRepository.countByRecipientIdAndType(me, NotificationType.ISSUE_MENTIONED)).thenReturn(4L);

        assertThat(notificationService.counts(me)).isEqualTo(new NotificationCountsResponse(3, 1, 4));
    }
}
