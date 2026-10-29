package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.ChecklistItemKind;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Criterio o paso que una plantilla agrega a la tarea creada con ella.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class TemplateChecklistItem {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChecklistItemKind kind;

    @Column(nullable = false, length = 500)
    private String text;
}
