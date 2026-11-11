package com.taskflow.api.imports;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;

import java.util.Map;
import java.util.UUID;

/**
 * Las equivalencias de una importación: a qué corresponde en TaskFlow cada tipo, estado, prioridad y persona del
 * origen. Las claves son los nombres del origen (el identificador, en las personas).
 */
public record ImportMapping(Map<String, TypeTarget> types, Map<String, StateTarget> states,
                            Map<String, IssuePriority> priorities, Map<String, PersonTarget> people) {

    /** En qué se convierte un tipo del origen. {@code MODULE}: agrupa a las tareas que cuelgan de él (una épica). */
    public enum TypeTarget {
        MODULE, STORY, TASK, BUG, SKIP
    }

    /**
     * El estado del proyecto que lleva ese nombre; si no existe, se crea en ese grupo.
     */
    public record StateTarget(String name, StateGroup group) {
    }

    public enum PersonAction {
        /** Ya es miembro del workspace ({@link PersonTarget#userId}). */
        MEMBER,
        /** Se le invita ({@link PersonTarget#email}); sus tareas se le asignan cuando acepte. */
        INVITE,
        /** Sus tareas quedan sin asignar, con su nombre anotado. */
        UNASSIGNED
    }

    /**
     * @param roleId rol con el que se invita ({@code null} = «Miembro»)
     */
    public record PersonTarget(PersonAction action, UUID userId, String email, UUID roleId) {

        public static final PersonTarget NOBODY = new PersonTarget(PersonAction.UNASSIGNED, null, null, null);
    }

    public static ImportMapping empty() {
        return new ImportMapping(Map.of(), Map.of(), Map.of(), Map.of());
    }
}
