package com.taskflow.api.imports;

import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.imports.ImportMapping.PersonAction;
import com.taskflow.api.imports.ImportMapping.PersonTarget;
import com.taskflow.api.imports.ImportMapping.TypeTarget;
import com.taskflow.api.imports.ImportSelection.Options;
import com.taskflow.api.imports.ImportSelection.ProjectChoice;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtProject;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;
import com.taskflow.api.storage.FileTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qué se va a crear, decidido una sola vez con lo leído del origen, lo que se eligió traer y las equivalencias: lo usan
 * por igual la pantalla de revisión (cuántas cosas y con qué avisos) y el importador (qué escribir). Aquí están las
 * reglas de cómo encaja la jerarquía del origen en la de TaskFlow:
 * <ul>
 *   <li>un tipo que se convierte en módulo (una épica) no es una tarea: las que cuelgan de él entran a ese módulo;</li>
 *   <li>una historia nunca cuelga de otra tarea;</li>
 *   <li>una tarea o un bug cuelga de su padre si ese padre también entra; si no, queda suelto;</li>
 *   <li>de los sprints de una tarea vale el último que se trae (en TaskFlow una tarea está en un solo ciclo).</li>
 * </ul>
 */
public final class ImportPlan {

    /** Hasta dónde se sube por la cadena de padres buscando un círculo. */
    private static final int MAX_PARENT_DEPTH = 50;

    private final List<PlannedProject> projects;
    private final Options options;
    private final ImportMapping mapping;

    private ImportPlan(List<PlannedProject> projects, Options options, ImportMapping mapping) {
        this.projects = projects;
        this.options = options;
        this.mapping = mapping;
    }

    /**
     * @param modules los módulos que se crean, por su clave ({@code m:<id>} un módulo del origen, {@code e:<id>} una
     *                épica), con su nombre y descripción
     * @param sprints los sprints que se traen
     */
    public record PlannedProject(ExtProject source, ProjectChoice choice, Map<String, PlannedModule> modules,
                                 List<ExtSprint> sprints, List<PlannedIssue> issues) {
    }

    public record PlannedModule(String name, String description, java.time.LocalDate startDate, java.time.LocalDate targetDate) {
    }

    /**
     * @param parentId   la tarea (del origen) de la que cuelga en TaskFlow, o {@code null}
     * @param moduleKeys los módulos a los que entra (claves de {@link PlannedProject#modules})
     * @param sprintId   el sprint del que queda en el ciclo, o {@code null}
     */
    public record PlannedIssue(ExtIssue source, IssueType type, String parentId, List<String> moduleKeys, String sprintId) {
    }

    public static ImportPlan of(ImportBundle bundle, ImportSelection selection, ImportMapping mapping) {
        Options options = selection.optionsOrDefault();
        Map<String, ProjectChoice> choices = new HashMap<>();
        if (selection.projects() != null) {
            selection.projects().forEach(choice -> choices.put(choice.externalId(), choice));
        }
        List<PlannedProject> planned = new ArrayList<>();
        for (ExtProject project : bundle.projects()) {
            ProjectChoice choice = choices.get(project.id());
            if (choice != null) {
                planned.add(plan(project, choice, options, mapping));
            }
        }
        return new ImportPlan(planned, options, mapping);
    }

