package com.taskflow.api.dto.response;

/**
 * La nota después de votar o quitar el voto, y los votos que le quedan al usuario en la retrospectiva.
 */
public record RetroVoteResponse(
        RetroNoteResponse note,
        int myVotesLeft
) {
}
