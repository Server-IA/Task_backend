package com.taskflow.api.repository;

import java.util.UUID;

/**
 * Proyección con el avance de un ciclo o módulo: issues totales y completados.
 */
public interface IssueProgress {

    UUID getId();

    long getTotal();

    long getCompleted();
}
