package com.taskflow.api.imports;

import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportMapping.TypeTarget;
import com.taskflow.api.imports.ImportPlan.PlannedIssue;
import com.taskflow.api.imports.ImportPlan.PlannedProject;
import com.taskflow.api.imports.ImportSelection.Options;
import com.taskflow.api.imports.ImportSelection.ProjectChoice;
import com.taskflow.api.imports.csv.CsvDates;
import com.taskflow.api.imports.csv.CsvField;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Las reglas de la importación que no necesitan base de datos: fechas, columnas, equivalencias propuestas y jerarquía. */
class ImportRulesTest {

    @Test
    void lasFechasSeLeenEnLosFormatosDeLasHerramientasMasUsadas() {
        CsvDates dates = CsvDates.forValues(List.of());

        assertThat(dates.date("2026-10-14")).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(dates.instant("2026-10-14T15:45:00.000+0200")).isEqualTo(Instant.parse("2026-10-14T13:45:00Z"));
        assertThat(dates.instant("2026-10-14T15:45:00+02:00")).isEqualTo(Instant.parse("2026-10-14T13:45:00Z"));
        assertThat(dates.instant("2026-10-14 15:45")).isEqualTo(Instant.parse("2026-10-14T15:45:00Z"));
        // Jira
        assertThat(dates.instant("14/Oct/26 3:45 PM")).isEqualTo(Instant.parse("2026-10-14T15:45:00Z"));
        // Con el mes en letras, en inglés y en español
        assertThat(dates.date("Oct 14, 2026")).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(dates.date("14 de octubre de 2026")).isEqualTo(LocalDate.of(2026, 10, 14));
        // ClickUp: milisegundos
        assertThat(dates.instant("1791992700000")).isEqualTo(Instant.ofEpochMilli(1791992700000L));
        assertThat(dates.date("no es una fecha")).isNull();
        assertThat(dates.date("  ")).isNull();
    }

    @Test
    void enLasFechasConBarrasElOrdenSaleDeLaColumnaEntera() {
        // Sin pistas: día/mes
        assertThat(CsvDates.forValues(List.of("03/04/2026")).date("03/04/2026")).isEqualTo(LocalDate.of(2026, 4, 3));
        // Un 25 en la segunda posición solo puede ser un día: mes/día
        assertThat(CsvDates.forValues(List.of("03/04/2026", "12/25/2026")).date("03/04/2026")).isEqualTo(LocalDate.of(2026, 3, 4));
        // Un 25 en la primera: día/mes
        assertThat(CsvDates.forValues(List.of("25/12/2026 14:30")).instant("25/12/2026 14:30")).isEqualTo(Instant.parse("2026-12-25T14:30:00Z"));
        assertThat(CsvDates.forValues(List.of()).date("31/02/2026")).isNull();
    }

    @Test
    void lasColumnasSeReconocenPorSuNombreEnVariasHerramientasYDosIdiomas() {
        assertThat(CsvField.suggest("Summary")).isEqualTo(CsvField.TITLE);
        assertThat(CsvField.suggest("Task Name")).isEqualTo(CsvField.TITLE);
        assertThat(CsvField.suggest(" Título ")).isEqualTo(CsvField.TITLE);
        assertThat(CsvField.suggest("Fecha límite")).isEqualTo(CsvField.DUE_DATE);
        assertThat(CsvField.suggest("Due Date")).isEqualTo(CsvField.DUE_DATE);
        assertThat(CsvField.suggest("Custom field (Story Points)")).isEqualTo(CsvField.POINTS);
        assertThat(CsvField.suggest("Issue key")).isEqualTo(CsvField.KEY);
        assertThat(CsvField.suggest("Watchers")).isEqualTo(CsvField.IGNORE);
    }

    @Test
    void unCsvSinColumnaDeProyectoEsUnSoloProyectoYVariosSiLaTrae() {
        ImportBundle one = CsvImport.toBundle("Título,Persona\nUna,Ana <ana@x.com>\nDos,ana@x.com; Luis Pérez\n".getBytes(StandardCharsets.UTF_8),
                List.of(CsvField.TITLE, CsvField.ASSIGNEE), "tareas");

        assertThat(one.projects()).singleElement().satisfies(project -> {
            assertThat(project.name()).isEqualTo("tareas");
            assertThat(project.issues()).extracting(ExtIssue::title).containsExactly("Una", "Dos");
            assertThat(project.issues().get(1).assigneeIds()).containsExactly("ana@x.com", "luis pérez");
        });
        assertThat(one.users()).extracting(user -> user.name() + "|" + user.email()).containsExactly("Ana|ana@x.com", "Luis Pérez|null");

        ImportBundle two = CsvImport.toBundle("Summary,Project key,Project name\nA,WEB,Sitio web\nB,APP,App móvil\nC,WEB,Sitio web\n"
                .getBytes(StandardCharsets.UTF_8), List.of(CsvField.TITLE, CsvField.PROJECT_KEY, CsvField.PROJECT_NAME), "archivo");

        assertThat(two.projects()).extracting(project -> project.key() + " " + project.name() + " " + project.issues().size())
                .containsExactly("WEB Sitio web 2", "APP App móvil 1");
    }

