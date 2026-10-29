package com.taskflow.api.entity.enums;

/**
 * Tipo de una tarea. Todo sigue siendo una tarea con un campo {@code type}:
 * <ul>
 *   <li>{@code STORY} (historia): el qué y el porqué, con sus criterios de aceptación. Nadie la trabaja directo: se
 *       parte en tareas (sus hijas) y su estado avanza solo con ellas. Solo el project manager la crea, la parte, la
 *       cierra y la reabre.</li>
 *   <li>{@code TASK} (tarea): el trabajo concreto; casi siempre hija de una historia, o suelta.</li>
 *   <li>{@code BUG}: algo que no funciona como debe. Suelto o ligado a la historia donde apareció: no cuenta en su
 *       progreso, pero uno abierto la frena antes de «lista para revisar».</li>
 * </ul>
 */
public enum IssueType {
    STORY,
    TASK,
    BUG
}
