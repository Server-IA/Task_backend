package com.taskflow.api.dto.response;

import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import com.taskflow.api.entity.enums.ImportVia;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.imports.ImportDefaults;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportPlan;
import com.taskflow.api.imports.ImportProgress;
import com.taskflow.api.imports.ImportReport;
import com.taskflow.api.imports.ImportSelection;
import com.taskflow.api.imports.csv.CsvImport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una importación, con lo que toca mostrar según por dónde va: lo que se encontró en el origen ({@code discovery})
 * desde que se conecta; las equivalencias propuestas ({@code mapping}) y lo que se va a crear ({@code preview}) cuando
 * ya se leyó lo elegido; el avance ({@code progress}) mientras lee, escribe o deshace; y el informe ({@code report})
 * cuando terminó. En el historial solo van los datos generales y el informe.
 *
 * @param connected si TaskFlow todavía tiene las credenciales del origen (hacen falta para leer y para los adjuntos;
 *                  se olvidan al terminar o al reiniciar el servidor)
 */
public record ImportJobResponse(
        UUID id,
        ImportSourceType source,
        ImportVia via,
        String sourceLabel,
        ImportStatus status,
        String error,
        String createdByName,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        boolean connected,
        Discovery discovery,
        ImportSelection selection,
        ImportDefaults.Proposal mapping,
        Preview preview,
        Progress progress,
        Report report
) {

    /**
     * @param account con qué cuenta se entró al origen
     * @param csv     solo con un archivo CSV: sus columnas
     */
    public record Discovery(String account, List<FoundProject> projects, Csv csv) {
    }

    /**
     * @param issueCount          cuántas tareas tiene ({@code -1} si no se sabe sin leerlas)
     * @param suggestedIdentifier el identificador que tendría como proyecto nuevo (libre en el workspace)
     * @param identifierTaken     si su clave en el origen ya la usa un proyecto del workspace
     */
    public record FoundProject(String id, String key, String name, int issueCount, String suggestedIdentifier,
                               boolean identifierTaken) {
    }

    public record Csv(String delimiter, int rows, List<CsvImport.Column> columns) {
    }

    /**
     * @param labels               etiquetas nuevas
     * @param newStates            estados que se crean
     * @param freeBytes            espacio libre del workspace para archivos
     * @param attachmentsAvailable si los adjuntos se pueden traer (hay conexión con el origen y almacenamiento)
     */
    public record Preview(ImportPlan.Totals totals, int labels, List<StateTarget> newStates, List<Sample> sample, long freeBytes,
                          boolean attachmentsAvailable) {
    }

    /**
     * Una tarea de muestra, como quedaría.
     *
     * @param code     su código, si se conserva el número del origen
     * @param invited  personas a las que se invita y a quienes se les asignará cuando acepten
     */
    public record Sample(IssueType type, String code, String title, String stateName, StateGroup stateGroup, String parentCode,
                         String module, String cycle, List<String> assignees, List<String> invited, Instant completedAt) {
    }

    public record Progress(int percent, List<ImportProgress.Stage> stages, String current) {
    }

    public record Report(int stories, int tasks, int bugs, int modules, int cycles, int comments, int attachments,
                         long attachmentBytes, int labels, int states, int relations, int invitations,
                         List<ImportReport.CreatedProject> projects, int skippedTotal, List<SkippedGroup> skipped) {
    }

    /**
     * @param items las primeras de ese motivo (la lista completa va en el informe que se descarga)
     */
    public record SkippedGroup(ImportReport.SkipReason reason, int count, List<ImportReport.Skipped> items) {
    }
}