    @Test
    void laCategoriaDeUnEstadoSaleDeSuNombreCuandoElOrigenNoLaDice() {
        assertThat(CsvImport.category("", "Listo", false)).isEqualTo(StatusCategory.DONE);
        assertThat(CsvImport.category("", "Terminadas", false)).isEqualTo(StatusCategory.DONE);
        assertThat(CsvImport.category("", "En revisión", false)).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(CsvImport.category("", "Descartado", false)).isEqualTo(StatusCategory.CANCELLED);
        assertThat(CsvImport.category("", "Backlog", false)).isEqualTo(StatusCategory.BACKLOG);
        // Una lista que se llama «lista de algo» no es un estado terminado
        assertThat(CsvImport.category("", "Lista de pendientes", false)).isEqualTo(StatusCategory.BACKLOG);
        assertThat(CsvImport.category("", "Wishlist", false)).isEqualTo(StatusCategory.TODO);
        assertThat(CsvImport.category("", "To do list", false)).isEqualTo(StatusCategory.TODO);
        // Lo que diga el archivo manda sobre el nombre
        assertThat(CsvImport.category("Done", "Publicado en web", false)).isEqualTo(StatusCategory.DONE);
        assertThat(CsvImport.category("", "Publicado en web", true)).isEqualTo(StatusCategory.DONE);
    }

    @Test
    void lasEquivalenciasPropuestasSalenDelNombre() {
        assertThat(ImportDefaults.priorityFor("Highest")).isEqualTo(IssuePriority.URGENT);
        assertThat(ImportDefaults.priorityFor("High")).isEqualTo(IssuePriority.HIGH);
        assertThat(ImportDefaults.priorityFor("Media")).isEqualTo(IssuePriority.MEDIUM);
        assertThat(ImportDefaults.priorityFor("Lowest")).isEqualTo(IssuePriority.LOW);
        assertThat(ImportDefaults.priorityFor("Sin prioridad")).isEqualTo(IssuePriority.NONE);

        List<StateTarget> factory = ImportDefaults.FACTORY_STATES;
        assertThat(ImportDefaults.stateFor("To Do", StatusCategory.TODO, factory)).isEqualTo(new StateTarget("Todo", StateGroup.UNSTARTED));
        assertThat(ImportDefaults.stateFor("in progress", StatusCategory.IN_PROGRESS, factory)).isEqualTo(new StateTarget("In Progress", StateGroup.STARTED));
        assertThat(ImportDefaults.stateFor("Resuelta", StatusCategory.DONE, factory)).isEqualTo(new StateTarget("Done", StateGroup.COMPLETED));
        // Un estado «en curso» con nombre propio se conserva como estado nuevo
        assertThat(ImportDefaults.stateFor("Code Review", StatusCategory.IN_PROGRESS, factory)).isEqualTo(new StateTarget("Code Review", StateGroup.STARTED));
        // Si el proyecto ya tiene uno que se llama igual, es ese
        assertThat(ImportDefaults.stateFor("code review", StatusCategory.IN_PROGRESS, List.of(new StateTarget("Code Review", StateGroup.STARTED))))
                .isEqualTo(new StateTarget("Code Review", StateGroup.STARTED));
    }

    @Test
    void elIdentificadorPropuestoEsValidoYLibre() {
        assertThat(ImportDefaults.identifier("PORT", taken -> false)).isEqualTo("PORT");
        assertThat(ImportDefaults.identifier("App móvil 2026", taken -> false)).isEqualTo("APPMOVIL2026");
        assertThat(ImportDefaults.identifier("2026 planes", taken -> false)).isEqualTo("PLANES");
        assertThat(ImportDefaults.identifier("¿?", taken -> false)).isEqualTo("IMP");
        assertThat(ImportDefaults.identifier("AMC", Set.of("AMC", "AMC2")::contains)).isEqualTo("AMC3");
        assertThat(ImportDefaults.identifier("ABCDEFGHIJKL", Set.of("ABCDEFGHIJKL")::contains)).isEqualTo("ABCDEFGHIJK2");
    }

