package com.taskflow.api.service.impl;

import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private CollaborationMapper mapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Issue issue;
    private User author;
    private User ana;
    private User luis;
    private User creator;

    @BeforeEach
    void setUp() {
        Project project = new Project();
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
        notificationService.notifyCommented(issue, author, List.of(ana));

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage)
                .containsExactlyInAnyOrder(
                        tuple("Ana", NotificationType.ISSUE_MENTIONED, "Ana Autora te mencionó en WEB-12: Rediseñar la portada"),
                        tuple("Luis", NotificationType.ISSUE_COMMENTED, "Ana Autora comentó en WEB-12: Rediseñar la portada"),
                        tuple("Carla", NotificationType.ISSUE_COMMENTED, "Ana Autora comentó en WEB-12: Rediseñar la portada"));
    }

    @Test
    void unMencionadoQueNoEsAsignadoRecibeSoloLaMencion() {
        User pm = user("Paula PM");

        notificationService.notifyCommented(issue, author, List.of(pm));

        assertThat(saved()).filteredOn(n -> n.getType() == NotificationType.ISSUE_MENTIONED)
                .extracting(n -> n.getRecipient().getFullName())
                .containsExactly("Paula PM");
        assertThat(saved()).filteredOn(n -> n.getType() == NotificationType.ISSUE_COMMENTED).hasSize(3);
    }

    @Test
    void nadieRecibeAvisosDeSuPropiaAccion() {
        notificationService.notifyMentioned(issue, author, List.of(author, ana, ana));

        assertThat(saved()).extracting(n -> n.getRecipient().getFullName()).containsExactly("Ana");
    }

    @Test
    void avisaALosAsignadosDeLaTareaDesbloqueada() {
        Issue blocker = new Issue();
        blocker.setProject(issue.getProject());
        blocker.setSequenceId(3);

        notificationService.notifyUnblocked(issue, blocker, luis);

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage,
                        Notification::getIssue)
                .containsExactly(tuple("Ana", NotificationType.ISSUE_UNBLOCKED,
                        "WEB-12 ya no está bloqueada: WEB-3 se completó", issue));
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
}
