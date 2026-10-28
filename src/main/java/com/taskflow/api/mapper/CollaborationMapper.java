package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.ActivityFeedItemResponse;
import com.taskflow.api.dto.response.ActivityResponse;
import com.taskflow.api.dto.response.CommentResponse;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.dto.response.NotificationIssueResponse;
import com.taskflow.api.dto.response.NotificationResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.DailyNote;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueActivity;
import com.taskflow.api.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = UserMapper.class)
public interface CollaborationMapper {

    CommentResponse toCommentResponse(Comment comment);

    ActivityResponse toActivityResponse(IssueActivity activity);

    /** Necesita la tarea y su proyecto cargados (el código de la tarea usa el identificador del proyecto). */
    @Mapping(target = "project", source = "issue.project")
    ActivityFeedItemResponse toFeedItem(IssueActivity activity);

    NotificationResponse toNotificationResponse(Notification notification);

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "workspaceSlug", source = "project.workspace.slug")
    NotificationIssueResponse toNotificationIssue(Issue issue);

    DailyNoteResponse toDailyNoteResponse(DailyNote note);
}