    private static PlannedProject plan(ExtProject project, ProjectChoice choice, Options options, ImportMapping mapping) {
        Map<String, ExtIssue> byId = new HashMap<>();
        project.issues().forEach(issue -> byId.put(issue.id(), issue));

        Map<String, PlannedModule> modules = new LinkedHashMap<>();
        if (project.modules() != null) {
            project.modules().forEach(module -> modules.put("m:" + module.id(),
                    new PlannedModule(module.name(), module.description(), module.startDate(), module.targetDate())));
        }
        Set<String> kept = new HashSet<>();
        for (ExtIssue issue : project.issues()) {
            TypeTarget target = typeOf(issue, mapping);
            if (target == TypeTarget.MODULE) {
                modules.put("e:" + issue.id(), new PlannedModule(issue.title(), issue.description(), issue.startDate(), issue.dueDate()));
            } else if (target != TypeTarget.SKIP && included(issue, options)) {
                kept.add(issue.id());
            }
        }
        Set<String> sprintIds = new LinkedHashSet<>();
        List<ExtSprint> sprints = new ArrayList<>();
        if (project.sprints() != null) {
            for (ExtSprint sprint : project.sprints()) {
                if (options.closedSprints() || sprint.completedAt() == null) {
                    sprints.add(sprint);
                    sprintIds.add(sprint.id());
                }
            }
        }

        List<PlannedIssue> issues = new ArrayList<>();
        for (ExtIssue issue : project.issues()) {
            if (!kept.contains(issue.id())) {
                continue;
            }
            IssueType type = issueType(typeOf(issue, mapping));
            List<String> moduleKeys = new ArrayList<>();
            if (issue.moduleIds() != null) {
                issue.moduleIds().stream().map(id -> "m:" + id).filter(modules::containsKey).forEach(moduleKeys::add);
            }
            String parentId = null;
            ExtIssue parent = issue.parentId() == null ? null : byId.get(issue.parentId());
            if (parent != null && modules.containsKey("e:" + parent.id())) {
                moduleKeys.add("e:" + parent.id());
            } else if (parent != null && type != IssueType.STORY && kept.contains(parent.id())
                    && !createsCycle(issue, parent, byId)) {
                parentId = parent.id();
            }
            String sprintId = null;
            if (issue.sprintIds() != null) {
                for (String id : issue.sprintIds()) {
                    if (sprintIds.contains(id)) {
                        sprintId = id;
                    }
                }
            }
            issues.add(new PlannedIssue(issue, type, parentId, List.copyOf(new LinkedHashSet<>(moduleKeys)), sprintId));
        }
        // Un sprint del que no queda ninguna tarea no se crea
        Set<String> usedSprints = new HashSet<>();
        issues.forEach(issue -> usedSprints.add(issue.sprintId()));
        sprints.removeIf(sprint -> !usedSprints.contains(sprint.id()));
        return new PlannedProject(project, choice, modules, sprints, parentsFirst(issues));
    }

    private static TypeTarget typeOf(ExtIssue issue, ImportMapping mapping) {
        TypeTarget chosen = mapping.types() == null || issue.type() == null ? null : mapping.types().get(issue.type());
        return chosen != null ? chosen : ImportDefaults.typeFor(issue.kind());
    }

    private static IssueType issueType(TypeTarget target) {
        return switch (target) {
            case STORY -> IssueType.STORY;
            case BUG -> IssueType.BUG;
            default -> IssueType.TASK;
        };
    }

    private static boolean included(ExtIssue issue, Options options) {
        boolean closed = issue.statusCategory() == StatusCategory.DONE || issue.statusCategory() == StatusCategory.CANCELLED;
        return (options.completed() || !closed) && (options.archived() || !issue.archived());
    }

