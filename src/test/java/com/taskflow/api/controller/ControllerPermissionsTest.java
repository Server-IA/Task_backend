package com.taskflow.api.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Comprueba la regla de {@code @PreAuthorize} que protege cada endpoint (la del método o, si no tiene, la de la
 * clase): lo que configura el workspace o un proyecto es solo del project manager; el trabajo con las tareas,
 * de cualquier miembro del proyecto.
 * <p>
 * Recorre por reflexión todos los métodos de todos los {@code @RestController}: cada endpoint debe estar en
 * {@link #RULES} (con su regla) o, explícitamente, en {@link #AUTHENTICATED_ONLY} (basta con iniciar sesión) o en
 * {@link #PUBLIC} (sin sesión). Un endpoint nuevo sin clasificar, o con una regla que no es la esperada (por ejemplo
 * la de la clase por olvido), hace fallar la prueba.
 */
class ControllerPermissionsTest {

    private static final String MEMBER = "@workspaceSecurity.isMember(#slug)";
    private static final String MANAGER = "@workspaceSecurity.isManager(#slug)";
    private static final String ACCESS = "@workspaceSecurity.canAccessProject(#slug, #projectId)";
    private static final String MANAGE = "@workspaceSecurity.canManageProject(#slug, #projectId)";

    /** Endpoint («Controlador.método») → su regla. */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    /**
     * Solo exigen estar autenticado: no dependen de un workspace (o todavía no hay uno), y el servicio trabaja siempre
     * con el usuario de la sesión.
     */
    private static final Set<String> AUTHENTICATED_ONLY = Set.of(
            // Mis workspaces y crear uno (quien lo crea pasa a ser su project manager)
            "WorkspaceController.getMyWorkspaces",
            "WorkspaceController.create",
            // Aceptar una invitación: el servicio exige que sea para el correo de la sesión
            "InvitationController.accept",
            // Mi perfil y mi contraseña
            "UserController.getProfile",
            "UserController.updateProfile",
            "UserController.changePassword",
            // Mis notificaciones (lista, contadores, marcar como leídas)
            "NotificationController.getMyNotifications",
            "NotificationController.countUnread",
            "NotificationController.counts",
            "NotificationController.markAsRead",
            "NotificationController.markAllAsRead");

    /** Sin sesión: la autenticación y ver una invitación con su token. */
    private static final Set<String> PUBLIC = Set.of(
            "AuthController.register",
            "AuthController.verifyEmail",
            "AuthController.resendVerification",
            "AuthController.login",
            "AuthController.refresh",
            "AuthController.logout",
            "AuthController.forgotPassword",
            "AuthController.resetPassword",
            "InvitationController.getDetails");

    /** Rutas públicas de {@code SecurityConfig} (permitAll). */
    private static final List<String> PUBLIC_PATTERNS = List.of("/api/v1/auth/**", "GET /api/v1/invitations/*");

    private static void rule(Class<?> controller, String method, String expected) {
        RULES.put(controller.getSimpleName() + "." + method, expected);
    }

    static {
        // Workspace e invitaciones
        rule(WorkspaceController.class, "get", MEMBER);
        rule(WorkspaceController.class, "update", MANAGER);
        rule(WorkspaceController.class, "delete", MANAGER);
        rule(WorkspaceController.class, "updateMemberRole", MANAGER);
        rule(WorkspaceController.class, "removeMember", MEMBER);
        rule(InvitationController.class, "invite", MANAGER);
        rule(InvitationController.class, "getPending", MANAGER);
        rule(InvitationController.class, "revoke", MANAGER);
        // Proyectos
        rule(ProjectController.class, "getProjects", MEMBER);
        rule(ProjectController.class, "create", MANAGER);
        rule(ProjectController.class, "get", ACCESS);
        rule(ProjectController.class, "update", MANAGE);
        rule(ProjectController.class, "delete", MANAGE);
        rule(ProjectController.class, "getMembers", ACCESS);
        rule(ProjectController.class, "addMember", MANAGE);
        rule(ProjectController.class, "removeMember", MANAGE);
        // Estados y etiquetas
        rule(StateController.class, "getStates", ACCESS);
        rule(StateController.class, "create", MANAGE);
        rule(StateController.class, "update", MANAGE);
        rule(StateController.class, "delete", MANAGE);
        rule(LabelController.class, "getLabels", ACCESS);
        rule(LabelController.class, "create", MANAGE);
        rule(LabelController.class, "update", MANAGE);
        rule(LabelController.class, "delete", MANAGE);
        // Ciclos y módulos: verlos y mover tareas dentro o fuera, cualquier miembro del proyecto
        rule(CycleController.class, "getCycles", ACCESS);
        rule(CycleController.class, "get", ACCESS);
        rule(CycleController.class, "create", MANAGE);
        rule(CycleController.class, "update", MANAGE);
        rule(CycleController.class, "delete", MANAGE);
        rule(CycleController.class, "addIssues", ACCESS);
        rule(CycleController.class, "removeIssue", ACCESS);
        // Cerrar un ciclo es del project manager
        rule(CycleController.class, "closePreview", MANAGE);
        rule(CycleController.class, "close", MANAGE);
        rule(ModuleController.class, "getModules", ACCESS);
        rule(ModuleController.class, "get", ACCESS);
        rule(ModuleController.class, "create", MANAGE);
        rule(ModuleController.class, "update", MANAGE);
        rule(ModuleController.class, "delete", MANAGE);
        rule(ModuleController.class, "addIssues", ACCESS);
        rule(ModuleController.class, "removeIssue", ACCESS);
        // Tareas y comentarios (borrar una tarea se valida además en el servicio)
        rule(IssueController.class, "create", ACCESS);
        rule(IssueController.class, "update", ACCESS);
        rule(IssueController.class, "move", ACCESS);
        rule(IssueController.class, "delete", ACCESS);
        rule(CommentController.class, "create", ACCESS);
        rule(CommentController.class, "update", ACCESS);
        rule(CommentController.class, "delete", ACCESS);
        // Dependencias y checklist: cualquier miembro del proyecto
        rule(IssueRelationController.class, "getRelations", ACCESS);
        rule(IssueRelationController.class, "create", ACCESS);
        rule(IssueRelationController.class, "delete", ACCESS);
        rule(IssueChecklistController.class, "getItems", ACCESS);
        rule(IssueChecklistController.class, "create", ACCESS);
        rule(IssueChecklistController.class, "update", ACCESS);
        rule(IssueChecklistController.class, "delete", ACCESS);
        // Plantillas: las ve todo el proyecto, las configura el project manager
        rule(IssueTemplateController.class, "getTemplates", ACCESS);
        rule(IssueTemplateController.class, "create", MANAGE);
        rule(IssueTemplateController.class, "update", MANAGE);
        rule(IssueTemplateController.class, "delete", MANAGE);
        // Vistas guardadas: el servicio decide quién gestiona cada una (dueño o PM si es compartida)
        rule(SavedViewController.class, "getViews", ACCESS);
        rule(SavedViewController.class, "create", ACCESS);
        rule(SavedViewController.class, "update", ACCESS);
        rule(SavedViewController.class, "delete", ACCESS);
        // Ordenar: el servicio exige project manager para las compartidas; las personales, las del usuario
        rule(SavedViewController.class, "reorder", ACCESS);
        // Retrospectiva: notas y votos de cualquier miembro del proyecto (editar o borrar una nota ajena se
        // valida en el servicio); abrirla, terminarla y convertir acciones en tareas, del project manager
        rule(RetroController.class, "getRetro", ACCESS);
        rule(RetroController.class, "start", MANAGE);
        rule(RetroController.class, "createNote", ACCESS);
        rule(RetroController.class, "updateNote", ACCESS);
        rule(RetroController.class, "deleteNote", ACCESS);
        rule(RetroController.class, "vote", ACCESS);
        rule(RetroController.class, "unvote", ACCESS);
        rule(RetroController.class, "convert", MANAGE);
        rule(RetroController.class, "finish", MANAGE);
        // Daily: la ve y escribe su nota cualquier miembro del proyecto
        rule(DailyController.class, "getDaily", ACCESS);
        rule(DailyController.class, "saveNote", ACCESS);
        // Endpoints nuevos
        rule(ProjectStatsController.class, "getStats", ACCESS);
        rule(MyWorkController.class, "getMyWork", MEMBER);
        rule(WorkspaceActivityController.class, "getFeed", MEMBER);
        rule(WorkspaceActivityController.class, "getSummary", MEMBER);
        rule(SearchController.class, "search", MEMBER);
        // Historias: las ve cualquier miembro del proyecto; partirlas, cerrarlas y reabrirlas es del project
        // manager (cerrar o reabrir por otros caminos, como el tablero, se valida en el servicio)
        rule(StoryController.class, "getStories", ACCESS);
        rule(StoryController.class, "split", MANAGE);
        rule(StoryController.class, "close", MANAGE);
        rule(StoryController.class, "reopen", MANAGE);
        // Lecturas de tareas, comentarios y miembros (la regla de la clase)
        rule(IssueController.class, "getIssues", ACCESS);
        rule(IssueController.class, "get", ACCESS);
        rule(IssueController.class, "getSubIssues", ACCESS);
        rule(IssueController.class, "getActivity", ACCESS);
        rule(CommentController.class, "getComments", ACCESS);
        rule(SearchController.class, "byCodes", MEMBER);
        rule(WorkspaceController.class, "getMembers", MEMBER);
    }

    /** Un endpoint: su controlador y su método. */
    private record Endpoint(Class<?> controller, Method method) {

        String key() {
            return controller.getSimpleName() + "." + method.getName();
        }

        PreAuthorize rule() {
            return method.isAnnotationPresent(PreAuthorize.class)
                    ? method.getAnnotation(PreAuthorize.class)
                    : controller.getAnnotation(PreAuthorize.class);
        }

        boolean takesSlug() {
            return Arrays.stream(method.getParameters()).anyMatch(Endpoint::isSlug);
        }

        private static boolean isSlug(Parameter parameter) {
            PathVariable variable = parameter.getAnnotation(PathVariable.class);
            if (variable == null) {
                return false;
            }
            String name = !variable.value().isEmpty() ? variable.value() : variable.name();
            return "slug".equals(name.isEmpty() ? parameter.getName() : name);
        }

        /** «GET /api/v1/…» para cada método HTTP y ruta del endpoint. */
        List<String> routes() {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            RequestMapping own = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            String[] paths = own.path().length == 0 ? new String[] {""} : own.path();
            List<String> routes = new ArrayList<>();
            for (RequestMethod verb : own.method()) {
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        routes.add(verb.name() + " " + prefix + path);
                    }
                }
            }
            return routes;
        }
    }

    /** Todos los métodos con {@code @RequestMapping} (o {@code @GetMapping}…) de todos los {@code @RestController}. */
    private static List<Endpoint> endpoints() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.taskflow.api")) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    endpoints.add(new Endpoint(controller, method));
                }
            }
        }
        return endpoints;
    }

    private static Endpoint endpoint(String key) {
        return endpoints().stream().filter(endpoint -> endpoint.key().equals(key)).findFirst().orElseThrow();
    }

    static Stream<Arguments> rules() {
        return RULES.entrySet().stream().map(entry -> arguments(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void cadaEndpointTieneSuRegla(String key, String expected) {
        PreAuthorize rule = endpoint(key).rule();

        assertThat(rule).as("@PreAuthorize de %s", key).isNotNull();
        assertThat(rule.value()).as("@PreAuthorize de %s", key).isEqualTo(expected);
    }

    @Test
    void todosLosEndpointsEstanClasificados() {
        List<Endpoint> endpoints = endpoints();
        assertThat(endpoints).as("controladores encontrados").hasSizeGreaterThan(100);

        Set<String> keys = new HashSet<>();
        Set<String> overloaded = new TreeSet<>();
        for (Endpoint endpoint : endpoints) {
            if (!keys.add(endpoint.key())) {
                overloaded.add(endpoint.key());
            }
        }
        // Con dos endpoints del mismo nombre, la clave «Controlador.método» sería ambigua
        assertThat(overloaded).as("endpoints con el mismo nombre en un controlador").isEmpty();

        Set<String> unclassified = new TreeSet<>(keys);
        unclassified.removeAll(RULES.keySet());
        unclassified.removeAll(AUTHENTICATED_ONLY);
        unclassified.removeAll(PUBLIC);
        assertThat(unclassified).as("endpoints sin regla esperada (agrégalos a RULES, AUTHENTICATED_ONLY o PUBLIC)")
                .isEmpty();

        Set<String> classified = new TreeSet<>(RULES.keySet());
        classified.addAll(AUTHENTICATED_ONLY);
        classified.addAll(PUBLIC);
        classified.removeAll(keys);
        assertThat(classified).as("entradas que ya no son endpoints").isEmpty();

        Set<String> twice = RULES.keySet().stream()
                .filter(key -> AUTHENTICATED_ONLY.contains(key) || PUBLIC.contains(key))
                .collect(Collectors.toCollection(TreeSet::new));
        twice.addAll(AUTHENTICATED_ONLY.stream().filter(PUBLIC::contains).toList());
        assertThat(twice).as("endpoints en más de una lista").isEmpty();
    }

    @Test
    void todoEndpointDeUnWorkspaceTieneRegla() {
        List<String> withoutRule = endpoints().stream()
                .filter(Endpoint::takesSlug)
                .filter(endpoint -> !RULES.containsKey(endpoint.key()) || endpoint.rule() == null)
                .map(Endpoint::key)
                .sorted()
                .toList();

        assertThat(withoutRule).as("endpoints con {slug} sin @PreAuthorize").isEmpty();
    }

    @Test
    void losEndpointsSinReglaNoTienenPreAuthorizeNiWorkspace() {
        Set<String> open = new HashSet<>(AUTHENTICATED_ONLY);
        open.addAll(PUBLIC);
        for (Endpoint endpoint : endpoints()) {
            if (open.contains(endpoint.key())) {
                assertThat(endpoint.rule()).as("@PreAuthorize de %s", endpoint.key()).isNull();
                assertThat(endpoint.takesSlug()).as("%s recibe {slug}", endpoint.key()).isFalse();
            }
        }
    }

    @Test
    void soloLosPublicosQuedanFueraDeLaSesion() {
        AntPathMatcher matcher = new AntPathMatcher();
        for (Endpoint endpoint : endpoints()) {
            boolean permitted = endpoint.routes().stream().allMatch(route -> PUBLIC_PATTERNS.stream()
                    .anyMatch(pattern -> pattern.contains(" ")
                            ? route.startsWith(pattern.substring(0, pattern.indexOf(' ') + 1))
                                    && matcher.match(pattern.substring(pattern.indexOf(' ') + 1),
                                            route.substring(route.indexOf(' ') + 1))
                            : matcher.match(pattern, route.substring(route.indexOf(' ') + 1))));
            assertThat(permitted).as("%s %s es público en SecurityConfig", endpoint.key(), endpoint.routes())
                    .isEqualTo(PUBLIC.contains(endpoint.key()));
        }
    }
}
