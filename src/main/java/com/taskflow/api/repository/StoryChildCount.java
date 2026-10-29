package com.taskflow.api.repository;

import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;

/**
 * Cuántas hijas de una historia hay de un tipo en un grupo de estados.
 */
public record StoryChildCount(IssueType type, StateGroup group, long count) {
}
