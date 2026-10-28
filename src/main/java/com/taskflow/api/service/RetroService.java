package com.taskflow.api.service;

import com.taskflow.api.dto.request.RetroNoteRequest;
import com.taskflow.api.dto.request.UpdateRetroNoteRequest;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.dto.response.RetroResponse;
import com.taskflow.api.dto.response.RetroVoteResponse;

import java.util.UUID;

/**
 * Retrospectiva de un ciclo. Las notas y los votos solo se pueden crear, editar o quitar mientras la retrospectiva
 * está abierta (empezó y no terminó); si no, responde 409.
 */
public interface RetroService {

    /** Si la retrospectiva no ha empezado, viene sin notas. Los votos se cuentan para {@code userId}. */
    RetroResponse getRetro(String slug, UUID projectId, UUID cycleId, UUID userId);

    /** Abre la retrospectiva; si ya estaba abierta (o terminada), no cambia nada. */
    RetroResponse start(String slug, UUID projectId, UUID cycleId, UUID userId);

    RetroNoteResponse createNote(String slug, UUID projectId, UUID cycleId, UUID userId, RetroNoteRequest request);

    /** Solo el autor de la nota o el project manager (403 si no). */
    RetroNoteResponse updateNote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId,
                                 UpdateRetroNoteRequest request);

    /** Solo el autor de la nota o el project manager (403 si no). */
    void deleteNote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId);

    /** Un voto por nota y como máximo 5 por retrospectiva (409 si ya votó por la nota o no le quedan votos). */
    RetroVoteResponse vote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId);

    /** Quita el voto del usuario; si no había votado, no cambia nada. */
    RetroVoteResponse unvote(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId);

    /**
     * Crea una tarea a partir de una acción ({@code ACTION}; si no, 400) en el primer estado «sin empezar» del
     * proyecto, con {@code userId} como creador, y la deja enlazada a la nota (409 si ya se había convertido).
     * Se puede hacer también con la retrospectiva terminada.
     */
    IssueResponse convert(String slug, UUID projectId, UUID cycleId, UUID noteId, UUID userId);

    /** Termina la retrospectiva (409 si no ha empezado); si ya había terminado, no cambia nada. */
    RetroResponse finish(String slug, UUID projectId, UUID cycleId, UUID userId);
}
