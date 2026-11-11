package com.taskflow.api.github;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.State;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.enums.ActivityField;
import com.taskflow.api.entity.enums.IssueType;
import com.taskflow.api.entity.enums.StateGroup;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.github.GithubStore.Branch;
import com.taskflow.api.github.GithubStore.Installation;
import com.taskflow.api.github.GithubStore.IssueRef;
import com.taskflow.api.github.GithubStore.LinkedProject;
import com.taskflow.api.github.GithubStore.PullRequest;
import com.taskflow.api.github.GithubStore.Repository;
import com.taskflow.api.github.GithubStore.Rule;
import com.taskflow.api.github.GithubStore.Run;
import com.taskflow.api.github.GithubStore.Settings;
import com.taskflow.api.github.GithubText.Code;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.service.impl.IssueCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.taskflow.api.github.GithubClient.text;

/**
 * La integración con GitHub: conectar el workspace, decir qué repositorios son de cada proyecto, atender los avisos de
 * GitHub (ramas, pull requests y ejecuciones de Actions, enlazados a la tarea que nombran), mover la tarea cuando su
 * pull request avanza y ejecutar workflows, a mano o por una regla.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubService {

    public static final String FIRES_ON_MERGE = "PR_MERGED";
    public static final String FIRES_ON_COMPLETED = "ISSUE_COMPLETED";

    private static final String NOT_CONFIGURED = "La integración con GitHub no está configurada en este servidor";
    private static final String NOT_CONNECTED = "Este workspace no está conectado con GitHub";
    private static final String REPOSITORY_NOT_FOUND = "Ese repositorio no es de este proyecto";
    private static final String UNAVAILABLE = "GitHub no respondió. Inténtalo de nuevo en un momento.";
    private static final String NOT_ALLOWED = "La app de TaskFlow no tiene permiso para eso en GitHub. Revisa los permisos de la app.";
    /** Cuánto vale el enlace para instalar la app (segundos). */
    private static final long STATE_SECONDS = 3600;
    private static final int LIST_LIMIT = 100;
    private static final Set<StateGroup> NOT_STARTED = EnumSet.of(StateGroup.BACKLOG, StateGroup.UNSTARTED);
    private static final Set<StateGroup> OPEN = EnumSet.of(StateGroup.BACKLOG, StateGroup.UNSTARTED, StateGroup.STARTED);
    private static final Set<StateGroup> IN_PROGRESS = EnumSet.of(StateGroup.STARTED);
    private static final Set<String> FAILED = Set.of("failure", "timed_out", "cancelled", "action_required", "startup_failure");

    private final GithubProperties properties;
    private final AppProperties appProperties;
    private final GithubClient client;
    private final GithubStore store;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final StateRepository stateRepository;
    private final IssueRepository issueRepository;
    private final IssueService issueService;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    // ----- Lo que se responde

    /**
     * @param openPullRequests cuántos pull requests abiertos tiene (solo se cuenta dentro de un proyecto)
     */
    public record RepositoryView(UUID id, String fullName, String defaultBranch, boolean isPrivate, List<LinkedProject> projects,
                                 int openPullRequests) {
    }

    /**
     * @param configured si el servidor tiene los datos de la GitHub App (sin ellos no se puede conectar)
     * @param manageUrl  dónde se cambia en GitHub qué repositorios ve la app
     */
    public record WorkspaceStatus(boolean configured, boolean connected, String accountLogin, String accountType, String installedByName,
                                  Instant installedAt, Instant lastEventAt, String manageUrl, List<RepositoryView> repositories) {
    }

    /**
     * @param repositories los repositorios de este proyecto
     * @param available    todos los que la instalación deja ver (para enlazar otro)
     */
    public record ProjectStatus(boolean configured, boolean connected, List<RepositoryView> repositories, List<RepositoryView> available,
                                Settings settings, List<Rule> rules) {
    }

    /**
     * @param suggestedBranch el nombre que TaskFlow le pondría a la rama de la tarea
     */
    public record IssueDev(boolean available, List<RepositoryView> repositories, List<Branch> branches, List<PullRequest> pullRequests,
                           List<Run> runs, String suggestedBranch) {
    }

    public record Workflow(long id, String name, String path, List<String> inputs) {
    }

    public record Check(String name, String status, String conclusion, Instant startedAt, Instant completedAt, String url) {
    }

    // ----- Workspace

    @Transactional(readOnly = true)
    public WorkspaceStatus workspace(String slug) {
        Workspace workspace = requireWorkspace(slug);
        Installation installation = store.installationOf(workspace.getId()).orElse(null);
        if (installation == null) {
            return new WorkspaceStatus(properties.configured(), false, null, null, null, null, null, null, List.of());
        }
        String manage = "Organization".equalsIgnoreCase(installation.accountType())
                ? properties.webUrl() + "/organizations/" + installation.accountLogin() + "/settings/installations/" + installation.installationId()
                : properties.webUrl() + "/settings/installations/" + installation.installationId();
        return new WorkspaceStatus(properties.configured(), true, installation.accountLogin(), installation.accountType(),
                installation.installedByName(), installation.createdAt(), installation.lastEventAt(), manage, repositoryViews(workspace.getId()));
    }

    private List<RepositoryView> repositoryViews(UUID workspaceId) {
        List<LinkedProject> links = store.linkedProjects(workspaceId);
        return store.repositoriesOf(workspaceId).stream().map(repository -> view(repository, links)).toList();
    }

    private static RepositoryView view(Repository repository, List<LinkedProject> links) {
        return view(repository, links, Map.of());
    }

    private static RepositoryView view(Repository repository, List<LinkedProject> links, Map<UUID, Integer> open) {
        return new RepositoryView(repository.id(), repository.fullName(), repository.defaultBranch(), repository.isPrivate(),
                links.stream().filter(link -> link.repositoryId().equals(repository.id())).toList(), open.getOrDefault(repository.id(), 0));
    }

    /** La dirección de GitHub donde el PM instala la app; lleva firmado para qué workspace es y quién la pidió. */
    @Transactional(readOnly = true)
    public String installUrl(String slug, UUID userId) {
        requireConfigured();
        Workspace workspace = requireWorkspace(slug);
        String payload = workspace.getId() + "|" + userId + "|" + Instant.now().plusSeconds(STATE_SECONDS).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return properties.webUrl() + "/apps/" + properties.appSlug() + "/installations/new?state=" + encoded + "."
                + GithubKeys.sign(properties.webhookSecret(), encoded);
    }

    /**
     * GitHub vuelve aquí cuando el PM termina de instalar la app. Guarda la instalación para el workspace que la pidió
     * y devuelve a dónde va el navegador.
     * <p>
     * El número de instalación llega en la dirección, así que no se confía en él: la instalación tiene que ser
     * reciente (acaba de hacerse) y no estar ya en otro workspace, o ser la que este workspace ya tenía.
     */
    @Transactional
    public String completeSetup(long installationId, String state) {
        String home = appProperties.frontendUrl();
        if (!properties.configured() || state == null || !state.contains(".")) {
            return home + "/?github=invalid";
        }
        String encoded = state.substring(0, state.indexOf('.'));
        if (!GithubKeys.signed(properties.webhookSecret(), encoded, state.substring(state.indexOf('.') + 1))) {
            return home + "/?github=invalid";
        }
        // Lo firmó TaskFlow, así que tiene la forma que TaskFlow le dio
        String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|");
        if (Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
            return home + "/?github=expired";
        }
        Workspace workspace = workspaceRepository.findById(UUID.fromString(parts[0])).orElse(null);
        if (workspace == null) {
            return home + "/?github=invalid";
        }
        String back = home + "/" + workspace.getSlug() + "/settings?tab=integrations&github=";
        Installation bound = store.installation(installationId).orElse(null);
        if (bound != null && !bound.workspaceId().equals(workspace.getId())) {
            return back + "taken";
        }
        JsonNode installation;
        try {
            installation = client.installation(installationId);
        } catch (GithubException ex) {
            return back + "failed";
        }
        Instant created = instant(text(installation, "created_at"));
        Instant updated = instant(text(installation, "updated_at"));
        Instant recent = Instant.now().minusSeconds(900);
        boolean fresh = (created != null && created.isAfter(recent)) || (updated != null && updated.isAfter(recent));
        if (bound == null && !fresh) {
            return back + "failed";
        }
        if (bound == null) {
            store.saveInstallation(workspace.getId(), installationId, text(installation.path("account"), "login"),
                    text(installation.path("account"), "type"), UUID.fromString(parts[1]));
        }
        try {
            refreshRepositories(store.installation(installationId).orElseThrow());
        } catch (GithubException ex) {
            log.warn("GitHub: no se pudieron leer los repositorios de la instalación {}: {}", installationId, ex.getMessage());
        }
        return back + "connected";
    }

    /** Vuelve a leer de GitHub qué repositorios deja ver la instalación. */
    @Transactional
    public WorkspaceStatus syncRepositories(String slug) {
        Workspace workspace = requireWorkspace(slug);
        Installation installation = store.installationOf(workspace.getId()).orElseThrow(() -> new ConflictException(NOT_CONNECTED));
        attempt(() -> refreshRepositories(installation));
        return workspace(slug);
    }

    private void refreshRepositories(Installation installation) {
        Set<Long> seen = new HashSet<>();
        for (JsonNode repository : client.repositories(installation.installationId())) {
            long githubId = repository.path("id").asLong(0);
            seen.add(githubId);
            store.saveRepository(installation.id(), githubId, text(repository, "full_name"), blankToNull(text(repository, "default_branch")),
                    repository.path("private").asBoolean(true));
        }
        store.keepOnlyRepositories(installation.id(), seen);
    }

    /** Desconecta el workspace: quita la app de la organización en GitHub y borra lo que TaskFlow guardaba de ella. */
    @Transactional
    public void disconnect(String slug) {
        Workspace workspace = requireWorkspace(slug);
        Installation installation = store.installationOf(workspace.getId()).orElse(null);
        if (installation == null) {
            return;
        }
        try {
            client.uninstall(installation.installationId());
        } catch (GithubException | IllegalStateException ex) {
            // Si GitHub no deja (o ya no existía), el workspace igual queda desconectado
            log.info("GitHub: no se pudo quitar la instalación {}: {}", installation.installationId(), ex.getMessage());
        }
        store.deleteInstallation(installation.installationId());
    }

    // ----- Proyecto

    @Transactional(readOnly = true)
    public ProjectStatus project(String slug, UUID projectId) {
        Project project = requireProject(slug, projectId);
        UUID workspaceId = project.getWorkspace().getId();
        boolean connected = store.installationOf(workspaceId).isPresent();
        List<LinkedProject> links = store.linkedProjects(workspaceId);
        Map<UUID, Integer> open = store.openPullRequests(projectId);
        return new ProjectStatus(properties.configured(), connected,
                store.repositoriesOfProject(projectId).stream().map(repository -> view(repository, links, open)).toList(),
                store.repositoriesOf(workspaceId).stream().map(repository -> view(repository, links)).toList(),
                store.settings(projectId), store.rules(projectId));
    }

    /** Dice qué repositorios son del proyecto. De los que entran se traen sus pull requests y ejecuciones recientes. */
    @Transactional
    public ProjectStatus setRepositories(String slug, UUID projectId, List<UUID> repositoryIds) {
        Project project = requireProject(slug, projectId);
        Set<UUID> allowed = new HashSet<>();
        store.repositoriesOf(project.getWorkspace().getId()).forEach(repository -> allowed.add(repository.id()));
        Set<UUID> wanted = new HashSet<>(repositoryIds == null ? List.of() : repositoryIds);
        if (!allowed.containsAll(wanted)) {
            throw new BadRequestException("Uno de los repositorios no está entre los que GitHub comparte con este workspace");
        }
        Set<UUID> before = new HashSet<>();
        store.repositoriesOfProject(projectId).forEach(repository -> before.add(repository.id()));
        store.setProjectRepositories(projectId, wanted);
        for (UUID repositoryId : wanted) {
            if (!before.contains(repositoryId)) {
                store.repository(repositoryId).ifPresent(this::backfill);
            }
        }
        return project(slug, projectId);
    }

    /** Lo reciente de un repositorio recién enlazado, para que el proyecto no empiece vacío. Si GitHub falla, llegará con los avisos. */
    private void backfill(Repository repository) {
        try {
            for (JsonNode pull : client.pullRequests(repository.installationId(), repository.fullName(), 50)) {
                savePullRequest(repository, pull);
            }
            for (JsonNode run : client.workflowRuns(repository.installationId(), repository.fullName(), 30)) {
                saveRun(repository, run);
            }
        } catch (GithubException | IllegalStateException ex) {
            log.info("GitHub: no se pudo traer lo reciente de {}: {}", repository.fullName(), ex.getMessage());
        }
    }

    @Transactional
    public ProjectStatus saveSettings(String slug, UUID projectId, Settings settings) {
        requireProject(slug, projectId);
        for (UUID stateId : new UUID[]{settings.onBranchStateId(), settings.onPrOpenedStateId(), settings.onPrMergedStateId(), settings.onPrClosedStateId()}) {
            if (stateId != null && stateRepository.findByIdAndProjectId(stateId, projectId).isEmpty()) {
                throw new BadRequestException("Uno de los estados no es de este proyecto");
            }
        }
        if (settings.branchFormat() != null && !GithubText.BRANCH_FORMATS.contains(settings.branchFormat())) {
            throw new BadRequestException("Ese formato de rama no existe");
        }
        store.saveSettings(projectId, settings);
        return project(slug, projectId);
    }

    @Transactional
    public ProjectStatus addRule(String slug, UUID projectId, UUID userId, String firesOn, UUID repositoryId, long workflowId,
                                 String workflowName, String ref) {
        requireProject(slug, projectId);
        if (!FIRES_ON_MERGE.equals(firesOn) && !FIRES_ON_COMPLETED.equals(firesOn)) {
            throw new BadRequestException("Esa regla no existe");
        }
        if (!GithubText.validBranch(ref)) {
            throw new BadRequestException("Esa rama no es válida");
        }
        projectRepository(projectId, repositoryId);
        store.addRule(projectId, firesOn, repositoryId, workflowId, blankToNull(workflowName) == null ? "Workflow" : workflowName.strip(), ref.strip(), userId);
        return project(slug, projectId);
    }

    @Transactional
    public void deleteRule(String slug, UUID projectId, UUID ruleId) {
        requireProject(slug, projectId);
        if (!store.deleteRule(projectId, ruleId)) {
            throw new ResourceNotFoundException("Regla no encontrada");
        }
    }

    /** @param filter {@code open} (abiertos y borradores), {@code merged} o {@code all} */
    @Transactional(readOnly = true)
    public List<PullRequest> pullRequests(String slug, UUID projectId, String filter) {
        requireProject(slug, projectId);
        List<String> states = "merged".equals(filter) ? List.of("MERGED") : "all".equals(filter) ? List.of() : List.of("OPEN", "DRAFT");
        return store.pullRequestsOfProject(projectId, states, LIST_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<Run> runs(String slug, UUID projectId) {
        requireProject(slug, projectId);
        return store.runsOfProject(projectId, LIST_LIMIT);
    }

    /** Las ramas enlazadas a tareas del proyecto. */
    @Transactional(readOnly = true)
    public List<Branch> linkedBranches(String slug, UUID projectId) {
        requireProject(slug, projectId);
        return store.branchesOfProject(projectId, LIST_LIMIT);
    }

    /** Qué tareas del proyecto tienen rama o pull request, para el indicador del tablero y la lista. */
    @Transactional(readOnly = true)
    public List<GithubStore.IssueSummary> summary(String slug, UUID projectId) {
        requireProject(slug, projectId);
        return store.summaryOfProject(projectId);
    }

    /** Los workflows del repositorio que se pueden ejecutar a mano, con los datos que pide cada uno. */
    @Transactional(readOnly = true)
    public List<Workflow> workflows(String slug, UUID projectId, UUID repositoryId) {
        requireProject(slug, projectId);
        Repository repository = projectRepository(projectId, repositoryId);
        return attempt(() -> {
            List<Workflow> workflows = new ArrayList<>();
            for (JsonNode workflow : client.workflows(repository.installationId(), repository.fullName())) {
                if (!"active".equals(text(workflow, "state"))) {
                    continue;
                }
                List<String> inputs = GithubText.dispatchInputs(
                        client.fileContent(repository.installationId(), repository.fullName(), text(workflow, "path"), repository.defaultBranch()));
                if (inputs != null) {
                    workflows.add(new Workflow(workflow.path("id").asLong(0), text(workflow, "name"), text(workflow, "path"), inputs));
                }
            }
            return workflows;
        });
    }

    @Transactional(readOnly = true)
    public List<String> branches(String slug, UUID projectId, UUID repositoryId) {
        requireProject(slug, projectId);
        Repository repository = projectRepository(projectId, repositoryId);
        return attempt(() -> client.branches(repository.installationId(), repository.fullName()).stream().map(branch -> text(branch, "name")).toList());
    }

    /** Ejecuta un workflow a mano. Queda como «pedida» hasta que GitHub avisa de la ejecución. */
    @Transactional
    public Run dispatch(String slug, UUID projectId, UUID userId, UUID repositoryId, long workflowId, String workflowName, String ref,
                        Map<String, String> inputs, UUID issueId) {
        requireProject(slug, projectId);
        Repository repository = projectRepository(projectId, repositoryId);
        if (!GithubText.validBranch(ref)) {
            throw new BadRequestException("Esa rama no es válida");
        }
        if (issueId != null && store.issue(issueId).filter(issue -> issue.projectId().equals(projectId)).isEmpty()) {
            throw new BadRequestException("Esa tarea no es de este proyecto");
        }
        UUID runId = store.requestRun(repositoryId, workflowId, workflowName, ref.strip(), issueId, null, userId);
        attempt(() -> client.dispatchWorkflow(repository.installationId(), repository.fullName(), workflowId, ref.strip(), inputs));
        return store.run(runId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public void rerun(String slug, UUID projectId, UUID runId) {
        Run run = projectRun(slug, projectId, runId);
        Repository repository = store.repository(run.repositoryId()).orElseThrow();
        attempt(() -> client.rerun(repository.installationId(), repository.fullName(), run.runId()));
    }

    @Transactional(readOnly = true)
    public void cancel(String slug, UUID projectId, UUID runId) {
        Run run = projectRun(slug, projectId, runId);
        Repository repository = store.repository(run.repositoryId()).orElseThrow();
        attempt(() -> client.cancel(repository.installationId(), repository.fullName(), run.runId()));
    }

    private Run projectRun(String slug, UUID projectId, UUID runId) {
        requireProject(slug, projectId);
        Run run = store.run(runId).filter(found -> store.projectHasRepository(projectId, found.repositoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("Ejecución no encontrada"));
        if (run.runId() == null) {
            throw new ConflictException("GitHub todavía no ha empezado esa ejecución");
        }
        return run;
    }

    /** Enlaza a mano un pull request con una tarea del proyecto ({@code null} lo deja sin tarea). */
    @Transactional
    public PullRequest linkPullRequest(String slug, UUID projectId, UUID pullRequestId, UUID issueId) {
        requireProject(slug, projectId);
        PullRequest pull = store.pullRequest(pullRequestId).filter(found -> store.projectHasRepository(projectId, found.repositoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pull request no encontrado"));
        if (issueId != null && store.issue(issueId).filter(issue -> issue.projectId().equals(projectId)).isEmpty()) {
            throw new BadRequestException("Esa tarea no es de este proyecto");
        }
        store.setPullRequestIssue(pull.id(), issueId);
        if (issueId != null && pull.headBranch() != null) {
            store.linkBranch(pull.repositoryId(), issueId, pull.headBranch());
        }
        return store.pullRequest(pullRequestId).orElseThrow();
    }

    // ----- Tarea

    @Transactional(readOnly = true)
    public IssueDev issue(String slug, UUID projectId, UUID issueId) {
        Issue issue = requireIssue(slug, projectId, issueId);
        List<LinkedProject> links = store.linkedProjects(issue.getProject().getWorkspace().getId());
        List<RepositoryView> repositories = store.repositoriesOfProject(projectId).stream().map(repository -> view(repository, links)).toList();
        return new IssueDev(!repositories.isEmpty(), repositories, store.branchesOf(issueId), store.pullRequestsOf(issueId),
                store.runsOf(issueId, 10), GithubText.branchName(store.settings(projectId).branchFormat(), issue.getCode(), issue.getTitle(),
                issue.getType() == IssueType.BUG));
    }

    /** Los checks del commit en el que está un pull request de la tarea, como están ahora en GitHub. */
    @Transactional(readOnly = true)
    public List<Check> checks(String slug, UUID projectId, UUID issueId, UUID pullRequestId) {
        requireIssue(slug, projectId, issueId);
        PullRequest pull = store.pullRequest(pullRequestId).filter(found -> issueId.equals(found.issueId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pull request no encontrado"));
        if (pull.headSha() == null) {
            return List.of();
        }
        Repository repository = store.repository(pull.repositoryId()).orElseThrow();
        return attempt(() -> client.checkRuns(repository.installationId(), repository.fullName(), pull.headSha()).stream()
                .map(check -> new Check(text(check, "name"), text(check, "status"), blankToNull(text(check, "conclusion")),
                        instant(text(check, "started_at")), instant(text(check, "completed_at")), blankToNull(text(check, "html_url"))))
                .toList());
    }

    /** Enlaza con la tarea una rama que ya existe en el repositorio (una que no la nombra). */
    @Transactional
    public IssueDev linkBranch(String slug, UUID projectId, UUID issueId, UUID repositoryId, String name) {
        Issue issue = requireIssue(slug, projectId, issueId);
        Repository repository = projectRepository(projectId, repositoryId);
        String branch = name == null ? "" : name.strip();
        if (!GithubText.validBranch(branch)) {
            throw new BadRequestException("Esa rama no es válida");
        }
        String sha = attempt(() -> client.branchSha(repository.installationId(), repository.fullName(), branch));
        if (sha == null || sha.isBlank()) {
            throw new BadRequestException("Esa rama no existe en el repositorio");
        }
        branchLinked(repository, issue.getId(), branch, false);
        return issue(slug, projectId, issueId);
    }

    /**
     * Crea en GitHub la rama de la tarea, a partir de otra, y la deja enlazada.
     *
     * @param move si la tarea pasa al estado de la automatización «se crea la rama» del proyecto (si está encendida)
     */
    @Transactional
    public IssueDev createBranch(String slug, UUID projectId, UUID issueId, UUID repositoryId, String base, String name, boolean move) {
        Issue issue = requireIssue(slug, projectId, issueId);
        Repository repository = projectRepository(projectId, repositoryId);
        String branch = name == null ? "" : name.strip();
        String from = base == null || base.isBlank() ? repository.defaultBranch() : base.strip();
        if (!GithubText.validBranch(branch) || from == null || !GithubText.validBranch(from)) {
            throw new BadRequestException("El nombre de la rama no es válido: usa letras, números, guiones, puntos y barras");
        }
        String sha = attempt(() -> client.branchSha(repository.installationId(), repository.fullName(), from));
        if (sha == null || sha.isBlank()) {
            throw new BadRequestException("La rama de la que parte no existe en el repositorio");
        }
        try {
            client.createBranch(repository.installationId(), repository.fullName(), branch, sha);
        } catch (GithubException ex) {
            if (ex.status() == 422) {
                throw new ConflictException("Ya existe una rama con ese nombre en el repositorio");
            }
            throw failure(ex);
        }
        branchLinked(repository, issue.getId(), branch, move);
        return issue(slug, projectId, issueId);
    }

    // ----- Avisos de GitHub

    /**
     * Atiende un aviso de GitHub (ya comprobada su firma). Uno repetido no hace nada.
     *
     * @param event    qué pasó (cabecera {@code X-GitHub-Event})
     * @param delivery el identificador del aviso (cabecera {@code X-GitHub-Delivery})
     */
    @Transactional
    public void handle(String event, String delivery, JsonNode payload) {
        if (delivery != null && !delivery.isBlank() && !store.firstDelivery(delivery)) {
            return;
        }
        long installationId = payload.path("installation").path("id").asLong(0);
        Installation installation = store.installation(installationId).orElse(null);
        if (installation == null) {
            // Una instalación que ningún workspace reclamó (o que ya se desconectó): no hay a quién contarle
            return;
        }
        store.touch(installationId);
        String action = text(payload, "action");
        switch (event == null ? "" : event) {
            case "installation" -> {
                if ("deleted".equals(action)) {
                    store.deleteInstallation(installationId);
                }
            }
            case "installation_repositories" -> {
                for (JsonNode added : payload.path("repositories_added")) {
                    store.saveRepository(installation.id(), added.path("id").asLong(0), text(added, "full_name"), null, added.path("private").asBoolean(true));
                }
                for (JsonNode removed : payload.path("repositories_removed")) {
                    store.deleteRepository(installationId, removed.path("id").asLong(0));
                }
                try {
                    // El aviso no dice la rama principal de los que entran: se lee la lista entera
                    refreshRepositories(installation);
                } catch (GithubException | IllegalStateException ex) {
                    log.info("GitHub: no se pudo releer la lista de repositorios de {}: {}", installation.accountLogin(), ex.getMessage());
                }
            }
            case "pull_request" -> repository(installationId, payload).ifPresent(repository -> onPullRequest(repository, payload.path("pull_request")));
            case "create" -> {
                if ("branch".equals(text(payload, "ref_type"))) {
                    repository(installationId, payload).ifPresent(repository -> onBranch(repository, text(payload, "ref")));
                }
            }
            case "delete" -> {
                if ("branch".equals(text(payload, "ref_type"))) {
                    repository(installationId, payload).ifPresent(repository -> store.deleteBranch(repository.id(), text(payload, "ref")));
                }
            }
            case "workflow_run" -> repository(installationId, payload).ifPresent(repository -> {
                JsonNode run = payload.path("workflow_run");
                saveRun(repository, run);
                refreshChecks(repository, text(run, "head_sha"), "completed".equals(text(run, "status")));
            });
            case "check_suite" -> repository(installationId, payload).ifPresent(repository ->
                    refreshChecks(repository, text(payload.path("check_suite"), "head_sha"), "completed".equals(action)));
            default -> {
                // Un aviso que no interesa
            }
        }
    }

    private java.util.Optional<Repository> repository(long installationId, JsonNode payload) {
        return store.repository(installationId, payload.path("repository").path("id").asLong(0));
    }

    /** Una rama nueva: si nombra una tarea, queda enlazada a ella. */
    private void onBranch(Repository repository, String branch) {
        firstIssue(repository, branch).ifPresent(issue -> branchLinked(repository, issue.id(), branch, true));
    }

    private void branchLinked(Repository repository, UUID issueId, String branch, boolean move) {
        if (!store.linkBranch(repository.id(), issueId, branch)) {
            return;
        }
        record(issueId, "BRANCH|" + branch);
        if (move) {
            IssueRef issue = store.issue(issueId).orElseThrow();
            automate(issue, store.settings(issue.projectId()).onBranchStateId(), NOT_STARTED);
        }
    }

    private void onPullRequest(Repository repository, JsonNode pull) {
        int number = pull.path("number").asInt(0);
        PullRequest before = store.pullRequest(repository.id(), number).orElse(null);
        savePullRequest(repository, pull);
        PullRequest now = store.pullRequest(repository.id(), number).orElse(null);
        if (now == null || now.issueId() == null) {
            return;
        }
        if (now.headBranch() != null) {
            store.linkBranch(repository.id(), now.issueId(), now.headBranch());
        }
        String previous = before == null ? null : before.state();
        if (now.state().equals(previous) && (before == null || now.issueId().equals(before.issueId()))) {
            return;
        }
        IssueRef issue = store.issue(now.issueId()).orElseThrow();
        Settings settings = store.settings(issue.projectId());
        // El título va al final: puede traer cualquier carácter
        String detail = now.number() + "|" + (now.authorLogin() == null ? "" : now.authorLogin()) + "|" + now.title();
        switch (now.state()) {
            case "OPEN" -> {
                // Abierto, o un borrador que pasó a listo para revisar
                record(issue.id(), "PR_OPENED|" + detail);
                automate(issue, settings.onPrOpenedStateId(), OPEN);
            }
            case "MERGED" -> {
                record(issue.id(), "PR_MERGED|" + detail);
                if (!automate(issue, settings.onPrMergedStateId(), OPEN)) {
                    warnPendingCriteria(issue, settings.onPrMergedStateId(), now.number());
                }
                for (Rule rule : store.rules(issue.projectId(), FIRES_ON_MERGE)) {
                    fire(rule, issue.id());
                }
            }
            case "CLOSED" -> {
                record(issue.id(), "PR_CLOSED|" + detail);
                automate(issue, settings.onPrClosedStateId(), IN_PROGRESS);
            }
            default -> {
                // Un borrador no mueve nada
            }
        }
    }

    private void savePullRequest(Repository repository, JsonNode pull) {
        String state = pull.path("merged").asBoolean(false) || !text(pull, "merged_at").isEmpty() ? "MERGED"
                : "closed".equals(text(pull, "state")) ? "CLOSED" : pull.path("draft").asBoolean(false) ? "DRAFT" : "OPEN";
        String head = blankToNull(text(pull.path("head"), "ref"));
        List<String> reviewers = new ArrayList<>();
        pull.path("requested_reviewers").forEach(reviewer -> reviewers.add(text(reviewer, "login")));
        UUID issueId = firstIssue(repository, head, text(pull, "title"), text(pull, "body")).map(IssueRef::id).orElse(null);
        store.savePullRequest(repository.id(), pull.path("number").asInt(0), text(pull, "title"), state, text(pull, "html_url"),
                blankToNull(text(pull.path("user"), "login")), head, blankToNull(text(pull.path("base"), "ref")),
                blankToNull(text(pull.path("head"), "sha")), reviewers.isEmpty() ? null : String.join(", ", reviewers),
                instant(text(pull, "merged_at")), instant(text(pull, "created_at")), instant(text(pull, "updated_at")), issueId);
    }

    private void saveRun(Repository repository, JsonNode run) {
        long runId = run.path("id").asLong(0);
        if (runId == 0) {
            return;
        }
        long workflowId = run.path("workflow_id").asLong(0);
        String branch = blankToNull(text(run, "head_branch"));
        String event = text(run, "event");
        if ("workflow_dispatch".equals(event) && branch != null) {
            // Si la pidió TaskFlow, es la que estaba esperando su número
            store.claimRequested(repository.id(), workflowId, branch, runId);
        }
        Integer prNumber = null;
        for (JsonNode pull : run.path("pull_requests")) {
            prNumber = pull.path("number").asInt(0);
            break;
        }
        UUID issueId = prNumber == null ? null : store.pullRequest(repository.id(), prNumber).map(PullRequest::issueId).orElse(null);
        if (issueId == null) {
            issueId = firstIssue(repository, branch, text(run, "display_title")).map(IssueRef::id).orElse(null);
        }
        store.saveRun(repository.id(), runId, workflowId == 0 ? null : workflowId, blankToNull(text(run, "name")),
                blankToNull(text(run, "display_title")), branch, blankToNull(text(run, "head_sha")), blankToNull(event), text(run, "status"),
                blankToNull(text(run, "conclusion")), blankToNull(text(run.path("actor"), "login")), blankToNull(text(run, "html_url")),
                prNumber, issueId, instant(text(run, "run_started_at")), instant(text(run, "updated_at")));
    }

    /** Cómo van los checks de un commit: en curso mientras algo corre; al terminar se cuentan en GitHub. */
    private void refreshChecks(Repository repository, String sha, boolean completed) {
        if (sha == null || sha.isBlank()) {
            return;
        }
        if (!completed) {
            store.setChecks(repository.id(), sha, "PENDING", 0, 0);
            return;
        }
        try {
            int total = 0;
            int failed = 0;
            int pending = 0;
            List<String> failedNames = new ArrayList<>();
            for (JsonNode check : client.checkRuns(repository.installationId(), repository.fullName(), sha)) {
                total++;
                if (!"completed".equals(text(check, "status"))) {
                    pending++;
                } else if (FAILED.contains(text(check, "conclusion"))) {
                    failed++;
                    failedNames.add(text(check, "name"));
                }
            }
            String state = failed > 0 ? "FAILURE" : pending > 0 ? "PENDING" : "SUCCESS";
            if ("FAILURE".equals(state)) {
                for (PullRequest pull : store.pullRequestsAt(repository.id(), sha)) {
                    if (pull.issueId() != null && !"FAILURE".equals(pull.checksState())) {
                        record(pull.issueId(), "CHECKS_FAILED|" + pull.number() + "|" + String.join(", ", failedNames));
                    }
                }
            }
            store.setChecks(repository.id(), sha, state, total, failed);
        } catch (GithubException | IllegalStateException ex) {
            log.info("GitHub: no se pudieron leer los checks de {} en {}: {}", sha, repository.fullName(), ex.getMessage());
        }
    }

    /** La primera tarea que nombran esos textos, entre los proyectos que tienen enlazado el repositorio. */
    private java.util.Optional<IssueRef> firstIssue(Repository repository, String... texts) {
        for (Code code : GithubText.codes(texts)) {
            java.util.Optional<IssueRef> issue = store.issueByCode(repository.id(), code.identifier(), code.number());
            if (issue.isPresent()) {
                return issue;
            }
        }
        return java.util.Optional.empty();
    }

    /** Mueve la tarea al estado de una automatización del proyecto, si está encendida y la tarea está donde corresponde. */
    private boolean automate(IssueRef issue, UUID stateId, Set<StateGroup> fromGroups) {
        return stateId != null && issueService.moveAutomatically(issue.id(), stateId, fromGroups);
    }

    /**
     * El pull request se fusionó y la tarea se habría completado sola, pero le faltan criterios de aceptación: eso lo
     * decide una persona, así que se le avisa a quien la tiene asignada.
     */
    private void warnPendingCriteria(IssueRef ref, UUID stateId, int pullRequestNumber) {
        if (stateId == null || !OPEN.contains(StateGroup.valueOf(ref.stateGroup()))) {
            return;
        }
        boolean completes = stateRepository.findByIdAndProjectId(stateId, ref.projectId())
                .map(state -> state.getStateGroup() == StateGroup.COMPLETED).orElse(false);
        if (!completes) {
            return;
        }
        issueRepository.findById(ref.id()).filter(issue -> issue.getCriteriaTotal() > issue.getCriteriaDone())
                .ifPresent(issue -> notificationService.notifyMergedWithPendingCriteria(issue, pullRequestNumber));
    }

    /** Deja en el historial de la tarea lo que pasó en GitHub (sin autor de TaskFlow). */
    private void record(UUID issueId, String what) {
        issueRepository.findById(issueId).ifPresent(issue -> activityService.record(issue, null, ActivityField.GITHUB, null, what));
    }

    // ----- Reglas

    /** Una tarea se completó: se ejecutan las reglas «al completar una tarea» de su proyecto. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIssueCompleted(IssueCompletedEvent event) {
        if (!properties.configured()) {
            return;
        }
        for (Rule rule : store.rules(event.projectId(), FIRES_ON_COMPLETED)) {
            fire(rule, event.issueId());
        }
    }

    /** Pide a GitHub el workflow de una regla. Si falla, queda en el registro y lo demás sigue. */
    private void fire(Rule rule, UUID issueId) {
        Repository repository = store.repository(rule.repositoryId()).orElse(null);
        if (repository == null) {
            return;
        }
        UUID runId = store.requestRun(rule.repositoryId(), rule.workflowId(), rule.workflowName(), rule.ref(), issueId, rule.id(), null);
        try {
            client.dispatchWorkflow(repository.installationId(), repository.fullName(), rule.workflowId(), rule.ref(), Map.of());
        } catch (GithubException | IllegalStateException ex) {
            log.warn("GitHub: la regla {} no pudo ejecutar «{}» en {}: {}", rule.id(), rule.workflowName(), repository.fullName(), ex.getMessage());
            store.failRequestedRun(runId);
        }
    }

    // ----- Utilidades

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ConflictException(NOT_CONFIGURED);
        }
    }

    private Workspace requireWorkspace(String slug) {
        return workspaceRepository.findBySlug(slug).orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado"));
    }

    private Project requireProject(String slug, UUID projectId) {
        return projectRepository.findInWorkspace(projectId, slug).orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado"));
    }

    private Issue requireIssue(String slug, UUID projectId, UUID issueId) {
        requireProject(slug, projectId);
        return issueRepository.findById(issueId).filter(issue -> issue.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResourceNotFoundException("Tarea no encontrada"));
    }

    private Repository projectRepository(UUID projectId, UUID repositoryId) {
        if (repositoryId == null || !store.projectHasRepository(projectId, repositoryId)) {
            throw new BadRequestException(REPOSITORY_NOT_FOUND);
        }
        return store.repository(repositoryId).orElseThrow(() -> new BadRequestException(REPOSITORY_NOT_FOUND));
    }

    private interface Call<T> {
        T run();
    }

    /** Una petición a GitHub cuyo fallo se le explica a la persona. */
    private <T> T attempt(Call<T> call) {
        try {
            return call.run();
        } catch (GithubException ex) {
            throw failure(ex);
        } catch (IllegalStateException ex) {
            log.error("GitHub: la app no está bien configurada", ex);
            throw new ConflictException(NOT_CONFIGURED);
        }
    }

    private void attempt(Runnable call) {
        attempt(() -> {
            call.run();
            return null;
        });
    }

    private static ConflictException failure(GithubException ex) {
        return new ConflictException(ex.status() == 403 || ex.status() == 401 ? NOT_ALLOWED
                : ex.status() == 404 ? "GitHub no encontró eso: puede que el repositorio ya no esté compartido con TaskFlow" : UNAVAILABLE);
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notInstant) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException notOffset) {
                return null;
            }
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
