package com.taskflow.api.imports;

import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.imports.ImportMapping.PersonAction;
import com.taskflow.api.imports.ImportMapping.PersonTarget;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportMapping.TypeTarget;
import com.taskflow.api.imports.ImportPlan.PlannedIssue;
import com.taskflow.api.imports.ImportPlan.PlannedProject;
import com.taskflow.api.imports.csv.CsvField;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.Kind;
import com.taskflow.api.imports.model.ImportBundle.StatusCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Las equivalencias que TaskFlow propone antes de que la persona toque nada: cada tipo, estado, prioridad y persona del
 * origen con lo que le corresponde. Manda lo que la persona ya eligió en esta importación; después, lo que eligió en la
 * anterior del mismo origen; y si no, lo que se deduce del nombre.
 */
public final class ImportDefaults {

    /** Los estados con los que nace un proyecto (ver {@code ProjectServiceImpl}): lo que se ofrece para uno nuevo. */
    public static final List<StateTarget> FACTORY_STATES = List.of(
            new StateTarget("Backlog", StateGroup.BACKLOG), new StateTarget("Todo", StateGroup.UNSTARTED),
            new StateTarget("In Progress", StateGroup.STARTED), new StateTarget("Done", StateGroup.COMPLETED),
            new StateTarget("Cancelled", StateGroup.CANCELLED));

    private static final int STATE_NAME_MAX = 50;
    private static final int IDENTIFIER_MAX = 12;

    private ImportDefaults() {
    }

    public record TypeRow(String source, Kind kind, int count, TypeTarget target) {
    }

    public record StateRow(String source, StatusCategory category, int count, StateTarget target) {
    }

    public record PriorityRow(String source, int count, IssuePriority target) {
    }

    /**
     * @param count       cuántas tareas tiene asignadas
     * @param matchedName el nombre en TaskFlow de la persona con la que se emparejó, si es miembro
     */
    public record PersonRow(String id, String name, String email, int count, PersonTarget target, String matchedName) {
    }

    /**
     * @param stateChoices los estados entre los que se puede elegir (además de crear uno con el nombre del origen)
     */
    public record Proposal(List<TypeRow> types, List<StateRow> states, List<PriorityRow> priorities, List<PersonRow> people,
                           List<StateTarget> stateChoices) {

        /** Las equivalencias, tal como quedarían si la persona no cambia nada. */
        public ImportMapping toMapping() {
            Map<String, TypeTarget> typeMap = new LinkedHashMap<>();
            types.forEach(row -> typeMap.put(row.source(), row.target()));
            Map<String, StateTarget> stateMap = new LinkedHashMap<>();
            states.forEach(row -> stateMap.put(row.source(), row.target()));
            Map<String, IssuePriority> priorityMap = new LinkedHashMap<>();
            priorities.forEach(row -> priorityMap.put(row.source(), row.target()));
            Map<String, PersonTarget> personMap = new LinkedHashMap<>();
            people.forEach(row -> personMap.put(row.id(), row.target()));
            return new ImportMapping(typeMap, stateMap, priorityMap, personMap);
        }
    }

