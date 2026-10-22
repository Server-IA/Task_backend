package com.taskflow.api.service.impl;

import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Búsquedas comunes entre servicios que lanzan 404 si el recurso no existe.
 */
@Component
@RequiredArgsConstructor
class ResourceLookup {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;

    User user(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    Workspace workspace(String slug) {
        return workspaceRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado"));
    }
}
