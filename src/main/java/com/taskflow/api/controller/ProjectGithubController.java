package com.taskflow.api.controller;

import com.taskflow.api.github.GithubService;
import com.taskflow.api.github.GithubService.Check;
import com.taskflow.api.github.GithubService.IssueDev;
import com.taskflow.api.github.GithubService.ProjectStatus;
import com.taskflow.api.github.GithubService.Workflow;
import com.taskflow.api.github.GithubStore.Branch;
import com.taskflow.api.github.GithubStore.IssueSummary;
import com.taskflow.api.github.GithubStore.PullRequest;
import com.taskflow.api.github.GithubStore.Run;
import com.taskflow.api.github.GithubStore.Settings;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.security.WorkspaceSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GitHub dentro de un proyecto. Ver (repositorios, pull requests, ejecuciones y lo de cada tarea) es de quien puede ver
 * el proyecto; configurarlo y ejecutar workflows van por permiso; crear la rama de una tarea, enlazarle o quitarle una
 * rama o un pull request es editar la tarea (y, como todo lo de GitHub, pide la cuenta de GitHub de la persona).
 */
@Tag(name = "GitHub del proyecto", description = "Repositorios, pull requests, GitHub Actions y el desarrollo de cada tarea")
@RestController
@RequestMapping("/api/v1/workspaces/{slug}/projects/{projectId}/github")
@RequiredArgsConstructor
public class ProjectGithubController {

    private final GithubService githubService;
    private final WorkspaceSecurity workspaceSecurity;

    public record RepositoriesRequest(List<UUID> repositoryIds) {
    }

    public record RuleRequest(String firesOn, UUID repositoryId, long workflowId, String workflowName, String ref) {
    }

    public record DispatchRequest(UUID repositoryId, long workflowId, String workflowName, String ref, Map<String, String> inputs,
                                  UUID issueId) {
    }

    public record LinkRequest(UUID issueId) {
    }

    /**
     * @param move si la tarea pasa al estado de la automatización «se crea la rama» ({@code null} = sí)
     */
    public record BranchRequest(UUID repositoryId, String base, String name, Boolean move) {
    }

    public record LinkBranchRequest(UUID repositoryId, String name) {
    }

    @Operation(summary = "GitHub en el proyecto", description = "Sus repositorios, los que se pueden enlazar, las automatizaciones y las reglas.")
    @GetMapping
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public ProjectStatus status(@PathVariable String slug, @PathVariable UUID projectId) {
        // Los demás repositorios del workspace y los proyectos que los comparten, solo para quien configura GitHub
        return githubService.project(slug, projectId, workspaceSecurity.can(slug, "GITHUB_MANAGE"));
    }

    @Operation(summary = "Elegir los repositorios del proyecto")
    @PutMapping("/repositories")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_MANAGE')")
    public ProjectStatus setRepositories(@PathVariable String slug, @PathVariable UUID projectId, @RequestBody RepositoriesRequest request) {
        return githubService.setRepositories(slug, projectId, request.repositoryIds());
    }

    @Operation(summary = "Guardar las automatizaciones", description = "A qué estado pasa la tarea con su rama y su pull request.")
    @PutMapping("/settings")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_MANAGE')")
    public ProjectStatus saveSettings(@PathVariable String slug, @PathVariable UUID projectId, @RequestBody Settings settings) {
        return githubService.saveSettings(slug, projectId, settings);
    }

    @Operation(summary = "Agregar una regla de Actions", description = "Cuando pase eso en una tarea, se ejecuta el workflow.")
    @PostMapping("/rules")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectStatus addRule(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                                 @RequestBody RuleRequest request) {
        return githubService.addRule(slug, projectId, me.getId(), request.firesOn(), request.repositoryId(), request.workflowId(),
                request.workflowName(), request.ref());
    }