    /**
     * @param chosen   lo que la persona ya guardó en esta importación (puede ser {@code null})
     * @param previous las equivalencias de la última importación del mismo origen (puede ser {@code null})
     * @param members  los miembros del workspace
     * @param states   los estados entre los que se puede elegir: los de los proyectos existentes a los que se agrega
     *                 y, si se crea alguno, los de fábrica
     */
    public static Proposal propose(ImportBundle bundle, ImportSelection selection, ImportMapping chosen, ImportMapping previous,
                                   Collection<User> members, List<StateTarget> states) {
        // Con todo como tarea: aquí interesa qué hay en el origen, no en qué se convierte
        ImportPlan everything = ImportPlan.of(bundle, selection, new ImportMapping(Map.of(), Map.of(), Map.of(), Map.of()));
        Map<String, Kind> kinds = new LinkedHashMap<>();
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, StatusCategory> categories = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> priorityCounts = new LinkedHashMap<>();
        Map<String, Integer> personCounts = new LinkedHashMap<>();
        for (PlannedProject project : everything.projects()) {
            // Las épicas no están en el plan (son módulos por defecto): se cuentan del origen
            for (ExtIssue issue : project.source().issues()) {
                if (issue.type() != null) {
                    kinds.putIfAbsent(issue.type(), issue.kind());
                    typeCounts.merge(issue.type(), 1, Integer::sum);
                }
            }
            for (PlannedIssue planned : project.issues()) {
                ExtIssue issue = planned.source();
                categories.putIfAbsent(issue.status(), issue.statusCategory());
                statusCounts.merge(issue.status(), 1, Integer::sum);
                if (issue.priority() != null) {
                    priorityCounts.merge(issue.priority(), 1, Integer::sum);
                }
                if (issue.assigneeIds() != null) {
                    issue.assigneeIds().forEach(id -> personCounts.merge(id, 1, Integer::sum));
                }
                if (issue.creatorId() != null) {
                    personCounts.putIfAbsent(issue.creatorId(), 0);
                }
                if (issue.comments() != null) {
                    for (ExtComment comment : issue.comments()) {
                        if (comment.authorId() != null) {
                            personCounts.putIfAbsent(comment.authorId(), 0);
                        }
                    }
                }
            }
        }

        List<TypeRow> types = new ArrayList<>();
        kinds.forEach((name, kind) -> types.add(new TypeRow(name, kind, typeCounts.get(name),
                first(pick(chosen, previous, mapping -> mapping.types(), name), typeFor(kind)))));

        List<StateRow> stateRows = new ArrayList<>();
        categories.forEach((name, category) -> stateRows.add(new StateRow(name, category, statusCounts.get(name),
                first(pick(chosen, previous, mapping -> mapping.states(), name), stateFor(name, category, states)))));

        List<PriorityRow> priorities = new ArrayList<>();
        priorityCounts.forEach((name, count) -> priorities.add(new PriorityRow(name, count,
                first(pick(chosen, previous, mapping -> mapping.priorities(), name), priorityFor(name)))));

        Map<String, ExtUser> users = new HashMap<>();
        if (bundle.users() != null) {
            bundle.users().forEach(user -> users.put(user.id(), user));
        }
        Map<UUID, User> memberById = new HashMap<>();
        members.forEach(member -> memberById.put(member.getId(), member));
        List<PersonRow> people = new ArrayList<>();
        personCounts.forEach((id, count) -> {
            ExtUser user = users.getOrDefault(id, new ExtUser(id, id, null));
            PersonTarget target = pick(chosen, previous, mapping -> mapping.people(), id);
            // Una equivalencia vieja con alguien que ya no es miembro no vale
            if (target == null || (target.action() == PersonAction.MEMBER && !memberById.containsKey(target.userId()))) {
                target = personFor(user, members);
            }
            // A quien se invitó en una importación anterior y ya entró al workspace no se le invita otra vez (la
            // invitación fallaría y sus tareas quedarían esperando para siempre): es miembro, se le asignan ya
            if (target.action() == PersonAction.INVITE) {
                String email = target.email() != null && !target.email().isBlank() ? target.email() : user.email();
                User already = email == null ? null : members.stream()
                        .filter(member -> email.strip().equalsIgnoreCase(member.getEmail())).findFirst().orElse(null);
                if (already != null) {
                    target = new PersonTarget(PersonAction.MEMBER, already.getId(), null, null);
                }
            }
            User matched = target.action() == PersonAction.MEMBER ? memberById.get(target.userId()) : null;
            people.add(new PersonRow(id, user.name(), user.email(), count, target, matched == null ? null : matched.getFullName()));
        });
        // Primero quien más tareas tiene
        people.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return new Proposal(types, stateRows, priorities, people, states);
    }

    private interface Section<T> {
        Map<String, T> of(ImportMapping mapping);
    }

