package com.taskflow.api.service.impl;

import java.time.Instant;

import com.taskflow.api.dto.response.NotificationResponse;

import com.taskflow.api.dto.response.PageResponse;

import org.springframework.data.domain.Limit;

import com.taskflow.api.dto.response.NotificationCountsResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Notification;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.entity.enums.NotificationType;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.NotificationRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.UserRepository;
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
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private CollaborationMapper mapper;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Project project;
    private Issue issue;
    private Comment comment;
    private User author;
    private User ana;
    private User luis;
    private User creator;
    /** Quienes ya no pueden ver el proyecto (los quitaron). */
    private final Set<UUID> removed = new HashSet<>();

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
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
        // Pueden ver la tarea todos los usuarios de la prueba, salvo los quitados del proyecto
        List<User> everyone = List.of(author, ana, luis, creator);
        lenient().when(userRepository.findMentionable(any(), eq(project.getId()), eq(workspace.getId())))
                .thenAnswer(call -> {
                    Collection<UUID> ids = call.getArgument(0);
                    return everyone.stream()
                            .filter(user -> ids.contains(user.getId()) && !removed.contains(user.getId()))
                            .toList();
                });
    }

    @Test
    void quienYaNoEstaEnElProyectoNoRecibeAvisosDeComentariosNiDesbloqueos() {
        // Carla creó la tarea y Luis estaba asignado, pero los quitaron del proyecto
        removed.add(creator.getId());
        removed.add(luis.getId());
        Issue blocker = new Issue();
        blocker.setProject(project);
        blocker.setSequenceId(3);

        notificationService.notifyCommented(issue, author, comment, List.of());
        notificationService.notifyUnblocked(issue, blocker, author);

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType)
                .containsExactlyInAnyOrder(
                        tuple("Ana", NotificationType.ISSUE_COMMENTED),
                        tuple("Ana", NotificationType.ISSUE_UNBLOCKED));
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
    void historiaListaParaRevisarAvisaALosProjectManagersSinAutorNiAQuienLaDejoLista() {
        issue.setTitle("Pantalla de perfil de usuario");
        issue.setSequenceId(15);
        User pm2 = user("Marta");

        notificationService.notifyStoryReady(issue, List.of(ana, pm2, ana), ana);

        assertThat(saved())
                .extracting(n -> n.getRecipient().getFullName(), Notification::getType, Notification::getMessage,
                        Notification::getActor, Notification::getIssue)
                .containsExactly(tuple("Marta", NotificationType.STORY_READY,
                        "WEB-15 está lista para revisar: Pantalla de perfil de usuario", null, issue));
    }

    @Test
    void contadoresDeLaBandeja() {
        UUID me = UUID.randomUUID();
        when(notificationRepository.countMine(me, EnumSet.allOf(NotificationType.class), true)).thenReturn(3L);
        when(notificationRepository.countMine(me, EnumSet.of(NotificationType.ISSUE_MENTIONED), true))
                .thenReturn(1L);
        when(notificationRepository.countMine(me, EnumSet.of(NotificationType.ISSUE_MENTIONED), false))
                .thenReturn(4L);

        assertThat(notificationService.counts(me)).isEqualTo(new NotificationCountsResponse(3, 1, 4));
    }

    @Test
    void conBeforePideLasSiguientesSinContarYDevuelveElCursor() {
        UUID me = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-29T14:03:07.123456Z");
        UUID afterId = UUID.randomUUID();
        List<Notification> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Notification notification = new Notification();
            notification.setId(UUID.randomUUID());
            notification.setCreatedAt(at.minusSeconds(i + 1));
            rows.add(notification);
        }
        // Pide una más que el tamaño: la de más solo indica que hay otra página
        when(notificationRepository.findMineBefore(eq(me), any(), eq(false), eq(at), eq(afterId), eq(Limit.of(3))))
                .thenReturn(rows);

        PageResponse<NotificationResponse> page = notificationService.getMyNotifications(me, false,
                List.of(NotificationType.ISSUE_MENTIONED), PageRequest.of(7, 2), at + "," + afterId);

        assertThat(page.content()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(rows.get(1).getCreatedAt() + "," + rows.get(1).getId());
        assertThat(page.last()).isFalse();
        assertThat(page.totalElements()).isEqualTo(PageResponse.UNKNOWN);
        verify(notificationRepository, never()).findMine(any(), any(), anyBoolean(), any());
    }

    @Test
    void laUltimaPaginaPorCursorNoTraeCursor() {
        UUID me = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-29T14:03:07Z");
        Notification only = new Notification();
        only.setId(UUID.randomUUID());
        only.setCreatedAt(at.minusSeconds(1));
        when(notificationRepository.findMineBefore(any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(only));

        PageResponse<NotificationResponse> page = notificationService.getMyNotifications(me, true, null,
                PageRequest.of(0, 20), at + "," + UUID.randomUUID());

        assertThat(page.content()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.last()).isTrue();
    }

    @Test
    void sinBeforeLaPaginaNormalTraeElCursorDeSuUltimaYOrdenaPorIdAIgualFecha() {
        UUID me = UUID.randomUUID();
        Notification last = new Notification();
        last.setId(UUID.randomUUID());
        last.setCreatedAt(Instant.parse("2026-10-29T14:03:07.5Z"));
        Pageable requested = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"));
        ArgumentCaptor<Pageable> used = ArgumentCaptor.forClass(Pageable.class);
        when(notificationRepository.findMine(eq(me), any(), anyBoolean(), used.capture()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(last), invocation.getArgument(3), 5));

        PageResponse<NotificationResponse> page = notificationService.getMyNotifications(me, false, null, requested);

        assertThat(used.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.nextCursor()).isEqualTo("2026-10-29T14:03:07.500Z," + last.getId());
    }

    @Test
    void unCursorMalFormadoResponde400() {
        UUID me = UUID.randomUUID();
        for (String bad : List.of("ayer", "2026-10-29T14:03:07Z", "2026-10-29T14:03:07Z,no-es-uuid", ",abc")) {
            assertThatThrownBy(() -> notificationService.getMyNotifications(me, false, null, PageRequest.of(0, 20),
                    bad))
                    .isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(notificationRepository);
    }

    @Test
    void laBandejaSoloSeOrdenaPorFecha() {
        UUID me = UUID.randomUUID();

        assertThatThrownBy(() -> notificationService.getMyNotifications(me, false, null,
                PageRequest.of(0, 20, Sort.by("actor.passwordHash"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("actor.passwordHash");
        verifyNoInteractions(notificationRepository);
    }
}
