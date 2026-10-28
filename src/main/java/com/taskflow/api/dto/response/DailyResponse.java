package com.taskflow.api.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily de un proyecto para un día.
 *
 * @param date         el día de la reunión
 * @param previousDate día hábil anterior: el día antes o, si {@code date} es lunes, sábado o domingo, el viernes
 *                     anterior
 * @param people       miembros del proyecto y asignados a sus tareas abiertas, por nombre (aunque no tengan nada)
 * @param note         nota de la reunión de ese día; {@code null} si no hay
 */
public record DailyResponse(
        LocalDate date,
        LocalDate previousDate,
        List<DailyPerson> people,
        DailyNoteResponse note
) {

    /**
     * @param yesterday lo que la persona hizo desde {@code previousDate} hasta antes de {@code date}: una entrada por
     *                  tarea con el cambio más importante, máximo 8
     * @param today     sus tareas abiertas en curso (grupo STARTED) o con fecha límite hasta {@code date}, por fecha
     *                  límite, máximo 8
     * @param blockers  sus tareas abiertas con bloqueos abiertos
     */
    public record DailyPerson(
            UserSummaryResponse user,
            List<DailyChange> yesterday,
            List<RelatedIssueResponse> today,
            List<DailyBlocker> blockers
    ) {
    }

    /**
     * @param change «completó», «movió a {estado}», «creó», «comentó» o «actualizó» (cualquier otro cambio)
     */
    public record DailyChange(
            RelatedIssueResponse issue,
            String change
    ) {
    }

    /**
     * @param blockedBy tareas abiertas que la bloquean, por número
     */
    public record DailyBlocker(
            RelatedIssueResponse issue,
            List<IssueSummaryResponse> blockedBy
    ) {
    }
}