    private static <T> T pick(ImportMapping chosen, ImportMapping previous, Section<T> section, String key) {
        for (ImportMapping mapping : new ImportMapping[]{chosen, previous}) {
            Map<String, T> values = mapping == null ? null : section.of(mapping);
            if (values != null && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static <T> T first(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Un identificador de proyecto libre a partir del que se pidió (una clave, un nombre): «AMC», y si ya existe,
     * «AMC2», «AMC3»… Empieza por letra y tiene hasta 12 letras o números, como pide un proyecto.
     */
    public static String identifier(String wanted, java.util.function.Predicate<String> taken) {
        String letters = CsvField.normalize(wanted).replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT).replaceAll("^[0-9]+", "");
        String base = letters.isEmpty() ? "IMP" : letters.substring(0, Math.min(letters.length(), IDENTIFIER_MAX));
        String candidate = base;
        for (int number = 2; taken.test(candidate); number++) {
            String suffix = String.valueOf(number);
            candidate = base.substring(0, Math.min(base.length(), IDENTIFIER_MAX - suffix.length())) + suffix;
        }
        return candidate;
    }

    static TypeTarget typeFor(Kind kind) {
        if (kind == null) {
            return TypeTarget.TASK;
        }
        return switch (kind) {
            case EPIC -> TypeTarget.MODULE;
            case STORY -> TypeTarget.STORY;
            case BUG -> TypeTarget.BUG;
            case TASK, SUBTASK -> TypeTarget.TASK;
        };
    }

    static IssuePriority priorityFor(String name) {
        String text = CsvField.normalize(name);
        if (contains(text, "highest", "urgent", "critical", "blocker", "urgente", "critica", "p0", "muy alta")) {
            return IssuePriority.URGENT;
        }
        if (contains(text, "lowest", "low", "minor", "trivial", "baja", "menor", "p3", "p4")) {
            return IssuePriority.LOW;
        }
        if (contains(text, "high", "major", "alta", "importante", "p1")) {
            return IssuePriority.HIGH;
        }
        if (contains(text, "medium", "normal", "media", "moderate", "p2")) {
            return IssuePriority.MEDIUM;
        }
        return IssuePriority.NONE;
    }

    private static boolean contains(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * El estado que le toca a uno del origen: el que se llame igual; si no, el de fábrica que significa lo mismo; y si
     * no, por su categoría. Uno «en curso» con nombre propio («In QA») se conserva como estado nuevo: es parte de cómo
     * trabaja ese equipo.
     */
    static StateTarget stateFor(String name, StatusCategory category, List<StateTarget> available) {
        String normalized = CsvField.normalize(name);
        for (StateTarget state : available) {
            if (CsvField.normalize(state.name()).equals(normalized)) {
                return state;
            }
        }
        StateGroup group = switch (category) {
            case BACKLOG -> StateGroup.BACKLOG;
            case TODO -> StateGroup.UNSTARTED;
            case IN_PROGRESS -> StateGroup.STARTED;
            case DONE -> StateGroup.COMPLETED;
            case CANCELLED -> StateGroup.CANCELLED;
        };
        boolean generic = switch (group) {
            case BACKLOG -> contains(normalized, "backlog", "pendiente");
            case UNSTARTED -> contains(normalized, "to do", "todo", "por hacer", "sin empezar", "open", "abiert", "new", "nuev");
            case STARTED -> contains(normalized, "in progress", "en curso", "en progreso", "doing", "en proceso", "started");
            case COMPLETED, CANCELLED -> true;
        };
        if (generic || group != StateGroup.STARTED) {
            for (StateTarget state : available) {
                if (state.group() == group) {
                    return state;
                }
            }
        }
        String clean = name.strip();
        return new StateTarget(clean.length() > STATE_NAME_MAX ? clean.substring(0, STATE_NAME_MAX).strip() : clean, group);
    }

    /** Miembro si el correo coincide o, sin correo, si exactamente un miembro se llama igual; si no, sin asignar. */
    static PersonTarget personFor(ExtUser user, Collection<User> members) {
        if (user.email() != null) {
            for (User member : members) {
                if (member.getEmail().equalsIgnoreCase(user.email())) {
                    return new PersonTarget(PersonAction.MEMBER, member.getId(), null, null);
                }
            }
        } else if (user.name() != null) {
            String name = CsvField.normalize(user.name());
            List<User> same = members.stream().filter(member -> CsvField.normalize(member.getFullName()).equals(name)).toList();
            if (same.size() == 1) {
                return new PersonTarget(PersonAction.MEMBER, same.get(0).getId(), null, null);
            }
        }
        return new PersonTarget(PersonAction.UNASSIGNED, null, user.email() == null ? null : user.email().toLowerCase(Locale.ROOT), null);
    }
}