    /** Si colgar la tarea de ese padre cerraría un círculo (un archivo mal armado: A cuelga de B y B de A). */
    private static boolean createsCycle(ExtIssue issue, ExtIssue parent, Map<String, ExtIssue> byId) {
        ExtIssue current = parent;
        for (int depth = 0; current != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (current.id().equals(issue.id())) {
                return true;
            }
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return current != null;
    }

    /** Las tareas en un orden en que cada padre va antes que sus hijas (para escribirlas en ese orden). */
    private static List<PlannedIssue> parentsFirst(List<PlannedIssue> issues) {
        Map<String, PlannedIssue> byId = new LinkedHashMap<>();
        issues.forEach(issue -> byId.put(issue.source().id(), issue));
        List<PlannedIssue> ordered = new ArrayList<>(issues.size());
        Set<String> placed = new HashSet<>();
        for (PlannedIssue issue : issues) {
            place(issue, byId, placed, ordered);
        }
        return ordered;
    }

    private static void place(PlannedIssue issue, Map<String, PlannedIssue> byId, Set<String> placed, List<PlannedIssue> ordered) {
        List<PlannedIssue> chain = new ArrayList<>();
        PlannedIssue current = issue;
        while (current != null && !placed.contains(current.source().id())) {
            chain.add(current);
            placed.add(current.source().id());
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            ordered.add(chain.get(index));
        }
    }

    public List<PlannedProject> projects() {
        return projects;
    }

    public Options options() {
        return options;
    }

    public ImportMapping mapping() {
        return mapping;
    }

    /** A quién corresponde una persona del origen; sin equivalencia, a nadie. */
    public PersonTarget person(String externalId) {
        PersonTarget target = externalId == null || mapping.people() == null ? null : mapping.people().get(externalId);
        return target != null ? target : PersonTarget.NOBODY;
    }

    public int issueCount() {
        return projects.stream().mapToInt(project -> project.issues().size()).sum();
    }

    /**
     * Lo que se va a crear, en números (pantalla «Revisar»).
     *
     * @param subtasks        de las tareas, las que cuelgan de otra tarea o de un bug
     * @param attachmentBytes lo que pesan los adjuntos que sí se traen
     * @param tooBig          adjuntos que pasan del límite por archivo
     * @param notAllowed      adjuntos de un tipo que TaskFlow no acepta
     * @param withPoints      tareas con story points u horas registradas (quedan anotados en la descripción)
     * @param unassigned      tareas que pierden a alguna persona asignada (queda anotada)
     * @param invitations     personas a las que se invita
     */
    public record Totals(int projects, int modules, int cycles, int closedCycles, int stories, int tasks, int subtasks, int bugs,
                         int comments, int attachments, long attachmentBytes, int tooBig, int notAllowed, int withPoints,
                         int unassigned, int invitations) {
    }

    public Totals totals() {
        int newProjects = 0;
        int modules = 0;
        int cycles = 0;
        int closedCycles = 0;
        int stories = 0;
        int tasks = 0;
        int subtasks = 0;
        int bugs = 0;
        int comments = 0;
        int attachments = 0;
        long bytes = 0;
        int tooBig = 0;
        int notAllowed = 0;
        int withPoints = 0;
        int unassigned = 0;
        Set<String> invited = new HashSet<>();
        for (PlannedProject project : projects) {
            newProjects += project.choice().createsProject() ? 1 : 0;
            modules += project.modules().size();
            cycles += project.sprints().size();
            closedCycles += (int) project.sprints().stream().filter(sprint -> sprint.completedAt() != null).count();
            Map<String, IssueType> types = new HashMap<>();
            project.issues().forEach(issue -> types.put(issue.source().id(), issue.type()));
            for (PlannedIssue issue : project.issues()) {
                switch (issue.type()) {
                    case STORY -> stories++;
                    case BUG -> bugs++;
                    case TASK -> {
                        tasks++;
                        if (issue.parentId() != null && types.get(issue.parentId()) != IssueType.STORY) {
                            subtasks++;
                        }
                    }
                }
                ExtIssue source = issue.source();
                if (options.comments()) {
                    comments += Math.max(source.commentCount(), source.comments() == null ? 0 : source.comments().size());
                }
                if (options.attachments() && source.attachments() != null) {
                    for (ExtAttachment attachment : source.attachments()) {
                        switch (check(attachment)) {
                            case OK -> {
                                attachments++;
                                bytes += attachment.size();
                            }
                            case TOO_BIG -> tooBig++;
                            case NOT_ALLOWED -> notAllowed++;
                        }
                    }
                }
                if (source.points() != null || (source.secondsSpent() != null && source.secondsSpent() > 0)) {
                    withPoints++;
                }
                boolean lost = false;
                if (source.assigneeIds() != null) {
                    for (String assignee : source.assigneeIds()) {
                        PersonTarget target = person(assignee);
                        lost |= target.action() == PersonAction.UNASSIGNED;
                        if (target.action() == PersonAction.INVITE) {
                            invited.add(assignee);
                        }
                    }
                }
                unassigned += lost ? 1 : 0;
            }
        }
        return new Totals(newProjects, modules, cycles, closedCycles, stories, tasks, subtasks, bugs, comments, attachments, bytes,
                tooBig, notAllowed, withPoints, unassigned, invited.size());
    }

    public enum AttachmentCheck {
        OK, TOO_BIG, NOT_ALLOWED
    }

    /** Si un adjunto del origen cabe en TaskFlow: por su tipo (según la extensión) y por su tamaño. */
    public static AttachmentCheck check(ExtAttachment attachment) {
        return FileTypes.byExtension(attachment.name() == null ? "" : attachment.name())
                .map(type -> attachment.size() > type.limit() ? AttachmentCheck.TOO_BIG : AttachmentCheck.OK)
                .orElse(AttachmentCheck.NOT_ALLOWED);
    }

    /** Los tipos del origen que hay entre las tareas, con cuántas tiene cada uno y lo que es. */
    public static Map<String, Kind> typeKinds(ImportBundle bundle) {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (ExtProject project : bundle.projects()) {
            for (ExtIssue issue : project.issues()) {
                if (issue.type() != null) {
                    kinds.putIfAbsent(issue.type(), issue.kind());
                }
            }
        }
        return kinds;
    }
}
