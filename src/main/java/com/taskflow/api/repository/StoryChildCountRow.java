package com.taskflow.api.repository;

import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;

import java.util.UUID;

/**
 * Cuántas hijas de la historia {@code storyId} hay de un tipo en un grupo de estados (varias historias a la vez).
 */
public record StoryChildCountRow(UUID storyId, IssueType type, StateGroup group, long count) {

    public StoryChildCount toCount() {
        return new StoryChildCount(type, group, count);
    }
}
