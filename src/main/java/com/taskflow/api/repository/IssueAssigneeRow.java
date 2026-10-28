package com.taskflow.api.repository;

import com.taskflow.api.entity.User;

import java.util.UUID;

/**
 * Un asignado de una tarea (una fila por cada par tarea-usuario).
 */
public record IssueAssigneeRow(UUID issueId, User user) {
}
