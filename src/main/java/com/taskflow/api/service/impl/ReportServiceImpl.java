package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.IssueFilter;
import com.taskflow.api.dto.request.ReportOptions;
import com.taskflow.api.dto.response.IssuesReportPreview;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Label;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.IssuePriority;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.report.IssuesReport;
import com.taskflow.api.report.IssuesReport.Count;
import com.taskflow.api.report.IssuesReport.FilterLine;
import com.taskflow.api.report.IssuesReport.Group;
import com.taskflow.api.report.IssuesReport.Row;
import com.taskflow.api.report.IssuesReport.Totals;
import com.taskflow.api.report.IssuesReportPdf;
import com.taskflow.api.repository.CycleRepository;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.IssueSpecifications;
import com.taskflow.api.repository.LabelRepository;
import com.taskflow.api.repository.ProjectModuleRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.service.ReportService;
import com.taskflow.api.util.IssueListSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Arma el reporte de tareas: aplica los filtros del listado y los rangos de fechas, resuelve los nombres de lo que
 * se filtró, cuenta y agrupa en memoria (un reporte trae como mucho {@link IssuesReport#MAX_ISSUES} tareas).
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    static final String UNASSIGNED = "Sin asignar";
    static final String GONE = "(ya no existe)";

    private static final Locale SPANISH = Locale.forLanguageTag("es");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH);
    private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER;

    private static final Map<IssuePriority, String> PRIORITY_LABELS = Map.of(
            IssuePriority.URGENT, "Urgente",
            IssuePriority.HIGH, "Alta",
            IssuePriority.MEDIUM, "Media",
            IssuePriority.LOW, "Baja",
            IssuePriority.NONE, "Sin prioridad");
    private static final Map<IssueType, String> TYPE_LABELS = Map.of(
            IssueType.STORY, "Historia",
            IssueType.TASK, "Tarea",
            IssueType.BUG, "Bug");

    private final IssueRepository issueRepository;
    private final StateRepository stateRepository;
    private final LabelRepository labelRepository;
    private final CycleRepository cycleRepository;
    private final ProjectModuleRepository moduleRepository;
    private final UserRepository userRepository;
    private final ResourceLookup lookup;
    private final AppProperties properties;

    @Override
    @Transactional(readOnly = true)
    public IssuesReportPreview previewIssues(String slug, UUID projectId, UUID userId, IssueFilter filter,
                                             ReportOptions options) {
        IssuesReport report = build(slug, projectId, userId, filter, options);
        return new IssuesReportPreview(report.totalMatching(), report.truncated(), IssuesReport.MAX_ISSUES,
                report.totals(), report.byState(), report.byPriority(), report.byAssignee(), report.byType());
    }

    @Override
    @Transactional(readOnly = true)
    public ReportFile issuesPdf(String slug, UUID projectId, UUID userId, IssueFilter filter, ReportOptions options) {
        if (!options.includeSummary() && !options.includeIssues()) {
            throw new BadRequestException("El reporte debe incluir el resumen, el listado o ambos");
        }
        IssuesReport report = build(slug, projectId, userId, filter, options);
        String day = LocalDate.ofInstant(report.generatedAt(), report.zone()).toString();
        // El identificador solo tiene letras y números: sirve tal cual en el nombre del archivo
        String fileName = "reporte-tareas-" + report.identifier() + "-" + day + ".pdf";
        return new ReportFile(fileName, IssuesReportPdf.render(report));
    }

    IssuesReport build(String slug, UUID projectId, UUID userId, IssueFilter filter, ReportOptions options) {
        Project project = lookup.project(slug, projectId);
        ZoneId zone = ZoneId.of(properties.timezone());
        checkRange(options.createdFrom(), options.createdTo(), "creación");
        checkRange(options.dueFrom(), options.dueTo(), "fecha límite");

        Specification<Issue> spec = IssueSpecifications.matching(project.getId(), filter)
                .and(IssueSpecifications.createdBetween(
                        options.createdFrom() == null ? null : options.createdFrom().atStartOfDay(zone).toInstant(),
                        options.createdTo() == null ? null : options.createdTo().plusDays(1).atStartOfDay(zone).toInstant()))
                .and(IssueSpecifications.dueBetween(options.dueFrom(), options.dueTo()));
        Page<Issue> page = issueRepository.findAll(spec, IssueListSort.withTieBreakers(
                PageRequest.of(0, IssuesReport.MAX_ISSUES, sortOf(options.sortOrDefault()))));
        List<Issue> issues = page.getContent();
        List<State> states = stateRepository.findAllByProjectIdOrderByPositionAsc(project.getId());
        LocalDate today = LocalDate.now(zone);

        return new IssuesReport(
                project.getName(),
                project.getIdentifier(),
                project.getWorkspace().getName(),
                lookup.user(userId).getFullName(),
                Instant.now(),
                zone,
                filterLines(project, filter, options, states),
                totals(issues, today),
                byState(issues, states),
                byPriority(issues),
                byAssignee(issues),
                byType(issues),
                groups(issues, states, options.groupByOrDefault(), today),
                page.getTotalElements(),
                page.getTotalElements() > issues.size(),
                options);
    }

    // ---------------------------------------------------------------- orden y validación

    static Sort sortOf(ReportOptions.Sort sort) {
        return switch (sort) {
            case PRIORITY -> Sort.by(Sort.Direction.ASC, IssueListSort.PRIORITY);
            case NEWEST -> Sort.by(Sort.Direction.DESC, "sequenceId");
            case OLDEST -> Sort.by(Sort.Direction.ASC, "sequenceId");
            case UPDATED -> Sort.by(Sort.Direction.DESC, "updatedAt");
            case DUE_DATE -> Sort.by(Sort.Direction.ASC, "dueDate");
            case TITLE -> Sort.by(Sort.Direction.ASC, "title");
            case MANUAL -> Sort.by(Sort.Direction.ASC, "sortOrder");
        };
    }

    /** Fechas entre 1900 y 2999 (como las demás de la API) y con el «desde» antes o igual que el «hasta». */
    private static void checkRange(LocalDate from, LocalDate to, String what) {
        for (LocalDate date : new LocalDate[]{from, to}) {
            if (date != null && (date.getYear() < 1900 || date.getYear() > 2999)) {
                throw new BadRequestException("La fecha de " + what + " debe estar entre los años 1900 y 2999");
            }
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("En " + what + ", «desde» no puede ser posterior a «hasta»");
        }
    }

    // ---------------------------------------------------------------- filtros legibles

    private List<FilterLine> filterLines(Project project, IssueFilter filter, ReportOptions options, List<State> states) {
        List<FilterLine> lines = new ArrayList<>();
        if (hasValues(filter.state())) {
            // En el orden del tablero; un estado borrado después de armar el enlace se avisa
            List<String> names = states.stream()
                    .filter(state -> filter.state().contains(state.getId()))
                    .map(State::getName)
                    .toList();
            lines.add(new FilterLine("Estado", joined(names, filter.state().size())));
        }
        if (hasValues(filter.priority())) {
            lines.add(new FilterLine("Prioridad", String.join(", ", IssuePriorityOrder.sorted(filter.priority()).stream()
                    .map(PRIORITY_LABELS::get)
                    .toList())));
        }
        if (hasValues(filter.type())) {
            lines.add(new FilterLine("Tipo", String.join(", ", filter.type().stream()
                    .distinct()
                    .sorted()
                    .map(TYPE_LABELS::get)
                    .toList())));
        }
        if (hasValues(filter.assignee())) {
            // Solo gente del workspace: un id de fuera no revela el nombre de esa persona
            List<String> names = userRepository
                    .findAllByIdInWorkspace(filter.assignee(), project.getWorkspace().getId()).stream()
                    .map(User::getFullName)
                    .sorted(BY_NAME)
                    .toList();
            lines.add(new FilterLine("Asignado", joined(names, filter.assignee().size())));
        }
        if (hasValues(filter.label())) {
            List<String> names = labelRepository.findAllByProjectIdAndIdIn(project.getId(), filter.label()).stream()
                    .map(Label::getName)
                    .sorted(BY_NAME)
                    .toList();
            lines.add(new FilterLine("Etiqueta", joined(names, filter.label().size())));
        }
        if (filter.cycle() != null) {
            lines.add(new FilterLine("Ciclo", cycleRepository.findByIdAndProjectId(filter.cycle(), project.getId())
                    .map(cycle -> cycle.getName())
                    .orElse(GONE)));
        }
        if (filter.module() != null) {
            lines.add(new FilterLine("Módulo", moduleRepository.findByIdAndProjectId(filter.module(), project.getId())
                    .map(module -> module.getName())
                    .orElse(GONE)));
        }
        if (filter.blocked() != null) {
            lines.add(new FilterLine("Bloqueo", filter.blocked() ? "solo bloqueadas" : "solo las que no están bloqueadas"));
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            lines.add(new FilterLine("Búsqueda", "«" + filter.search().trim() + "»"));
        }
        rangeText(options.createdFrom(), options.createdTo())
                .ifPresent(text -> lines.add(new FilterLine("Creadas", text)));
        rangeText(options.dueFrom(), options.dueTo())
                .ifPresent(text -> lines.add(new FilterLine("Fecha límite", text)));
        return lines;
    }

    /** «a, b» y, si alguno de los pedidos ya no existe, «a, b (ya no existe)». */
    private static String joined(List<String> names, int requested) {
        if (names.isEmpty()) {
            return GONE;
        }
        String text = String.join(", ", names);
        return names.size() < requested ? text + " " + GONE : text;
    }

    private static java.util.Optional<String> rangeText(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return java.util.Optional.empty();
        }
        if (from != null && to != null) {
            return java.util.Optional.of("del " + DAY.format(from) + " al " + DAY.format(to));
        }
        return java.util.Optional.of(from != null ? "desde el " + DAY.format(from) : "hasta el " + DAY.format(to));
    }

    // ---------------------------------------------------------------- conteos

    static Totals totals(List<Issue> issues, LocalDate today) {
        int open = 0;
        int completed = 0;
        int cancelled = 0;
        int overdue = 0;
        for (Issue issue : issues) {
            StateGroup group = issue.getState().getStateGroup();
            if (group == StateGroup.COMPLETED) {
                completed++;
            } else if (group == StateGroup.CANCELLED) {
                cancelled++;
            } else {
                open++;
                if (isOverdue(issue, today)) {
                    overdue++;
                }
            }
        }
        return new Totals(issues.size(), open, completed, cancelled, overdue);
    }

    private static boolean isOverdue(Issue issue, LocalDate today) {
        return issue.getState().getStateGroup().isOpen() && issue.getDueDate() != null && issue.getDueDate().isBefore(today);
    }

    private static List<Count> byState(List<Issue> issues, List<State> states) {
        return states.stream()
                .map(state -> new Count(state.getName(), (int) issues.stream()
                        .filter(issue -> issue.getState().getId().equals(state.getId()))
                        .count()))
                .filter(count -> count.count() > 0)
                .toList();
    }

    private static List<Count> byPriority(List<Issue> issues) {
        List<Count> counts = new ArrayList<>();
        for (IssuePriority priority : IssuePriority.values()) {
            int count = (int) issues.stream().filter(issue -> issue.getPriority() == priority).count();
            if (count > 0) {
                counts.add(new Count(PRIORITY_LABELS.get(priority), count));
            }
        }
        return counts;
    }

    private static List<Count> byType(List<Issue> issues) {
        List<Count> counts = new ArrayList<>();
        for (IssueType type : IssueType.values()) {
            int count = (int) issues.stream().filter(issue -> issue.getType() == type).count();
            if (count > 0) {
                counts.add(new Count(TYPE_LABELS.get(type), count));
            }
        }
        return counts;
    }

    /** De más a menos tareas (a igual cantidad, por nombre) y «Sin asignar» al final. */
    private static List<Count> byAssignee(List<Issue> issues) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        int unassigned = 0;
        for (Issue issue : issues) {
            if (issue.getAssignees().isEmpty()) {
                unassigned++;
            }
            for (User assignee : issue.getAssignees()) {
                byName.merge(assignee.getFullName(), 1, Integer::sum);
            }
        }
        List<Count> counts = new ArrayList<>(byName.entrySet().stream()
                .map(entry -> new Count(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(Count::count).reversed().thenComparing(Count::name, BY_NAME))
                .toList());
        if (unassigned > 0) {
            counts.add(new Count(UNASSIGNED, unassigned));
        }
        return counts;
    }

    // ---------------------------------------------------------------- grupos

    static List<Group> groups(List<Issue> issues, List<State> states, ReportOptions.GroupBy groupBy, LocalDate today) {
        if (issues.isEmpty()) {
            return List.of();
        }
        List<Group> groups = new ArrayList<>();
        switch (groupBy) {
            case NONE -> groups.add(new Group(null, rows(issues, today)));
            case STATE -> {
                for (State state : states) {
                    addGroup(groups, state.getName(), issues.stream()
                            .filter(issue -> issue.getState().getId().equals(state.getId()))
                            .toList(), today);
                }
            }
            case PRIORITY -> {
                for (IssuePriority priority : IssuePriority.values()) {
                    addGroup(groups, PRIORITY_LABELS.get(priority), issues.stream()
                            .filter(issue -> issue.getPriority() == priority)
                            .toList(), today);
                }
            }
            case TYPE -> {
                for (IssueType type : IssueType.values()) {
                    addGroup(groups, TYPE_LABELS.get(type), issues.stream()
                            .filter(issue -> issue.getType() == type)
                            .toList(), today);
                }
            }
            case ASSIGNEE -> {
                // Por nombre; una tarea con dos asignados sale en el grupo de cada uno
                List<User> people = issues.stream()
                        .flatMap(issue -> issue.getAssignees().stream())
                        .collect(java.util.stream.Collectors.toMap(User::getId, user -> user, (a, b) -> a, LinkedHashMap::new))
                        .values().stream()
                        .sorted(Comparator.comparing(User::getFullName, BY_NAME))
                        .toList();
                for (User person : people) {
                    addGroup(groups, person.getFullName(), issues.stream()
                            .filter(issue -> issue.getAssignees().stream()
                                    .anyMatch(assignee -> assignee.getId().equals(person.getId())))
                            .toList(), today);
                }
                addGroup(groups, UNASSIGNED, issues.stream()
                        .filter(issue -> issue.getAssignees().isEmpty())
                        .toList(), today);
            }
        }
        return groups;
    }

    private static void addGroup(List<Group> groups, String title, List<Issue> issues, LocalDate today) {
        if (!issues.isEmpty()) {
            groups.add(new Group(title, rows(issues, today)));
        }
    }

    private static List<Row> rows(Collection<Issue> issues, LocalDate today) {
        return issues.stream()
                .map(issue -> new Row(
                        issue.getCode(),
                        TYPE_LABELS.get(issue.getType()),
                        issue.getTitle(),
                        issue.getState().getName(),
                        PRIORITY_LABELS.get(issue.getPriority()),
                        issue.getAssignees().stream().map(User::getFullName).sorted(BY_NAME).toList(),
                        issue.getLabels().stream().map(Label::getName).sorted(BY_NAME).toList(),
                        issue.getDueDate(),
                        !issue.getState().getStateGroup().isOpen(),
                        isOverdue(issue, today)))
                .toList();
    }

    private static boolean hasValues(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /** Prioridades en su orden natural (urgente … sin prioridad), sin repetir. */
    private static final class IssuePriorityOrder {
        private IssuePriorityOrder() {
        }

        static List<IssuePriority> sorted(Collection<IssuePriority> priorities) {
            return priorities.stream().distinct().sorted().toList();
        }
    }
}
