package com.taskflow.api.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Comprueba la regla de {@code @PreAuthorize} que protege cada endpoint (la del método o, si no tiene, la de la
 * clase): lo que configura el workspace o un proyecto es solo del project manager; el trabajo con las tareas,
 * de cualquier miembro del proyecto.
 */
class ControllerPermissionsTest {

    private static final String MEMBER = "@workspaceSecurity.isMember(#slug)";
    private static final String MANAGER = "@workspaceSecurity.isManager(#slug)";
    private static final String ACCESS = "@workspaceSecurity.canAccessProject(#slug, #projectId)";
    private static final String MANAGE = "@workspaceSecurity.canManageProject(#slug, #projectId)";

    static Stream<Arguments> rules() {
        return Stream.of(
                // Workspace e invitaciones
                arguments(WorkspaceController.class, "get", MEMBER),
                arguments(WorkspaceController.class, "update", MANAGER),
                arguments(WorkspaceController.class, "delete", MANAGER),
                arguments(WorkspaceController.class, "updateMemberRole", MANAGER),
                arguments(WorkspaceController.class, "removeMember", MEMBER),
                arguments(InvitationController.class, "invite", MANAGER),
                arguments(InvitationController.class, "getPending", MANAGER),
                arguments(InvitationController.class, "revoke", MANAGER),
                // Proyectos
                arguments(ProjectController.class, "getProjects", MEMBER),
                arguments(ProjectController.class, "create", MANAGER),
                arguments(ProjectController.class, "get", ACCESS),
                arguments(ProjectController.class, "update", MANAGE),
                arguments(ProjectController.class, "delete", MANAGE),
                arguments(ProjectController.class, "getMembers", ACCESS),
                arguments(ProjectController.class, "addMember", MANAGE),
                arguments(ProjectController.class, "removeMember", MANAGE),
                // Estados y etiquetas
                arguments(StateController.class, "getStates", ACCESS),
                arguments(StateController.class, "create", MANAGE),
                arguments(StateController.class, "update", MANAGE),
                arguments(StateController.class, "delete", MANAGE),
                arguments(LabelController.class, "getLabels", ACCESS),
                arguments(LabelController.class, "create", MANAGE),
                arguments(LabelController.class, "update", MANAGE),
                arguments(LabelController.class, "delete", MANAGE),
                // Ciclos y módulos: verlos y mover tareas dentro o fuera, cualquier miembro del proyecto
                arguments(CycleController.class, "getCycles", ACCESS),
                arguments(CycleController.class, "get", ACCESS),
                arguments(CycleController.class, "create", MANAGE),
                arguments(CycleController.class, "update", MANAGE),
                arguments(CycleController.class, "delete", MANAGE),
                arguments(CycleController.class, "addIssues", ACCESS),
                arguments(CycleController.class, "removeIssue", ACCESS),
                arguments(ModuleController.class, "getModules", ACCESS),
                arguments(ModuleController.class, "get", ACCESS),
                arguments(ModuleController.class, "create", MANAGE),
                arguments(ModuleController.class, "update", MANAGE),
                arguments(ModuleController.class, "delete", MANAGE),
                arguments(ModuleController.class, "addIssues", ACCESS),
                arguments(ModuleController.class, "removeIssue", ACCESS),
                // Tareas y comentarios (borrar una tarea se valida además en el servicio)
                arguments(IssueController.class, "create", ACCESS),
                arguments(IssueController.class, "update", ACCESS),
                arguments(IssueController.class, "move", ACCESS),
                arguments(IssueController.class, "delete", ACCESS),
                arguments(CommentController.class, "create", ACCESS),
                arguments(CommentController.class, "delete", ACCESS),
                // Endpoints nuevos
                arguments(ProjectStatsController.class, "getStats", ACCESS),
                arguments(MyWorkController.class, "getMyWork", MEMBER),
                arguments(WorkspaceActivityController.class, "getFeed", MEMBER),
                arguments(WorkspaceActivityController.class, "getSummary", MEMBER),
                arguments(SearchController.class, "search", MEMBER));
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("rules")
    void cadaEndpointTieneSuRegla(Class<?> controller, String methodName, String expected) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        PreAuthorize rule = method.isAnnotationPresent(PreAuthorize.class)
                ? method.getAnnotation(PreAuthorize.class)
                : controller.getAnnotation(PreAuthorize.class);

        assertThat(rule).as("@PreAuthorize de %s.%s", controller.getSimpleName(), methodName).isNotNull();
        assertThat(rule.value()).isEqualTo(expected);
    }
}