    @Operation(summary = "Quitar una regla de Actions")
    @DeleteMapping("/rules/{ruleId}")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID ruleId) {
        githubService.deleteRule(slug, projectId, ruleId);
    }

    @Operation(summary = "Pull requests del proyecto", description = "state: open (abiertos y borradores), merged o all.")
    @GetMapping("/pulls")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<PullRequest> pullRequests(@PathVariable String slug, @PathVariable UUID projectId,
                                          @RequestParam(defaultValue = "open") String state) {
        return githubService.pullRequests(slug, projectId, state);
    }

    @Operation(summary = "Enlazar un pull request con una tarea", description = "Sin tarea, lo deja suelto.")
    @PutMapping("/pulls/{pullRequestId}/issue")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'ISSUE_EDIT')")
    public PullRequest linkPullRequest(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                                       @PathVariable UUID pullRequestId, @RequestBody LinkRequest request) {
        return githubService.linkPullRequest(slug, projectId, me.getId(), pullRequestId, request.issueId());
    }

    @Operation(summary = "Ejecuciones de GitHub Actions del proyecto")
    @GetMapping("/runs")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<Run> runs(@PathVariable String slug, @PathVariable UUID projectId) {
        return githubService.runs(slug, projectId);
    }

    @Operation(summary = "Ejecutar un workflow", description = "Queda como pedida hasta que GitHub avisa de la ejecución.")
    @PostMapping("/runs")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_RUN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Run dispatch(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                        @RequestBody DispatchRequest request) {
        return githubService.dispatch(slug, projectId, me.getId(), request.repositoryId(), request.workflowId(), request.workflowName(),
                request.ref(), request.inputs() == null ? Map.of() : request.inputs(), request.issueId());
    }

    @Operation(summary = "Repetir una ejecución")
    @PostMapping("/runs/{runId}/rerun")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_RUN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rerun(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                      @PathVariable UUID runId) {
        githubService.rerun(slug, projectId, me.getId(), runId);
    }

    @Operation(summary = "Cancelar una ejecución")
    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'GITHUB_RUN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                       @PathVariable UUID runId) {
        githubService.cancel(slug, projectId, me.getId(), runId);
    }

    @Operation(summary = "Workflows que se pueden ejecutar a mano", description = "Con los datos que pide cada uno.")
    @GetMapping("/repositories/{repositoryId}/workflows")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<Workflow> workflows(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return githubService.workflows(slug, projectId, repositoryId);
    }

    @Operation(summary = "Ramas de un repositorio")
    @GetMapping("/repositories/{repositoryId}/branches")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<String> branches(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return githubService.branches(slug, projectId, repositoryId);
    }

    @Operation(summary = "Tareas con rama o pull request", description = "Para el indicador del tablero y la lista.")
    @GetMapping("/summary")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<IssueSummary> summary(@PathVariable String slug, @PathVariable UUID projectId) {
        return githubService.summary(slug, projectId);
    }

    @Operation(summary = "El desarrollo de una tarea", description = "Sus ramas, pull requests y ejecuciones.")
    @GetMapping("/issues/{issueId}")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public IssueDev issue(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId) {
        return githubService.issue(slug, projectId, issueId);
    }

    @Operation(summary = "Checks de un pull request de la tarea", description = "Como están ahora en GitHub.")
    @GetMapping("/issues/{issueId}/pulls/{pullRequestId}/checks")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<Check> checks(@PathVariable String slug, @PathVariable UUID projectId, @PathVariable UUID issueId,
                              @PathVariable UUID pullRequestId) {
        return githubService.checks(slug, projectId, issueId, pullRequestId);
    }

    @Operation(summary = "Crear la rama de una tarea", description = "La crea en GitHub a partir de otra y la deja enlazada.")
    @PostMapping("/issues/{issueId}/branches")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'ISSUE_EDIT')")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueDev createBranch(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                                 @PathVariable UUID issueId, @RequestBody BranchRequest request) {
        return githubService.createBranch(slug, projectId, me.getId(), issueId, request.repositoryId(), request.base(), request.name(),
                !Boolean.FALSE.equals(request.move()));
    }

    @Operation(summary = "Enlazar una rama que ya existe con la tarea")
    @PostMapping("/issues/{issueId}/branches/link")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'ISSUE_EDIT')")
    public IssueDev linkBranch(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                               @PathVariable UUID issueId, @RequestBody LinkBranchRequest request) {
        return githubService.linkBranch(slug, projectId, me.getId(), issueId, request.repositoryId(), request.name());
    }

    @Operation(summary = "Quitar una rama de la tarea", description = "La rama sigue en GitHub.")
    @DeleteMapping("/issues/{issueId}/branches/{branchId}")
    @PreAuthorize("@workspaceSecurity.canInProject(#slug, #projectId, 'ISSUE_EDIT')")
    public IssueDev unlinkBranch(@PathVariable String slug, @PathVariable UUID projectId, @AuthenticationPrincipal UserPrincipal me,
                                 @PathVariable UUID issueId, @PathVariable UUID branchId) {
        return githubService.unlinkBranch(slug, projectId, me.getId(), issueId, branchId);
    }

    @Operation(summary = "Ramas enlazadas a tareas del proyecto")
    @GetMapping("/branches")
    @PreAuthorize("@workspaceSecurity.canAccessProject(#slug, #projectId)")
    public List<Branch> linkedBranches(@PathVariable String slug, @PathVariable UUID projectId) {
        return githubService.linkedBranches(slug, projectId);
    }
}
