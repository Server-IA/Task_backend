package com.taskflow.api.imports;

import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.response.ProjectResponse;
import com.taskflow.api.entity.Comment;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.ImportJob;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.IssueRelation;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.ProjectMember;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.StoredFile;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.ChecklistItemKind;
import com.taskflow.api.entity.enums.FileKind;
import com.taskflow.api.entity.enums.FileStatus;
import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueRelationType;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.ApiException;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.imports.ImportMapping.PersonAction;
import com.taskflow.api.imports.ImportMapping.PersonTarget;
import com.taskflow.api.imports.ImportMapping.StateTarget;
import com.taskflow.api.imports.ImportPlan.PlannedIssue;
import com.taskflow.api.imports.ImportPlan.PlannedModule;
import com.taskflow.api.imports.ImportPlan.PlannedProject;
import com.taskflow.api.imports.ImportReport.SkipReason;
import com.taskflow.api.imports.ImportStore.ItemKind;
import com.taskflow.api.imports.csv.CsvField;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.model.ImportBundle.ExtChecklistItem;
import com.taskflow.api.imports.model.ImportBundle.ExtComment;
import com.taskflow.api.imports.model.ImportBundle.ExtIssue;
import com.taskflow.api.imports.model.ImportBundle.ExtLink;
import com.taskflow.api.imports.model.ImportBundle.ExtSprint;
import com.taskflow.api.imports.model.ImportBundle.ExtUser;
import com.taskflow.api.imports.model.ImportBundle.LinkType;
import com.taskflow.api.repository.CommentRepository;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueChecklistItemRepository;
import com.taskflow.api.repository.IssueRelationRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectMemberRepository;
import com.taskflow.api.repository.ProjectModuleRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.StoredFileRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.storage.FileTypes;
import com.taskflow.api.storage.ObjectStorage;
import com.taskflow.api.storage.StorageProperties;
import com.taskflow.api.util.SortOrderCalculator;
import com.taskflow.api.util.VisibleText;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Escribe en TaskFlow lo que dice un {@link ImportPlan}. Cada método es un paso que {@code ImportRunner} corre en su
 * propia transacción (así una importación grande no es una sola transacción enorme y se puede detener a la mitad); lo
 * que un paso necesita del anterior (qué tarea de TaskFlow es cada una del origen) va en la {@link Session}.
 * <p>
 * No pasa por los servicios de tareas: importar no es que alguien cree cientos de tareas a mano. No manda avisos, no
 * mueve historias solas y conserva las fechas del origen. Sí respeta lo que esos servicios garantizan: los números de
 * tarea no se repiten, los asignados son miembros del proyecto, una historia no cuelga de nada y los archivos pasan
 * por las mismas reglas de tipo, tamaño y cuota.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportWriter {

    private static final int TITLE_MAX = 255;
    private static final int DESCRIPTION_MAX = 50_000;
    private static final int NAME_MAX = 80;
    private static final int LABEL_MAX = 50;
    private static final int CHECK_MAX = 500;
    private static final int CHECKS_PER_ISSUE = 100;
    private static final int KEY_MAX = 100;
    private static final int URL_MAX = 1000;
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);
    private static final List<String> LABEL_COLORS = List.of("#2F78C4", "#C2761A", "#7B5BD6", "#2E9E6A", "#C2261C", "#0E8F8F",
            "#B0508F", "#67615A");
    private static final Map<StateGroup, String> STATE_COLORS = Map.of(StateGroup.BACKLOG, "#A3A3A3",
            StateGroup.UNSTARTED, "#3A3A3A", StateGroup.STARTED, "#F59E0B", StateGroup.COMPLETED, "#16A34A",
            StateGroup.CANCELLED, "#DC2626");
    private static final List<StateGroup> GROUP_ORDER = List.of(StateGroup.BACKLOG, StateGroup.UNSTARTED, StateGroup.STARTED,
            StateGroup.COMPLETED, StateGroup.CANCELLED);

    private final ProjectService projectService;
    private final InvitationService invitationService;
    private final ActivityService activityService;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final StateRepository stateRepository;
    private final LabelRepository labelRepository;
    private final ProjectModuleRepository moduleRepository;
    private final CycleRepository cycleRepository;
    private final IssueRepository issueRepository;
    private final IssueChecklistItemRepository checklistRepository;
    private final CommentRepository commentRepository;
    private final IssueRelationRepository relationRepository;
    private final StoredFileRepository fileRepository;
    private final UserRepository userRepository;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final ImportStore store;

    @PersistenceContext
    private EntityManager entityManager;

    /** Lo que una importación lleva de un paso al siguiente. */
    public static final class Session {
        final UUID jobId;
        final UUID workspaceId;
        final String slug;
        final UUID importerId;
        final ImportSourceType source;
        final Language language;
        final ImportPlan plan;
        final Map<String, ExtUser> users = new HashMap<>();
        final Map<String, Target> projects = new LinkedHashMap<>();
        /** Tarea del origen → tarea de TaskFlow. */
        final Map<String, UUID> issueIds = new HashMap<>();
        final Map<String, ExtIssue> issues = new HashMap<>();
        final ImportReport report = new ImportReport();

        Session(ImportJob job, String slug, ImportPlan plan, ImportBundle bundle, Language language) {
            this.jobId = job.getId();
            this.workspaceId = job.getWorkspace().getId();
            this.slug = slug;
            this.importerId = job.getCreatedBy().getId();
            this.source = job.getSource();
            this.language = language;
            this.plan = plan;
            if (bundle.users() != null) {
                bundle.users().forEach(user -> users.put(user.id(), user));
            }
        }

        public ImportReport report() {
            return report;
        }

        public ImportPlan plan() {
            return plan;
        }

        /** Cómo se llama el origen en un texto: «Jira», «un archivo CSV». */
        String sourceName() {
            return switch (source) {
                case JIRA -> "Jira";
                case TRELLO -> "Trello";
                case LINEAR -> "Linear";
                case ASANA -> "Asana";
                case CSV -> language.t("un archivo CSV");
            };
        }
    }

    /** El proyecto de TaskFlow en el que cae un proyecto del origen, con lo que ya existe o se creó en él. */
    static final class Target {
        UUID id;
        String identifier;
        String name;
        boolean created;
        boolean keepNumbers;
        int issues;
        /** Nombre del estado (normalizado) → estado. */
        final Map<String, UUID> states = new HashMap<>();
        final Map<UUID, StateGroup> groups = new HashMap<>();
        /** Estado del origen → estado de TaskFlow. */
        final Map<String, UUID> stateOf = new HashMap<>();
        final Map<String, UUID> labels = new HashMap<>();
        final Map<String, UUID> modules = new HashMap<>();
        final Map<String, UUID> cycles = new HashMap<>();
        final Map<UUID, Double> lastSortOrder = new HashMap<>();
        final Set<Integer> usedNumbers = new HashSet<>();
        final Set<UUID> members = new HashSet<>();
    }

    public Session open(ImportJob job, String slug, ImportPlan plan, ImportBundle bundle, Language language) {
        return new Session(job, slug, plan, bundle, language);
    }

    // ----- Paso 1: el proyecto y lo que cuelga de él

    /** Crea (o busca) el proyecto y sus estados, etiquetas, miembros, módulos y ciclos. */
    public void prepareProject(Session session, PlannedProject planned) {
        Target target = new Target();
        Project project;
        if (planned.choice().createsProject()) {
            String name = cut(firstText(planned.choice().name(), planned.source().name(), session.language.t("Proyecto importado")), NAME_MAX);
            String identifier = freeIdentifier(session.workspaceId, firstText(planned.choice().identifier(), planned.source().key(), name));
            ProjectResponse created = projectService.create(session.slug, session.importerId,
                    new CreateProjectRequest(name, identifier, cut(blankToNull(planned.source().description()), 10_000), null));
            entityManager.flush();
            project = projectRepository.findById(created.id()).orElseThrow();
            store.addItem(session.jobId, ItemKind.PROJECT, project.getId());
            target.created = true;
        } else {
            project = projectRepository.findInWorkspace(planned.choice().targetProjectId(), session.slug)
                    .orElseThrow(() -> new IllegalStateException("El proyecto de destino ya no existe"));
            // Mientras se crean estados y etiquetas, nadie más los crea con el mismo nombre
            projectRepository.lockById(project.getId());
        }
        target.id = project.getId();
        target.identifier = project.getIdentifier();
        target.name = project.getName();
        session.projects.put(planned.source().id(), target);

        prepareStates(session, planned, target, project);
        prepareLabels(session, planned, target, project);
        prepareMembers(session, planned, target, project);
        for (Map.Entry<String, PlannedModule> entry : planned.modules().entrySet()) {
            PlannedModule source = entry.getValue();
            ProjectModule module = new ProjectModule();
            module.setProject(project);
            module.setName(cut(firstText(source.name(), session.language.t("(sin título)")), NAME_MAX));
            module.setDescription(blankToNull(source.description()));
            LocalDate start = inRange(source.startDate());
            LocalDate end = inRange(source.targetDate());
            module.setStartDate(start != null && end != null && end.isBefore(start) ? null : start);
            module.setTargetDate(end);
            moduleRepository.save(module);
            target.modules.put(entry.getKey(), module.getId());
            store.addItem(session.jobId, ItemKind.MODULE, module.getId());
            session.report.modules++;
        }
        User importer = userRepository.getReferenceById(session.importerId);
        for (ExtSprint sprint : planned.sprints()) {
            Cycle cycle = new Cycle();
            cycle.setProject(project);
            cycle.setName(cut(firstText(sprint.name(), "Sprint"), NAME_MAX));
            LocalDate start = inRange(sprint.startDate());
            LocalDate end = inRange(sprint.endDate());
            cycle.setStartDate(start != null && end != null && end.isBefore(start) ? null : start);
            cycle.setEndDate(end);
            if (sprint.completedAt() != null) {
                cycle.setClosedAt(sprint.completedAt());
                cycle.setClosedBy(importer);
            }
            cycleRepository.save(cycle);
            target.cycles.put(sprint.id(), cycle.getId());
            store.addItem(session.jobId, ItemKind.CYCLE, cycle.getId());
            session.report.cycles++;
        }

        target.keepNumbers = target.created && session.plan.options().keepNumbers();
        if (target.keepNumbers) {
            int highest = planned.issues().stream().map(issue -> issue.source().number()).filter(number -> number != null && number > 0)
                    .mapToInt(Integer::intValue).max().orElse(0);
            // Así una tarea creada a mano mientras tanto, o una importada sin número, no toma el de otra
            store.raiseIssueCounter(target.id, highest);
        }
        for (PlannedIssue issue : planned.issues()) {
            session.issues.put(issue.source().id(), issue.source());
        }
    }

    private void prepareStates(Session session, PlannedProject planned, Target target, Project project) {
        List<State> states = new ArrayList<>(stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId()));
        List<StateTarget> available = new ArrayList<>();
        for (State state : states) {
            target.states.put(CsvField.normalize(state.getName()), state.getId());
            target.groups.put(state.getId(), state.getStateGroup());
            available.add(new StateTarget(state.getName(), state.getStateGroup()));
        }
        Map<String, StateTarget> chosen = session.plan.mapping().states() == null ? Map.of() : session.plan.mapping().states();
        for (PlannedIssue issue : planned.issues()) {
            String status = issue.source().status();
            if (target.stateOf.containsKey(status)) {
                continue;
            }
            StateTarget wanted = chosen.get(status);
            if (wanted == null || wanted.name() == null || wanted.name().isBlank() || wanted.group() == null) {
                wanted = ImportDefaults.stateFor(status, issue.source().statusCategory(), available);
            }
            String key = CsvField.normalize(wanted.name());
            UUID stateId = target.states.get(key);
            if (stateId == null) {
                State state = insertState(states, project, cut(wanted.name().strip(), LABEL_MAX), wanted.group());
                stateId = state.getId();
                target.states.put(key, stateId);
                target.groups.put(stateId, state.getStateGroup());
                available.add(new StateTarget(state.getName(), state.getStateGroup()));
                store.addItem(session.jobId, ItemKind.STATE, stateId);
                session.report.states++;
            }
            target.stateOf.put(status, stateId);
        }
    }

    /** Un estado nuevo va después del último de su grupo (o de los grupos anteriores): «In QA» queda tras «In Progress». */
    private State insertState(List<State> states, Project project, String name, StateGroup group) {
        int index = 0;
        for (int i = 0; i < states.size(); i++) {
            if (GROUP_ORDER.indexOf(states.get(i).getStateGroup()) <= GROUP_ORDER.indexOf(group)) {
                index = i + 1;
            }
        }
        State state = new State(project, name, STATE_COLORS.get(group), group, index);
        states.add(index, state);
        for (int i = index + 1; i < states.size(); i++) {
            states.get(i).setPosition(i);
        }
        stateRepository.save(state);
        return state;
    }

    private void prepareLabels(Session session, PlannedProject planned, Target target, Project project) {
        List<Label> existing = labelRepository.findAllByProjectIdOrderByNameAsc(project.getId());
        existing.forEach(label -> target.labels.put(CsvField.normalize(label.getName()), label.getId()));
        int color = existing.size();
        for (PlannedIssue issue : planned.issues()) {
            if (issue.source().labels() == null) {
                continue;
            }
            for (String name : issue.source().labels()) {
                String clean = cut(name.strip(), LABEL_MAX);
                String key = CsvField.normalize(clean);
                if (key.isEmpty() || target.labels.containsKey(key)) {
                    continue;
                }
                Label label = new Label();
                label.setProject(project);
                label.setName(clean);
                label.setColor(LABEL_COLORS.get(color++ % LABEL_COLORS.size()));
                labelRepository.save(label);
                target.labels.put(key, label.getId());
                store.addItem(session.jobId, ItemKind.LABEL, label.getId());
                session.report.labels++;
            }
        }
    }

    /** Quien tenga tareas asignadas (o las haya creado) y ya sea del workspace entra al proyecto: solo un miembro del proyecto puede tener tareas. */
    private void prepareMembers(Session session, PlannedProject planned, Target target, Project project) {
        Set<UUID> wanted = new LinkedHashSet<>();
        for (PlannedIssue issue : planned.issues()) {
            if (issue.source().assigneeIds() != null) {
                issue.source().assigneeIds().forEach(id -> addMember(wanted, session.plan.person(id)));
            }
        }
        for (UUID userId : wanted) {
            if (!projectMemberRepository.existsByProjectIdAndUserId(project.getId(), userId)) {
                projectMemberRepository.save(new ProjectMember(project, userRepository.getReferenceById(userId)));
            }
            target.members.add(userId);
        }
    }

    private static void addMember(Set<UUID> wanted, PersonTarget target) {
        if (target.action() == PersonAction.MEMBER && target.userId() != null) {
            wanted.add(target.userId());
        }
    }

    private String freeIdentifier(UUID workspaceId, String wanted) {
        return ImportDefaults.identifier(wanted, candidate -> projectRepository.existsByWorkspaceIdAndIdentifier(workspaceId, candidate));
    }

    // ----- Paso 2: las tareas

    /** Escribe un grupo de tareas del proyecto (ya en orden: cada padre antes que sus hijas). */
    public void writeIssues(Session session, PlannedProject planned, List<PlannedIssue> chunk) {
        Target target = session.projects.get(planned.source().id());
        Project project = projectRepository.getReferenceById(target.id);
        User importer = userRepository.getReferenceById(session.importerId);
        Instant now = Instant.now();
        List<Issue> saved = new ArrayList<>();
        for (PlannedIssue plannedIssue : chunk) {
            ExtIssue source = plannedIssue.source();
            Issue issue = new Issue();
            issue.setProject(project);
            issue.setType(plannedIssue.type());
            issue.setTitle(cut(firstText(VisibleText.strip(clean(source.title())), session.language.t("(sin título)")), TITLE_MAX));
            UUID stateId = target.stateOf.get(source.status());
            StateGroup group = target.groups.get(stateId);
            issue.setState(stateRepository.getReferenceById(stateId));
            issue.setPriority(priority(session, source));
            UUID parentId = plannedIssue.parentId() == null ? null : session.issueIds.get(plannedIssue.parentId());
            if (parentId != null) {
                issue.setParent(issueRepository.getReferenceById(parentId));
            }
            LocalDate start = inRange(source.startDate());
            LocalDate due = inRange(source.dueDate());
            issue.setStartDate(start != null && due != null && due.isBefore(start) ? null : start);
            issue.setDueDate(due);

            Instant createdAt = source.createdAt() != null ? source.createdAt() : now;
            if (group == StateGroup.COMPLETED) {
                issue.setCompletedAt(source.completedAt() != null ? source.completedAt() : createdAt);
            } else if (group == StateGroup.STARTED) {
                issue.setStartedAt(now);
            }
            if (plannedIssue.type() == IssueType.STORY && (group == StateGroup.COMPLETED || group == StateGroup.CANCELLED)) {
                issue.setClosedAt(source.completedAt() != null ? source.completedAt() : createdAt);
                issue.setClosedBy(importer);
            }

            List<String> lost = new ArrayList<>();
            List<String> invited = new ArrayList<>();
            if (source.assigneeIds() != null) {
                for (String assignee : source.assigneeIds()) {
                    PersonTarget person = session.plan.person(assignee);
                    if (person.action() == PersonAction.MEMBER && target.members.contains(person.userId())) {
                        issue.getAssignees().add(userRepository.getReferenceById(person.userId()));
                    } else if (person.action() == PersonAction.INVITE && person.email() != null && !person.email().isBlank()) {
                        invited.add(person.email().strip().toLowerCase(Locale.ROOT));
                    } else {
                        lost.add(personName(session, assignee));
                    }
                }
            }
            if (source.labels() != null) {
                for (String label : source.labels()) {
                    UUID labelId = target.labels.get(CsvField.normalize(cut(label.strip(), LABEL_MAX)));
                    if (labelId != null) {
                        issue.getLabels().add(labelRepository.getReferenceById(labelId));
                    }
                }
            }
            PersonTarget creator = session.plan.person(source.creatorId());
            boolean knownCreator = creator.action() == PersonAction.MEMBER && creator.userId() != null;
            issue.setCreatedBy(knownCreator ? userRepository.getReferenceById(creator.userId()) : importer);
            String createdBy = knownCreator || source.creatorId() == null ? null : personName(session, source.creatorId());

            String description = description(session, source, lost, createdBy);
            issue.setDescription(clean(description));
            if (description != null) {
                issue.setDescriptionUpdatedAt(now);
                issue.setDescriptionUpdatedBy(importer);
            }
            issue.setSequenceId(nextNumber(target, source.number()));
            double sortOrder = SortOrderCalculator.endOfColumn(target.lastSortOrder.computeIfAbsent(stateId,
                    id -> issueRepository.findMaxSortOrder(id)));
            target.lastSortOrder.put(stateId, sortOrder);
            issue.setSortOrder(sortOrder);
            issue.setImportSource(session.source.name());
            issue.setImportKey(cut(blankToNull(source.key()), KEY_MAX));
            issue.setImportUrl(webUrl(source.url()));
            issueRepository.save(issue);

            if (source.checklist() != null) {
                int position = 0;
                for (ExtChecklistItem item : source.checklist()) {
                    if (item.text() == null || item.text().isBlank() || position >= CHECKS_PER_ISSUE) {
                        continue;
                    }
                    IssueChecklistItem check = new IssueChecklistItem(issue, ChecklistItemKind.CHECK, cut(item.text().strip(), CHECK_MAX), position++);
                    if (item.done()) {
                        check.setDone(true);
                        check.setCheckedAt(createdAt);
                    }
                    checklistRepository.save(check);
                }
                if (position > 0) {
                    issue.setChecklistUpdatedAt(createdAt);
                }
            }
            activityService.record(issue, knownCreator ? issue.getCreatedBy() : null, ActivityField.CREATED, null, issue.getTitle());
            session.issueIds.put(source.id(), issue.getId());
            store.addItem(session.jobId, ItemKind.ISSUE, issue.getId());
            for (String email : invited) {
                store.addPendingAssignment(session.workspaceId, email, issue.getId());
            }
            saved.add(issue);
            target.issues++;
            switch (plannedIssue.type()) {
                case STORY -> session.report.stories++;
                case BUG -> session.report.bugs++;
                case TASK -> session.report.tasks++;
            }
        }
        // Con las filas ya en la base: ciclo, módulos y las fechas del origen (la entidad siempre pone «ahora»)
        entityManager.flush();
        for (int index = 0; index < chunk.size(); index++) {
            PlannedIssue plannedIssue = chunk.get(index);
            ExtIssue source = plannedIssue.source();
            UUID issueId = saved.get(index).getId();
            if (plannedIssue.sprintId() != null && target.cycles.containsKey(plannedIssue.sprintId())) {
                store.addToCycle(target.cycles.get(plannedIssue.sprintId()), issueId);
            }
            for (String moduleKey : plannedIssue.moduleKeys()) {
                if (target.modules.containsKey(moduleKey)) {
                    store.addToModule(target.modules.get(moduleKey), issueId);
                }
            }
            if (source.createdAt() != null) {
                Instant updated = source.completedAt() != null && source.completedAt().isAfter(source.createdAt())
                        ? source.completedAt() : source.createdAt();
                store.backdateIssue(issueId, source.createdAt(), updated);
            }
        }
        entityManager.clear();
    }

    private IssuePriority priority(Session session, ExtIssue source) {
        if (source.priority() == null) {
            return IssuePriority.NONE;
        }
        Map<String, IssuePriority> chosen = session.plan.mapping().priorities();
        IssuePriority priority = chosen == null ? null : chosen.get(source.priority());
        return priority != null ? priority : ImportDefaults.priorityFor(source.priority());
    }

    /** El número de la tarea: el del origen si se conservan y está libre; si no, el siguiente del proyecto. */
    private int nextNumber(Target target, Integer wanted) {
        if (target.keepNumbers && wanted != null && wanted > 0 && target.usedNumbers.add(wanted)) {
            return wanted;
        }
        projectRepository.incrementIssueCounter(target.id);
        int number = projectRepository.findIssueCounter(target.id);
        target.usedNumbers.add(number);
        return number;
    }

    /**
     * La descripción de la tarea: la del origen y, debajo, lo que TaskFlow no lleva en un campo (story points, horas,
     * a quién estaba asignada si esa persona no entra, otros campos con valor), para que no se pierda.
     */
    private String description(Session session, ExtIssue source, List<String> lost, String createdBy) {
        List<String> notes = new ArrayList<>();
        Language language = session.language;
        if (source.points() != null) {
            notes.add(note("Story points", number(source.points())));
        }
        if (source.secondsSpent() != null && source.secondsSpent() > 0) {
            notes.add(note(language.t("Tiempo registrado"), duration(source.secondsSpent())));
        }
        if (!lost.isEmpty()) {
            notes.add(note(language.t("Asignada a"), String.join(", ", lost)));
        }
        if (createdBy != null) {
            notes.add(note(language.t("Creada por"), createdBy));
        }
        if (source.extra() != null) {
            source.extra().forEach((name, value) -> {
                if (value != null && !value.isBlank()) {
                    notes.add(note(name, value.strip().replaceAll("\\s*\\n\\s*", " ")));
                }
            });
        }
        String body = source.description() == null ? "" : source.description().strip();
        if (!notes.isEmpty()) {
            String heading = "*" + language.t("Datos que venían de") + " " + session.sourceName() + ":*";
            body = (body.isEmpty() ? "" : body + "\n\n---\n\n") + heading + "\n\n" + String.join("\n", notes);
        }
        return blankToNull(cut(body, DESCRIPTION_MAX));
    }

    private static String note(String label, String value) {
        return "- **" + label + ":** " + value;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 && minutes > 0 ? hours + " h " + minutes + " min" : hours > 0 ? hours + " h" : minutes + " min";
    }

    private static String personName(Session session, String externalId) {
        ExtUser user = session.users.get(externalId);
        return user == null || user.name() == null || user.name().isBlank() ? externalId : user.name();
    }

    // ----- Paso 3: los comentarios

    /** Los comentarios de un grupo de tareas ya escritas, con su fecha y, si quien lo escribió no entra, su nombre en el texto. */
    public void writeComments(Session session, List<PlannedIssue> chunk) {
        User importer = userRepository.getReferenceById(session.importerId);
        List<Comment> saved = new ArrayList<>();
        List<Instant> dates = new ArrayList<>();
        for (PlannedIssue plannedIssue : chunk) {
            UUID issueId = session.issueIds.get(plannedIssue.source().id());
            if (issueId == null || plannedIssue.source().comments() == null) {
                continue;
            }
            Issue issue = issueRepository.getReferenceById(issueId);
            for (ExtComment source : plannedIssue.source().comments()) {
                String body = source.body() == null ? "" : source.body().strip();
                if (body.isEmpty()) {
                    continue;
                }
                PersonTarget author = session.plan.person(source.authorId());
                boolean known = author.action() == PersonAction.MEMBER && author.userId() != null;
                if (!known) {
                    String name = source.authorId() != null ? personName(session, source.authorId()) : source.authorName();
                    String when = source.createdAt() == null ? "" : ", " + source.createdAt().atOffset(ZoneOffset.UTC).toLocalDate();
                    body = (name == null || name.isBlank() ? "" : "**" + name + "** ") + "(" + session.sourceName() + when + "):\n\n" + body;
                }
                Comment comment = new Comment();
                comment.setIssue(issue);
                comment.setAuthor(known ? userRepository.getReferenceById(author.userId()) : importer);
                comment.setBody(cut(clean(body), DESCRIPTION_MAX));
                commentRepository.save(comment);
                saved.add(comment);
                dates.add(source.createdAt());
                session.report.comments++;
            }
        }
        entityManager.flush();
        for (int index = 0; index < saved.size(); index++) {
            if (dates.get(index) != null) {
                store.backdateComment(saved.get(index).getId(), dates.get(index));
            }
        }
        entityManager.clear();
    }

    // ----- Paso 4: los adjuntos

    /**
     * Si el adjunto se puede traer, antes de descargarlo: por tipo, por tamaño y por el espacio que queda. Lo que no,
     * queda en el informe.
     */
    public boolean accepts(Session session, PlannedIssue issue, ExtAttachment attachment) {
        SkipReason reason = switch (ImportPlan.check(attachment)) {
            case TOO_BIG -> SkipReason.ATTACHMENT_TOO_BIG;
            case NOT_ALLOWED -> SkipReason.ATTACHMENT_TYPE;
            case OK -> fileRepository.sumReservedBytes(session.workspaceId) + attachment.size() > storageProperties.workspaceQuotaBytes()
                    ? SkipReason.ATTACHMENT_QUOTA : null;
        };
        if (reason != null) {
            skip(session, reason, issue.source(), attachment.name());
        }
        return reason == null;
    }

    /** Guarda un adjunto ya descargado: lo sube al almacenamiento y lo deja en la tarea. */
    public void writeAttachment(Session session, PlannedIssue plannedIssue, ExtAttachment attachment, byte[] content) {
        ExtIssue source = plannedIssue.source();
        UUID issueId = session.issueIds.get(source.id());
        FileTypes.FileType type = FileTypes.byExtension(attachment.name()).orElse(null);
        byte[] head = Arrays.copyOf(content, Math.min(content.length, FileTypes.SIGNATURE_BYTES));
        if (issueId == null || type == null || content.length == 0 || !FileTypes.contentMatches(type.contentType(), head)) {
            skip(session, SkipReason.ATTACHMENT_TYPE, source, attachment.name());
            return;
        }
        if (content.length > type.limit()) {
            skip(session, SkipReason.ATTACHMENT_TOO_BIG, source, attachment.name());
            return;
        }
        // Con el workspace bloqueado, como cualquier subida: dos a la vez no se pasan juntas de la cuota
        workspaceRepository.lockById(session.workspaceId);
        if (fileRepository.sumReservedBytes(session.workspaceId) + content.length > storageProperties.workspaceQuotaBytes()) {
            skip(session, SkipReason.ATTACHMENT_QUOTA, source, attachment.name());
            return;
        }
        Target target = session.projects.get(projectOf(session, source));
        String key = "projects/" + target.id + "/" + UUID.randomUUID() + "." + type.extension();
        storage.put(key, type.contentType(), content);
        Workspace workspace = workspaceRepository.getReferenceById(session.workspaceId);
        StoredFile file = new StoredFile();
        file.setWorkspace(workspace);
        file.setProject(projectRepository.getReferenceById(target.id));
        file.setIssue(issueRepository.getReferenceById(issueId));
        file.setUploadedBy(userRepository.getReferenceById(session.importerId));
        file.setKind(FileKind.ATTACHMENT);
        file.setStatus(FileStatus.READY);
        file.setObjectKey(key);
        file.setFileName(FileTypes.cleanName(attachment.name()));
        file.setContentType(type.contentType());
        file.setSizeBytes(content.length);
        file.setConfirmedAt(Instant.now());
        fileRepository.save(file);
        session.report.attachments++;
        session.report.attachmentBytes += content.length;
    }

    public void attachmentFailed(Session session, PlannedIssue issue, ExtAttachment attachment) {
        skip(session, SkipReason.ATTACHMENT_FAILED, issue.source(), attachment.name());
    }

    private static void skip(Session session, SkipReason reason, ExtIssue issue, String detail) {
        session.report.skip(reason, issue.key(), issue.title(), detail, issue.url());
    }

    private static String projectOf(Session session, ExtIssue issue) {
        for (PlannedProject project : session.plan.projects()) {
            if (project.source().issues().contains(issue)) {
                return project.source().id();
            }
        }
        throw new IllegalStateException("Tarea sin proyecto");
    }

    // ----- Paso 5: relaciones entre tareas, ciclos cerrados e invitaciones

    /** Los bloqueos y relaciones entre tareas que entraron (una que apunta a una tarea que no entró queda en el informe). */
    public void writeRelations(Session session) {
        Set<String> written = new HashSet<>();
        for (PlannedProject project : session.plan.projects()) {
            for (PlannedIssue plannedIssue : project.issues()) {
                ExtIssue source = plannedIssue.source();
                UUID issueId = session.issueIds.get(source.id());
                if (issueId == null || source.links() == null) {
                    continue;
                }
                for (ExtLink link : source.links()) {
                    UUID otherId = session.issueIds.get(link.targetId());
                    if (otherId == null) {
                        skip(session, SkipReason.LINK_OUTSIDE, source, link.targetId());
                        continue;
                    }
                    if (otherId.equals(issueId)) {
                        continue;
                    }
                    boolean blocks = link.type() == LinkType.BLOCKS || link.type() == LinkType.BLOCKED_BY;
                    UUID from = link.type() == LinkType.BLOCKED_BY ? otherId : issueId;
                    UUID to = link.type() == LinkType.BLOCKED_BY ? issueId : otherId;
                    // Un par solo lleva una relación de cada tipo, sin importar el sentido
                    String pair = (from.compareTo(to) < 0 ? from + ":" + to : to + ":" + from) + (blocks ? ":B" : ":R");
                    if (!written.add(pair)) {
                        continue;
                    }
                    relationRepository.save(new IssueRelation(issueRepository.getReferenceById(from), issueRepository.getReferenceById(to),
                            blocks ? IssueRelationType.BLOCKS : IssueRelationType.RELATES));
                    session.report.relations++;
                }
            }
        }
    }

    /** Lo que guarda un ciclo al cerrarse (cuánto se completó de cuánto), para los que llegan ya cerrados; y el resumen del proyecto. */
    public void finishProject(Session session, PlannedProject planned) {
        Target target = session.projects.get(planned.source().id());
        Map<String, int[]> counts = new HashMap<>();
        for (PlannedIssue issue : planned.issues()) {
            if (issue.sprintId() == null || issue.type() == IssueType.STORY || !session.issueIds.containsKey(issue.source().id())) {
                continue;
            }
            int[] count = counts.computeIfAbsent(issue.sprintId(), id -> new int[2]);
            count[1]++;
            if (target.groups.get(target.stateOf.get(issue.source().status())) == StateGroup.COMPLETED) {
                count[0]++;
            }
        }
        for (ExtSprint sprint : planned.sprints()) {
            if (sprint.completedAt() == null) {
                continue;
            }
            int[] count = counts.getOrDefault(sprint.id(), new int[2]);
            Cycle cycle = cycleRepository.findById(target.cycles.get(sprint.id())).orElseThrow();
            cycle.setClosedCompletedCount(count[0]);
            cycle.setClosedTotalCount(count[1]);
        }
        session.report.projects.add(new ImportReport.CreatedProject(target.id, target.identifier, target.name, target.issues, target.created));
    }

    /** A quiénes hay que invitar: las personas marcadas así que tienen alguna tarea (correo → rol). */
    public Map<String, UUID> invitations(Session session) {
        Map<String, UUID> emails = new LinkedHashMap<>();
        for (PlannedProject project : session.plan.projects()) {
            for (PlannedIssue issue : project.issues()) {
                if (issue.source().assigneeIds() == null) {
                    continue;
                }
                for (String assignee : issue.source().assigneeIds()) {
                    PersonTarget person = session.plan.person(assignee);
                    if (person.action() == PersonAction.INVITE && person.email() != null && !person.email().isBlank()) {
                        emails.putIfAbsent(person.email().strip().toLowerCase(Locale.ROOT), person.roleId());
                    }
                }
            }
        }
        return emails;
    }

    /** Envía una invitación; si no se puede (ya es miembro, límite de invitaciones), queda en el informe y la importación sigue. */
    public void invite(Session session, String email, UUID roleId) {
        try {
            invitationService.invite(session.slug, session.importerId, new InviteMemberRequest(email, roleId));
            session.report.invitations++;
        } catch (ApiException ex) {
            session.report.skip(SkipReason.INVITATION_FAILED, null, email, session.language.t(ex.getMessage()), null);
        }
    }

    // ----- Utilidades

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max).strip();
    }

    /** El texto sin caracteres nulos (PostgreSQL no los admite en un texto). */
    static String clean(String text) {
        return text == null || text.indexOf('\u0000') < 0 ? text : text.replace("\u0000", "");
    }

    /** Una dirección web ({@code http} o {@code https}) que cabe en la columna; cualquier otra cosa, nada. */
    static String webUrl(String url) {
        if (url == null || url.isBlank() || url.length() > URL_MAX) {
            return null;
        }
        String trimmed = url.strip();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? trimmed : null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    /** Una fecha fuera de lo que TaskFlow acepta (1900–2999) se descarta. */
    private static LocalDate inRange(LocalDate date) {
        return date == null || date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE) ? null : date;
    }
}