    private static ExtIssue issue(String id, Kind kind, String parentId, StatusCategory category, List<String> sprints) {
        return new ExtIssue(id, id, null, kind.name(), kind, "Tarea " + id, null, category.name(), category, null, List.of(), null, List.of(),
                parentId, List.of(), sprints, null, null, null, null, null, null, Map.of(), List.of(), List.of(), 0, List.of(), List.of(), null, false);
    }

    private static PlannedProject plan(List<ExtIssue> issues, List<ExtSprint> sprints, Options options, Map<String, TypeTarget> types) {
        ImportBundle bundle = new ImportBundle(List.of(new ExtProject("p", "P", "Proyecto", null, null, List.of(), sprints, List.of(), issues)), List.of());
        ImportSelection selection = new ImportSelection(List.of(new ProjectChoice("p", null, "Proyecto", "P")), options, null);
        return ImportPlan.of(bundle, selection, new ImportMapping(types, Map.of(), Map.of(), Map.of())).projects().get(0);
    }

    @Test
    void laJerarquiaDelOrigenEncajaEnLaDeTaskFlow() {
        List<ExtIssue> issues = List.of(
                issue("sub", Kind.SUBTASK, "story", StatusCategory.TODO, List.of()),
                issue("story", Kind.STORY, "epic", StatusCategory.IN_PROGRESS, List.of()),
                issue("epic", Kind.EPIC, null, StatusCategory.IN_PROGRESS, List.of()),
                issue("bug", Kind.BUG, "fuera", StatusCategory.TODO, List.of()),
                issue("hija", Kind.STORY, "story", StatusCategory.TODO, List.of()));

        PlannedProject project = plan(issues, List.of(), Options.DEFAULTS, Map.of());

        // La épica es un módulo; la historia entra a él y va antes que su subtarea
        assertThat(project.modules()).containsOnlyKeys("e:epic");
        assertThat(project.issues()).extracting(issue -> issue.source().id()).containsExactly("story", "sub", "bug", "hija");
        PlannedIssue story = project.issues().get(0);
        assertThat(story.type()).isEqualTo(IssueType.STORY);
        assertThat(story.parentId()).isNull();
        assertThat(story.moduleKeys()).containsExactly("e:epic");
        assertThat(project.issues().get(1).parentId()).isEqualTo("story");
        // Un padre que no está en el proyecto, y una historia que colgaba de otra: sueltos
        assertThat(project.issues().get(2).parentId()).isNull();
        assertThat(project.issues().get(3).parentId()).isNull();
    }

    @Test
    void sinLoTerminadoNiLosSprintsCerradosYConUnTipoDescartado() {
        List<ExtSprint> sprints = List.of(new ExtSprint("s1", "Sprint 1", null, null, Instant.parse("2026-09-14T00:00:00Z")),
                new ExtSprint("s2", "Sprint 2", null, null, null), new ExtSprint("s3", "Sprint 3", null, null, null));
        List<ExtIssue> issues = List.of(
                issue("a", Kind.TASK, null, StatusCategory.DONE, List.of("s1")),
                issue("b", Kind.TASK, "a", StatusCategory.TODO, List.of("s1", "s2")),
                issue("c", Kind.BUG, null, StatusCategory.TODO, List.of("s2")),
                issue("d", Kind.TASK, null, StatusCategory.CANCELLED, List.of()));

        PlannedProject all = plan(issues, sprints, Options.DEFAULTS, Map.of());
        // De varios sprints vale el último; el sprint sin tareas no se crea
        assertThat(all.issues()).extracting(issue -> issue.source().id() + ":" + issue.sprintId()).containsExactly("a:s1", "b:s2", "c:s2", "d:null");
        assertThat(all.sprints()).extracting(ExtSprint::id).containsExactly("s1", "s2");

        PlannedProject open = plan(issues, sprints, new Options(true, false, true, true, false, false), Map.of("BUG", TypeTarget.SKIP));
        // Sin «a» (terminada), su hija queda suelta; sin el bug; sin el sprint cerrado
        assertThat(open.issues()).extracting(issue -> issue.source().id()).containsExactly("b");
        assertThat(open.issues().get(0).parentId()).isNull();
        assertThat(open.issues().get(0).sprintId()).isEqualTo("s2");
        assertThat(open.sprints()).extracting(ExtSprint::id).containsExactly("s2");
    }

    @Test
    void unCirculoDePadresEnElArchivoNoSeEscribe() {
        PlannedProject project = plan(List.of(issue("a", Kind.TASK, "b", StatusCategory.TODO, List.of()),
                issue("b", Kind.TASK, "a", StatusCategory.TODO, List.of())), List.of(), Options.DEFAULTS, Map.of());

        assertThat(project.issues()).hasSize(2);
        assertThat(project.issues()).filteredOn(issue -> issue.parentId() != null).isEmpty();
    }
}
