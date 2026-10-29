package com.taskflow.api.service.impl;

import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.StoryChildResponse;
import com.taskflow.api.dto.response.StoryResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.mapper.IssueMapper;
import com.taskflow.api.mapper.UserMapper;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El listado de «Historias»: una consulta para las historias, otra para todas sus hijas y otra para las relacionadas
 * (no una por historia).
 */
@ExtendWith(MockitoExtension.class)
class StoryServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private IssueRepository issueRepository;
    @Mock
    private IssueRelationRepository relationRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private IssueMapper issueMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private StoryServiceImpl storyService;

    private Project project;
    private State done;
    private State inProgress;
    private int sequence;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(UUID.randomUUID());
        project.setIdentifier("WEB");
        done = new State(project, "Done", "#000000", StateGroup.COMPLETED, 3);
        inProgress = new State(project, "In Progress", "#000000", StateGroup.STARTED, 2);
        lenient().when(lookup.project(SLUG, project.getId())).thenReturn(project);
        lenient().when(issueMapper.toResponse(any(Issue.class))).thenAnswer(invocation -> mock(IssueResponse.class));
        lenient().when(issueMapper.toStoryChild(any(Issue.class), anyBoolean())).thenAnswer(invocation -> {
            Issue child = invocation.getArgument(0);
            return new StoryChildResponse(child.getId(), child.getCode(), child.getTitle(), child.getType(), null,
                    child.getPriority(), List.of(), child.getCreatedAt(), invocation.getArgument(1));
        });
        lenient().when(issueMapper.toSummary(any(Issue.class))).thenAnswer(invocation -> {
            Issue issue = invocation.getArgument(0);
            return new IssueSummaryResponse(issue.getId(), issue.getCode(), issue.getTitle(), issue.getType());
        });
        lenient().when(userMapper.toSummary(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return new UserSummaryResponse(user.getId(), user.getEmail(), user.getFullName(), null);
        });
    }

    private Issue issue(IssueType type, State state, Issue parent, User... assignees) {
        Issue issue = new Issue();
        issue.setId(UUID.randomUUID());
        issue.setProject(project);
        issue.setType(type);
        issue.setState(state);
        issue.setSequenceId(++sequence);
        issue.setTitle(type + " " + sequence);
        issue.setParent(parent);
        issue.getAssignees().addAll(List.of(assignees));
        issue.setCreatedAt(Instant.parse("2026-11-01T10:00:00Z").plusSeconds(sequence * 3600L));
        return issue;
    }

    private static User user(String name) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(name);
        return user;
    }

    @Test
    void sinHistoriasNoConsultaNadaMas() {
        when(issueRepository.findStories(project.getId())).thenReturn(List.of());

        assertThat(storyService.getStories(SLUG, project.getId())).isEmpty();
        verify(issueRepository, never()).findChildrenOf(anyCollection());
    }

    @Test
    void cadaHistoriaTraeSusTareasPrimeroSusBugsSusPersonasYSusRelacionadas() {
        User luis = user("Luis");
        User carlos = user("Carlos");
        Issue story = issue(IssueType.STORY, inProgress, null);
        Issue unsplit = issue(IssueType.STORY, inProgress, null);
        Issue bug = issue(IssueType.BUG, inProgress, story, carlos);
        Issue task2 = issue(IssueType.TASK, done, story, luis);
        Issue task1 = issue(IssueType.TASK, done, story, carlos, luis);
        Issue newStory = issue(IssueType.STORY, inProgress, null);
        when(issueRepository.findStories(project.getId())).thenReturn(List.of(story, unsplit));
        when(issueRepository.findChildrenOf(List.of(story.getId(), unsplit.getId())))
                .thenReturn(List.of(bug, task2, task1));
        when(relationRepository.findRelatesOf(List.of(story.getId(), unsplit.getId())))
                .thenReturn(List.of(new IssueRelation(newStory, story, IssueRelationType.RELATES)));

        List<StoryResponse> stories = storyService.getStories(SLUG, project.getId());

        assertThat(stories).hasSize(2);
        StoryResponse first = stories.getFirst();
        assertThat(first.children()).extracting(StoryChildResponse::id)
                .containsExactly(task2.getId(), task1.getId(), bug.getId());
        assertThat(first.people()).extracting(UserSummaryResponse::fullName).containsExactly("Carlos", "Luis");
        assertThat(first.related()).extracting(IssueSummaryResponse::code).containsExactly(newStory.getCode());
        assertThat(first.children()).noneMatch(StoryChildResponse::afterClose);
        StoryResponse second = stories.get(1);
        assertThat(second.children()).isEmpty();
        assertThat(second.people()).isEmpty();
        assertThat(second.related()).isEmpty();
    }

    @Test
    void losBugsCreadosDespuesDeCerrarlaSeMarcan() {
        Issue story = issue(IssueType.STORY, done, null);
        Issue before = issue(IssueType.BUG, done, story);
        story.setClosedAt(Instant.parse("2026-11-01T10:00:00Z").plusSeconds(sequence * 3600L + 60));
        Issue after = issue(IssueType.BUG, inProgress, story);
        Issue lateTask = issue(IssueType.TASK, done, story);

        assertThat(StoryServiceImpl.afterClose(story, before)).isFalse();
        assertThat(StoryServiceImpl.afterClose(story, after)).isTrue();
        assertThat(StoryServiceImpl.afterClose(story, lateTask)).isFalse();
        story.setClosedAt(null);
        assertThat(StoryServiceImpl.afterClose(story, after)).isFalse();
    }
}
