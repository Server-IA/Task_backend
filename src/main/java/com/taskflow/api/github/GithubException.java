package com.taskflow.api.github;

/**
 * GitHub no hizo lo que se le pidió. Lleva el estado HTTP (0 si ni siquiera hubo respuesta) y lo que dijo GitHub, para
 * el registro; lo que ve la persona lo decide quien la atrapa.
 */
public class GithubException extends RuntimeException {

    private final int status;

    public GithubException(int status, String detail, Throwable cause) {
        super("GitHub " + status + (detail == null || detail.isBlank() ? "" : ": " + detail), cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
