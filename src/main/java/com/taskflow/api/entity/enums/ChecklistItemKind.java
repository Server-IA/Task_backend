package com.taskflow.api.entity.enums;

/**
 * Tipo de elemento de la checklist de una tarea. El orden de las constantes es el orden en que se muestran.
 */
public enum ChecklistItemKind {
    /** Criterio de aceptación, por ejemplo «Dado …, cuando …, entonces …». */
    CRITERION,
    /** Paso de la checklist. */
    CHECK
}
