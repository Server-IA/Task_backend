package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.CommentRequest;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.mapper.CollaborationMapper;
import com.taskflow.api.repository.CommentRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Menciones con {@code @[Nombre](id)} en los comentarios.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private IssueRepository issueRepository;
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
    private CollaborationMapper mapper;

    @InjectMocks
    private CommentServiceImpl commentService;

    private Project project;
    private Issue issue;
    private User author;
    private User ana;
    private User luis;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        project.setIdentifier("WEB");
        issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        author = user("Autor");
        ana = user("Ana");
        luis = user("Luis");
        when(lookup.project(SLUG, project.getId())).thenReturn(project);
        when(issueRepository.findByIdAndProjectId(issue.getId(), project.getId())).thenReturn(Optional.of(issue));
        lenient().when(lookup.user(author.getId())).thenReturn(author);
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    private static String mention(User user) {
        return "@[" + user.getFullName() + "](" + user.getId() + ")";
    }

    @SuppressWarnings("unchecked")
    private Collection<UUID> queriedIds() {
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findMentionable(ids.capture(), eq(project.getId()), eq(project.getWorkspace().getId()));
        return ids.getValue();
    }

    @Nested
    class Create {

        private void comment(String body) {
            commentService.create(SLUG, project.getId(), issue.getId(), author.getId(), new CommentRequest(body));
        }

        @Test
        void avisaSoloALosMencionadosQuePuedenVerLaTarea() {
            User outsider = user("Externo");
            // La consulta deja fuera a quien no es miembro del proyecto ni project manager
            when(userRepository.findMentionable(anyCollection(), eq(project.getId()), any())).thenReturn(List.of(ana));

            comment("Hola " + mention(ana) + " y " + mention(outsider));

            assertThat(queriedIds()).containsExactly(ana.getId(), outsider.getId());
            verify(notificationService).notifyCommented(issue, author, List.of(ana));
        }

        @Test
        void unaPersonaMencionadaVariasVecesCuentaUnaVezYElAutorNoSeAvisaASiMismo() {
            when(userRepository.findMentionable(anyCollection(), eq(project.getId()), any())).thenReturn(List.of(ana));

            comment(mention(ana) + " " + mention(author) + " otra vez " + mention(ana));

            assertThat(queriedIds()).containsExactly(ana.getId());
            verify(notificationService).notifyCommented(issue, author, List.of(ana));
        }

        @Test
        void sinMencionesNoConsultaUsuarios() {
            comment("Listo, ya quedó");

            verify(userRepository, never()).findMentionable(any(), any(), any());
            verify(notificationService).notifyCommented(issue, author, List.of());
        }
    }

    @Nested
    class Update {

        private Comment comment;

        @BeforeEach
        void existingComment() {
            comment = new Comment();
            comment.setId(UUID.randomUUID());
            comment.setIssue(issue);
            comment.setAuthor(author);
            comment.setBody("Revisa esto " + mention(ana));
            when(commentRepository.findByIdAndIssueId(comment.getId(), issue.getId())).thenReturn(Optional.of(comment));
            lenient().when(commentRepository.saveAndFlush(comment)).thenReturn(comment);
        }

        @Test
        void soloAvisaALasMencionesNuevas() {
            when(userRepository.findMentionable(anyCollection(), eq(project.getId()), any())).thenReturn(List.of(luis));

            commentService.update(SLUG, project.getId(), issue.getId(), comment.getId(), author.getId(),
                    new CommentRequest("Revisa esto " + mention(ana) + " y tú también " + mention(luis)));

            assertThat(queriedIds()).containsExactly(luis.getId());
            verify(notificationService).notifyMentioned(issue, author, List.of(luis));
            verify(notificationService, never()).notifyCommented(any(), any(), any());
        }

        @Test
        void sinMencionesNuevasNoAvisaANadie() {
            commentService.update(SLUG, project.getId(), issue.getId(), comment.getId(), author.getId(),
                    new CommentRequest("Revisa esto, " + mention(ana) + ", cuando puedas"));

            verify(userRepository, never()).findMentionable(any(), any(), any());
            verify(notificationService).notifyMentioned(issue, author, List.of());
        }

        @Test
        void soloElAutorPuedeEditar() {
            assertThatThrownBy(() -> commentService.update(SLUG, project.getId(), issue.getId(), comment.getId(),
                    ana.getId(), new CommentRequest("Hola " + mention(luis))))
                    .isInstanceOf(ForbiddenException.class);
            verify(notificationService, never()).notifyMentioned(any(), any(), any());
        }
    }
}
