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
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.exception.ResourceNotFoundException;
import com.taskflow.api.github.GithubStore.Branch;
import com.taskflow.api.github.GithubStore.Installation;
import com.taskflow.api.github.GithubStore.IssueRef;
import com.taskflow.api.github.GithubStore.LinkedProject;
import com.taskflow.api.github.GithubStore.MemberAccount;
import com.taskflow.api.github.GithubStore.PullRequest;
import com.taskflow.api.github.GithubStore.Repository;
import com.taskflow.api.github.GithubStore.Rule;
import com.taskflow.api.github.GithubStore.Run;
import com.taskflow.api.github.GithubStore.Settings;
import com.taskflow.api.github.GithubText.Code;
import com.taskflow.api.repository.IssueRepository;
import com.taskflow.api.repository.ProjectRepository;
import com.taskflow.api.repository.StateRepository;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.service.ActivityService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.service.impl.IssueCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    /** Cuánto se recuerdan los workflows ejecutables de un repositorio (segundos). */
    private static final long WORKFLOWS_SECONDS = 180;
    private static final long BRANCHES_SECONDS = 60;
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
    private final GithubAccounts accounts;
    private final GithubBackground background;
    /** Los workflows ejecutables de cada repositorio, unos minutos: leerlos cuesta una petición a GitHub por workflow. */
    private final Map<UUID, CachedWorkflows> workflowCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, CachedBranches> branchCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedBranches(List<String> names, Instant at) {
    }

    private record CachedWorkflows(List<Workflow> workflows, Instant at) {
    }
    private final UserRepository userRepository;

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
     * @param accounts   los miembros del workspace con su usuario de GitHub (sin él solo pueden ver)
     */
    public record WorkspaceStatus(boolean configured, boolean connected, String accountLogin, String accountType, String installedByName,
                                  Instant installedAt, Instant lastEventAt, String manageUrl, List<RepositoryView> repositories,
                                  List<MemberAccount> accounts) {
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
        return workspace(slug, true);
    }

    /**
     * @param manage si quien pregunta puede configurar GitHub: solo entonces ve los repositorios que comparte la
     *               instalación y a qué proyectos van (los demás ven si está conectado y las cuentas del equipo)
     */
    @Transactional(readOnly = true)
    public WorkspaceStatus workspace(String slug, boolean manage) {
        Workspace workspace = requireWorkspace(slug);
        Installation installation = store.installationOf(workspace.getId()).orElse(null);
        if (installation == null) {
            return new WorkspaceStatus(properties.configured(), false, null, null, null, null, null, null, List.of(), List.of());
        }
        String manageUrl = "Organization".equalsIgnoreCase(installation.accountType())
                ? properties.webUrl() + "/organizations/" + installation.accountLogin() + "/settings/installations/" + installation.installationId()
                : properties.webUrl() + "/settings/installations/" + installation.installationId();
        return new WorkspaceStatus(properties.configured(), true, installation.accountLogin(), installation.accountType(),
                installation.installedByName(), installation.createdAt(), installation.lastEventAt(), manageUrl,
                manage ? repositoryViews(workspace.getId()) : List.of(), store.memberAccounts(workspace.getId()));
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
        // Con su cuenta se comprueba, al volver, que la instalación es de quien la pidió
        if (!accounts.linked(userId)) {
            throw new ConflictException(GithubAccounts.NOT_LINKED);
        }
        String payload = workspace.getId() + "|" + userId + "|" + Instant.now().plusSeconds(STATE_SECONDS).getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return properties.webUrl() + "/apps/" + properties.appSlug() + "/installations/new?state=" + encoded + "."
                + GithubKeys.sign(properties.webhookSecret(), encoded);
    }

    /**
     * GitHub vuelve aquí cuando el PM termina de instalar la app. Guarda la instalación para el workspace que la pidió
     * y devuelve a dónde va el navegador.
     * <p>
     * El número de instalación llega en la dirección, así que no se confía en él: no puede estar ya en otro workspace
     * y GitHub tiene que confirmar que quien pidió conectar administra la cuenta donde está la instalación (es esa
     * cuenta personal, o es dueña de la organización).
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
        UUID installer = UUID.fromString(parts[1]);
        if (!accounts.linked(installer)) {
            return back + "unlinked";
        }
        JsonNode installation;
        try {
            installation = client.installation(installationId);
        } catch (GithubException ex) {
            // Tampoco existe (o no es de esta app) un número inventado
            return back + (ex.status() == 404 ? "foreign" : "failed");
        }
        // El número de instalación llega en la dirección: solo vale si GitHub dice que quien la pidió administra esa cuenta
        if (!accounts.administersInstallation(installer, installationId, installation)) {
            return back + "foreign";
        }
        if (bound == null) {
            store.saveInstallation(workspace.getId(), installationId, text(installation.path("account"), "login"),
                    text(installation.path("account"), "type"), installer);
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
        applyRepositories(installation, client.repositories(installation.installationId()));
    }

    private void applyRepositories(Installation installation, List<JsonNode> repositories) {
        Set<Long> seen = new HashSet<>();
        for (JsonNode repository : repositories) {
            long githubId = repository.path("id").asLong(0);
            seen.add(githubId);
            store.saveRepository(installation.id(), githubId, text(repository, "full_name"), blankToNull(text(repository, "default_branch")),
                    repository.path("private").asBoolean(true));
        }
        store.keepOnlyRepositories(installation.id(), seen);
    }

    /**
     * Antes de borrar un workspace: quita la app de su cuenta de GitHub, para que no quede instalada sin workspace (si
     * GitHub no responde, el workspace igual se borra y la instalación cae con él).
     */
    @Transactional
    public void forgetWorkspace(UUID workspaceId) {
        store.installationOf(workspaceId).ifPresent(installation -> {
            try {
                client.uninstall(installation.installationId());
            } catch (GithubException | IllegalStateException ex) {
                log.warn("GitHub: no se pudo quitar la instalación {} del workspace borrado: {}", installation.installationId(), ex.getMessage());
            }
        });
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
        return project(slug, projectId, true);
    }

    /**
     * @param manage si quien pregunta puede configurar GitHub: solo entonces ve los demás repositorios del workspace
     *               (para enlazarlos) y a qué otros proyectos va cada uno
     */
    @Transactional(readOnly = true)
    public ProjectStatus project(String slug, UUID projectId, boolean manage) {
        Project project = requireProject(slug, projectId);
        UUID workspaceId = project.getWorkspace().getId();
        boolean connected = store.installationOf(workspaceId).isPresent();
        List<LinkedProject> links = manage ? store.linkedProjects(workspaceId) : ownLinks(workspaceId, projectId);
        Map<UUID, Integer> open = store.openPullRequests(projectId);
        return new ProjectStatus(properties.configured(), connected,
                store.repositoriesOfProject(projectId).stream().map(repository -> view(repository, links, open)).toList(),
                manage ? store.repositoriesOf(workspaceId).stream().map(repository -> view(repository, links)).toList() : List.of(),
                store.settings(projectId), store.rules(projectId));
    }

    /** Los enlaces de repositorios de este proyecto solamente (sin nombrar los otros proyectos que comparten repositorio). */
    private List<LinkedProject> ownLinks(UUID workspaceId, UUID projectId) {
        return store.linkedProjects(workspaceId).stream().filter(link -> link.projectId().equals(projectId)).toList();
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
        Repository repository = projectRepository(projectId, repositoryId);
        // La regla ejecuta el workflow con la cuenta de quien la crea: tiene que poder hacerlo en GitHub
        if (!accounts.canPush(userId, repository.fullName())) {
            throw new ForbiddenException("Tu cuenta de GitHub no puede escribir en ese repositorio, y la regla ejecutaría el workflow a tu nombre");
        }
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
        return store.pullRequestsOfProject(projectId, states, LIST_LIMIT).stream()
                .map(pull -> pull.issueProjectId() == null || pull.issueProjectId().equals(projectId) ? pull : pull.withoutIssue()).toList();
    }

    @Transactional(readOnly = true)
    public List<Run> runs(String slug, UUID projectId) {
        requireProject(slug, projectId);
        return store.runsOfProject(projectId, LIST_LIMIT).stream()
                .map(run -> run.issueProjectId() == null || run.issueProjectId().equals(projectId) ? run : run.withoutIssue()).toList();
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
    public List<Workflow> workflows(String slug, UUID projectId, UUID repositoryId) {
        requireProject(slug, projectId);
        Repository repository = projectRepository(projectId, repositoryId);
        CachedWorkflows cached = workflowCache.get(repositoryId);
        if (cached != null && cached.at().isAfter(Instant.now().minusSeconds(WORKFLOWS_SECONDS))) {
            return cached.workflows();
        }
        List<Workflow> fresh = readWorkflows(repository);
        workflowCache.put(repositoryId, new CachedWorkflows(fresh, Instant.now()));
        return fresh;
    }

    private List<Workflow> readWorkflows(Repository repository) {
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

    /**
     * Las ramas del repositorio. Un repositorio con cientos de ramas son varias páginas de GitHub: se guardan un minuto
     * para que abrir y cerrar los diálogos no las vuelva a pedir.
     */
    public List<String> branches(String slug, UUID projectId, UUID repositoryId) {
        requireProject(slug, projectId);
        Repository repository = projectRepository(projectId, repositoryId);
        CachedBranches cached = branchCache.get(repositoryId);
        if (cached != null && cached.at().isAfter(Instant.now().minusSeconds(BRANCHES_SECONDS))) {
            return cached.names();
        }
        List<String> fresh = attempt(() -> client.branches(repository.installationId(), repository.fullName()).stream()
                .map(branch -> text(branch, "name")).toList());
        branchCache.put(repositoryId, new CachedBranches(fresh, Instant.now()));
        return fresh;
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
        String token = accounts.token(userId);
        UUID runId = store.requestRun(repositoryId, workflowId, workflowName, ref.strip(), issueId, null, userId);
        attemptAs(repository, () -> client.dispatchWorkflowAs(token, repository.fullName(), workflowId, ref.strip(), inputs));
        return store.run(runId).orElseThrow();
    }

    @Transactional
    public void rerun(String slug, UUID projectId, UUID userId, UUID runId) {
        Run run = projectRun(slug, projectId, runId);
        Repository repository = store.repository(run.repositoryId()).orElseThrow();
        String token = accounts.token(userId);
        attemptAs(repository, () -> client.rerunAs(token, repository.fullName(), run.runId()));
    }

    @Transactional
    public void cancel(String slug, UUID projectId, UUID userId, UUID runId) {
        Run run = projectRun(slug, projectId, runId);
        Repository repository = store.repository(run.repositoryId()).orElseThrow();
        String token = accounts.token(userId);
        attemptAs(repository, () -> client.cancelAs(token, repository.fullName(), run.runId()));
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

    /**
     * Enlaza a mano un pull request con una tarea del proyecto ({@code null} lo deja sin tarea). Como todo lo que se hace
     * con GitHub desde TaskFlow, pide la cuenta de GitHub de la persona enlazada.
     */
    @Transactional
    public PullRequest linkPullRequest(String slug, UUID projectId, UUID userId, UUID pullRequestId, UUID issueId) {
        requireProject(slug, projectId);
        requireLinked(userId);
        PullRequest pull = store.pullRequest(pullRequestId).filter(found -> store.projectHasRepository(projectId, found.repositoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pull request no encontrado"));
        if (issueId != null && store.issue(issueId).filter(issue -> issue.projectId().equals(projectId)).isEmpty()) {
            throw new BadRequestException("Esa tarea no es de este proyecto");
        }
        // Con un repositorio compartido entre proyectos, el pull request de una tarea de otro proyecto no se toca desde este
        if (pull.issueProjectId() != null && !pull.issueProjectId().equals(projectId)) {
            throw new ConflictException("Ese pull request está enlazado a una tarea de otro proyecto");
        }
        store.setPullRequestIssue(pull.id(), issueId);
        if (issueId != null && pull.headBranch() != null && !issueId.equals(pull.issueId())) {
            store.linkBranch(pull.repositoryId(), issueId, pull.headBranch());
        }
        // Al quitarlo (o pasarlo a otra tarea), su rama deja la tarea anterior
        if (pull.issueId() != null && !pull.issueId().equals(issueId) && pull.headBranch() != null) {
            store.branchesOf(pull.issueId()).stream()
                    .filter(branch -> branch.repositoryId().equals(pull.repositoryId()) && branch.name().equals(pull.headBranch()))
                    .forEach(branch -> store.unlinkBranch(pull.issueId(), branch.id()));
        }
        return store.pullRequest(pullRequestId).orElseThrow();
    }

    // ----- Tarea

    @Transactional(readOnly = true)
    public IssueDev issue(String slug, UUID projectId, UUID issueId) {
        Issue issue = requireIssue(slug, projectId, issueId);
        List<LinkedProject> links = ownLinks(issue.getProject().getWorkspace().getId(), projectId);
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
    public IssueDev linkBranch(String slug, UUID projectId, UUID userId, UUID issueId, UUID repositoryId, String name) {
        Issue issue = requireIssue(slug, projectId, issueId);
        requireLinked(userId);
        Repository repository = projectRepository(projectId, repositoryId);
        String branch = name == null ? "" : name.strip();
        if (!GithubText.validBranch(branch)) {
            throw new BadRequestException("Esa rama no es válida");
        }
        String sha = attempt(() -> client.branchSha(repository.installationId(), repository.fullName(), branch));
        if (sha == null || sha.isBlank()) {
            throw new BadRequestException("Esa rama no existe en el repositorio");
        }
        branchLinked(repository, issue.getId(), branch, false, userId);
        return issue(slug, projectId, issueId);
    }

    /** Quita una rama de la tarea. La rama sigue en GitHub: TaskFlow no borra ramas. */
    @Transactional
    public IssueDev unlinkBranch(String slug, UUID projectId, UUID userId, UUID issueId, UUID branchId) {
        requireIssue(slug, projectId, issueId);
        requireLinked(userId);
        store.unlinkBranch(issueId, branchId).orElseThrow(() -> new ResourceNotFoundException("Rama no encontrada"));
        return issue(slug, projectId, issueId);
    }

    /**
     * Crea en GitHub la rama de la tarea, a partir de otra, y la deja enlazada. La crea la persona con su cuenta de
     * GitHub: tiene que tenerla enlazada y permiso de escritura en el repositorio.
     *
     * @param move si la tarea pasa al estado de la automatización «se crea la rama» del proyecto (si está encendida)
     */
    @Transactional
    public IssueDev createBranch(String slug, UUID projectId, UUID userId, UUID issueId, UUID repositoryId, String base, String name,
                                 boolean move) {
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
        String token = accounts.token(userId);
        try {
            client.createBranchAs(token, repository.fullName(), branch, sha);
        } catch (GithubException ex) {
            if (ex.status() == 422) {
                throw new ConflictException("Ya existe una rama con ese nombre en el repositorio");
            }
            throw failureAs(repository, ex);
        }
        branchCache.remove(repositoryId);
        branchLinked(repository, issue.getId(), branch, move, userId);
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
                // El aviso no dice la rama principal de los que entran: se lee la lista entera, ya fuera de esta transacción
                background.afterCommit(() -> {
                    List<JsonNode> repositories = client.repositories(installationId);
                    background.write(() -> store.installation(installationId).ifPresent(current -> applyRepositories(current, repositories)));
                });
            }
            case "pull_request" -> repository(installationId, payload).ifPresent(repository ->
                    onPullRequest(repository, payload.path("pull_request"), payload.path("sender")));
            case "create" -> {
                if ("branch".equals(text(payload, "ref_type"))) {
                    repository(installationId, payload).ifPresent(repository -> branchCache.remove(repository.id()));
                    repository(installationId, payload).ifPresent(repository ->
                            onBranch(repository, text(payload, "ref"), payload.path("sender").path("id").asLong(0)));
                }
            }
            case "delete" -> {
                if ("branch".equals(text(payload, "ref_type"))) {
                    repository(installationId, payload).ifPresent(repository -> {
                        branchCache.remove(repository.id());
                        store.deleteBranch(repository.id(), text(payload, "ref"));
                    });
                }
            }
            case "workflow_run" -> repository(installationId, payload).ifPresent(repository -> {
                // Las ejecuciones de un repositorio que ningún proyecto tiene enlazado no se guardan: nadie las vería
                if (store.repositoryLinked(repository.id())) {
                    JsonNode run = payload.path("workflow_run");
                    saveRun(repository, run);
                    refreshChecks(repository, text(run, "head_sha"), "completed".equals(text(run, "status")));
                }
            });
            case "check_suite" -> repository(installationId, payload).ifPresent(repository ->
                    refreshChecks(repository, text(payload.path("check_suite"), "head_sha"), "completed".equals(action)));
            default -> {
                // Un aviso que no interesa
            }
        }
        // Al final: es lo único que toca la fila de la instalación, y así dos avisos a la vez casi no se esperan
        store.touch(installationId);
    }

    private java.util.Optional<Repository> repository(long installationId, JsonNode payload) {
        return store.repository(installationId, payload.path("repository").path("id").asLong(0));
    }

    /** Una rama nueva: si nombra una tarea, queda enlazada a ella. */
    private void onBranch(Repository repository, String branch, long senderId) {
        UUID actor = store.memberByGithubId(repository.id(), senderId).orElse(null);
        firstIssue(repository, branch).ifPresent(issue -> branchLinked(repository, issue.id(), branch, true, actor));
    }

    private void branchLinked(Repository repository, UUID issueId, String branch, boolean move, UUID actorId) {
        if (!store.linkBranch(repository.id(), issueId, branch)) {
            return;
        }
        record(issueId, "BRANCH|" + branch, actorId);
        if (move) {
            IssueRef issue = store.issue(issueId).orElseThrow();
            automate(issue, store.settings(issue.projectId()).onBranchStateId(), NOT_STARTED);
        }
    }

    /**
     * @param sender quién provocó el aviso en GitHub (quien abrió, fusionó o cerró el pull request)
     */
    private void onPullRequest(Repository repository, JsonNode pull, JsonNode sender) {
        int number = pull.path("number").asInt(0);
        PullRequest before = store.pullRequest(repository.id(), number).orElse(null);
        savePullRequest(repository, pull);
        PullRequest now = store.pullRequest(repository.id(), number).orElse(null);
        if (now == null || now.issueId() == null) {
            return;
        }
        String previous = before == null ? null : before.state();
        boolean sameState = now.state().equals(previous);
        boolean sameIssue = before != null && now.issueId().equals(before.issueId());
        // Su rama queda con la tarea cuando el PR se enlaza (no en cada aviso: si alguien la quitó de la tarea, no vuelve).
        // La de un fork no es una rama de este repositorio.
        if (!sameIssue && now.headBranch() != null && !fromFork(pull)) {
            store.linkBranch(repository.id(), now.issueId(), now.headBranch());
        }
        // Lo que mueve la tarea y lanza reglas es que el pull request cambie de estado. Si no cambió y solo apareció la
        // tarea (alguien puso su código en el título), cuenta únicamente para uno abierto: uno ya fusionado o cerrado no
        // vuelve a completar la tarea ni a ejecutar las reglas.
        if (sameState && (sameIssue || !"OPEN".equals(now.state()))) {
            return;
        }
        IssueRef issue = store.issue(now.issueId()).orElseThrow();
        Settings settings = store.settings(issue.projectId());
        // Quién lo hizo: abrirlo, su autor; fusionarlo o cerrarlo, quien lo fusionó o cerró (si GitHub lo dice)
        JsonNode merger = pull.path("merged_by");
        JsonNode whoNode = "OPEN".equals(now.state()) ? pull.path("user")
                : merger.path("id").asLong(0) > 0 ? merger : sender.path("id").asLong(0) > 0 ? sender : pull.path("user");
        String who = blankToNull(text(whoNode, "login"));
        UUID actor = store.memberByGithubId(repository.id(), whoNode.path("id").asLong(0)).orElse(null);
        // El título va al final: puede traer cualquier carácter
        String detail = now.number() + "|" + (who == null ? "" : who) + "|" + now.title();
        switch (now.state()) {
            case "OPEN" -> {
                // Abierto, o un borrador que pasó a listo para revisar
                record(issue.id(), "PR_OPENED|" + detail, actor);
                automate(issue, settings.onPrOpenedStateId(), OPEN);
            }
            case "MERGED" -> {
                record(issue.id(), "PR_MERGED|" + detail, actor);
                if (!automate(issue, settings.onPrMergedStateId(), OPEN)) {
                    warnPendingCriteria(issue, settings.onPrMergedStateId(), now.number());
                }
                for (Rule rule : store.rules(issue.projectId(), FIRES_ON_MERGE)) {
                    fire(rule, issue.id());
                }
            }
            case "CLOSED" -> {
                record(issue.id(), "PR_CLOSED|" + detail, actor);
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
        // Un PR desde un fork de alguien de fuera no se enlaza solo con una tarea (en un repositorio público cualquiera
        // podría abrir uno que nombre una tarea y moverla); alguien del equipo puede enlazarlo a mano
        UUID issueId = fromFork(pull) && !fromTeam(pull) ? null
                : firstIssue(repository, head, text(pull, "title"), text(pull, "body")).map(IssueRef::id).orElse(null);
        store.savePullRequest(repository.id(), pull.path("number").asInt(0), text(pull, "title"), state, text(pull, "html_url"),
                blankToNull(text(pull.path("user"), "login")), head, blankToNull(text(pull.path("base"), "ref")),
                blankToNull(text(pull.path("head"), "sha")), reviewers.isEmpty() ? null : String.join(", ", reviewers),
                instant(text(pull, "merged_at")), instant(text(pull, "created_at")), instant(text(pull, "updated_at")), issueId);
    }

    /** Si el pull request viene de otro repositorio (un fork). */
    private static boolean fromFork(JsonNode pull) {
        long head = pull.path("head").path("repo").path("id").asLong(0);
        long base = pull.path("base").path("repo").path("id").asLong(0);
        return head != 0 && base != 0 && head != base;
    }

    /** Si quien abrió el pull request es del equipo del repositorio (dueño, miembro de la organización o colaborador). */
    private static boolean fromTeam(JsonNode pull) {
        return Set.of("OWNER", "MEMBER", "COLLABORATOR").contains(text(pull, "author_association"));
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
        // Un push a la rama principal o a una rama sin pull request no tiene a quién contarle sus checks
        if (store.pullRequestsAt(repository.id(), sha).isEmpty()) {
            return;
        }
        background.afterCommit(() -> countChecks(repository, sha));
    }

    /** Le pregunta a GitHub por los checks del commit (sin transacción abierta) y guarda el resultado. */
    private void countChecks(Repository repository, String sha) {
        int total = 0;
        int failed = 0;
        int pending = 0;
        List<String> failedNames = new ArrayList<>();
        try {
            for (JsonNode check : client.checkRuns(repository.installationId(), repository.fullName(), sha)) {
                total++;
                if (!"completed".equals(text(check, "status"))) {
                    pending++;
                } else if (FAILED.contains(text(check, "conclusion"))) {
                    failed++;
                    failedNames.add(text(check, "name"));
                }
            }
        } catch (GithubException | IllegalStateException ex) {
            log.info("GitHub: no se pudieron leer los checks de {} en {}: {}", sha, repository.fullName(), ex.getMessage());
            return;
        }
        String state = failed > 0 ? "FAILURE" : pending > 0 ? "PENDING" : "SUCCESS";
        int counted = total;
        int broken = failed;
        background.write(() -> {
            if ("FAILURE".equals(state)) {
                for (PullRequest pull : store.pullRequestsAt(repository.id(), sha)) {
                    if (pull.issueId() != null && !"FAILURE".equals(pull.checksState())) {
                        record(pull.issueId(), "CHECKS_FAILED|" + pull.number() + "|" + String.join(", ", failedNames));
                    }
                }
            }
            store.setChecks(repository.id(), sha, state, counted, broken);
        });
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
        record(issueId, what, null);
    }

    /**
     * @param actorId la persona de TaskFlow que lo hizo en GitHub, si enlazó su cuenta ({@code null}: no se sabe quién
     *                es aquí, y el historial muestra su usuario de GitHub)
     */
    private void record(UUID issueId, String what, UUID actorId) {
        issueRepository.findById(issueId).ifPresent(issue -> activityService.record(issue,
                actorId == null ? null : userRepository.findById(actorId).orElse(null), ActivityField.GITHUB, null, what));
    }

    // ----- Reglas

    /**
     * Una tarea se completó: se ejecutan las reglas «al completar una tarea» de su proyecto. Va en los hilos de la
     * integración: quien completó la tarea no espera a GitHub ni gasta otra conexión a la base de datos.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIssueCompleted(IssueCompletedEvent event) {
        if (!properties.configured()) {
            return;
        }
        background.run(() -> {
            for (Rule rule : background.write(() -> store.rules(event.projectId(), FIRES_ON_COMPLETED))) {
                Repository repository = background.write(() -> store.repository(rule.repositoryId()).orElse(null));
                if (repository != null) {
                    UUID runId = background.write(() -> store.requestRun(rule.repositoryId(), rule.workflowId(), rule.workflowName(),
                            rule.ref(), event.issueId(), rule.id(), null));
                    dispatchRule(rule, repository, runId);
                }
            }
        });
    }

    /**
     * La regla de un pull request recién fusionado, dentro de la transacción del aviso: la ejecución queda pedida ya, y
     * a GitHub se le pide cuando la transacción se confirme (así, cuando GitHub avise de la ejecución, la fila existe).
     */
    private void fire(Rule rule, UUID issueId) {
        Repository repository = store.repository(rule.repositoryId()).orElse(null);
        if (repository == null) {
            return;
        }
        UUID runId = store.requestRun(rule.repositoryId(), rule.workflowId(), rule.workflowName(), rule.ref(), issueId, rule.id(), null);
        background.afterCommit(() -> dispatchRule(rule, repository, runId));
    }

    /**
     * Pide a GitHub el workflow de una regla, con la cuenta de quien la creó: sale a su nombre y GitHub solo lo deja si
     * todavía puede escribir en el repositorio. Si esa persona ya no está en el workspace o desenlazó su cuenta, la regla
     * no ejecuta nada. Si falla, queda en el registro y la ejecución pedida se quita.
     */
    private void dispatchRule(Rule rule, Repository repository, UUID runId) {
        try {
            if (rule.createdById() == null || !store.isWorkspaceMember(repository.workspaceId(), rule.createdById())) {
                throw new IllegalStateException("quien creó la regla ya no está en el workspace");
            }
            String token = accounts.token(rule.createdById());
            client.dispatchWorkflowAs(token, repository.fullName(), rule.workflowId(), rule.ref(), Map.of());
        } catch (GithubException | ConflictException | IllegalStateException ex) {
            log.warn("GitHub: la regla {} no pudo ejecutar «{}» en {}: {}", rule.id(), rule.workflowName(), repository.fullName(), ex.getMessage());
            background.write(() -> store.failRequestedRun(runId));
        }
    }

    // ----- Utilidades

    private void requireLinked(UUID userId) {
        if (!accounts.linked(userId)) {
            throw new ConflictException(GithubAccounts.NOT_LINKED);
        }
    }

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

    /** Una petición a GitHub hecha con la cuenta de la persona: si GitHub la rechaza, es su cuenta la que no tiene permiso. */
    private void attemptAs(Repository repository, Runnable call) {
        try {
            call.run();
        } catch (GithubException ex) {
            throw failureAs(repository, ex);
        }
    }

    private static ConflictException failureAs(Repository repository, GithubException ex) {
        if (ex.status() == 401) {
            return new ConflictException("GitHub ya no acepta tu cuenta. Vuelve a conectarla en tu perfil.");
        }
        if (ex.status() == 403 || ex.status() == 404) {
            return new ConflictException("Tu cuenta de GitHub no tiene permiso de escritura en " + repository.fullName()
                    + ". Pídeselo a quien administra el repositorio en GitHub.");
        }
        return new ConflictException(UNAVAILABLE);
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
