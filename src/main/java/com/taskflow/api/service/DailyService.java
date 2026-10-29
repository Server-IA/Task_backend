package com.taskflow.api.service;

import com.taskflow.api.dto.request.DailyNoteRequest;
import com.taskflow.api.dto.response.DailyNoteResponse;
import com.taskflow.api.dto.response.DailyResponse;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily (reunión diaria) de un proyecto. {@code date = null} es hoy en la zona horaria de la aplicación.
 */
public interface DailyService {

    /**
     * Qué hizo cada persona desde el día hábil anterior, en qué está hoy, qué la bloquea y la nota del día. Las tareas
     * que bloquean y son de un proyecto al que {@code userId} no tiene acceso van sin título ni asignados.
     */
    DailyResponse getDaily(String slug, UUID projectId, UUID userId, LocalDate date);

    /** Guarda la nota del día; con texto vacío la borra y devuelve vacío. */
    Optional<DailyNoteResponse> saveNote(String slug, UUID projectId, UUID userId, LocalDate date,
                                         DailyNoteRequest request);
}
