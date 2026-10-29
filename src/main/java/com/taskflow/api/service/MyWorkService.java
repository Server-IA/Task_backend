package com.taskflow.api.service;

import com.taskflow.api.dto.request.MyWorkView;
import com.taskflow.api.dto.response.MyWorkResponse;

import java.util.UUID;

public interface MyWorkService {

    /** Tareas abiertas del usuario (según la vista) en los proyectos del workspace a los que tiene acceso. */
    MyWorkResponse getMyWork(String slug, UUID userId, MyWorkView view);
}
