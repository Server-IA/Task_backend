package com.taskflow.api.controller;

import com.taskflow.api.entity.enums.Permission;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** Un permiso del workspace (invitar, gestionar roles, crear proyectos…). */
    private static String can(Permission permission) {
        return "@workspaceSecurity.can(#slug, '" + permission.name() + "')";
    }

    /** Un permiso dentro de un proyecto que además se puede ver. */
    private static String inProject(Permission permission) {
        return "@workspaceSecurity.canInProject(#slug, #projectId, '" + permission.name() + "')";
    }

    /** Endpoint («Controlador.método») → su regla. */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    /**
     * Solo exigen estar autenticado: no dependen de un workspace (o todavía no hay uno), y el servicio trabaja siempre
     * con el usuario de la sesión.
     */
    private static final Set<String> AUTHENTICATED_ONLY = Set.of(
            // Catálogo de permisos (es el mismo para todos los workspaces)
            "RoleController.catalog",
            // Mis workspaces y crear uno (quien lo crea pasa a ser su project manager)
            "WorkspaceController.getMyWorkspaces",
            "WorkspaceController.create",
            // Aceptar una invitación: el servicio exige que sea para el correo de la sesión
            "InvitationController.accept",
            // Mi perfil y mi contraseña
            "UserController.getProfile",
            "UserController.updateProfile",
            "UserController.updateLanguage",
            "UserController.changePassword",
            // Mi foto de perfil (subirla, confirmarla y quitarla)
            "UserController.startAvatarUpload",
            "UserController.confirmAvatar",
            "UserController.removeAvatar",
            // Mi cuenta de GitHub (enlazarla, verla y desenlazarla): el servicio trabaja con el usuario de la sesión
            "GithubAccountController.status",
            "GithubAccountController.link",
            "GithubAccountController.complete",
            "GithubAccountController.unlink",
            // URLs firmadas de archivos: el servicio solo firma los de proyectos accesibles y las fotos de quien
            // comparte un workspace (un id ajeno, aunque se adivine, no viene)
            "FileController.resolveUrls",
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
            "AuthController.validateResetToken",
            "AuthController.resetPassword",
            "InvitationController.getDetails",
            // Lo que llama GitHub: avisos firmados con el secreto de la app y la vuelta de instalarla (enlace firmado)
            "GithubWebhookController.webhook",
            "GithubWebhookController.setup",
            // /error: el formato común de los errores que no pasan por un controlador (público en SecurityConfig)
            "ApiErrorController.error");

    /** Rutas públicas de {@code SecurityConfig} (permitAll). */
    private static final List<String> PUBLIC_PATTERNS = List.of("/api/v1/auth/**", "GET /api/v1/invitations/*",
            "/api/v1/github/webhook", "/api/v1/github/setup");

    private static void rule(Class<?> controller, String method, String expected) {
        RULES.put(controller.getSimpleName() + "." + method, expected);
    }

    static {
        // Workspace: editarlo, cambiar roles e invitar van por permiso; eliminarlo, solo el project manager. Salir del
        // workspace es de cualquiera (quitar a otra persona lo valida el servicio)
        rule(WorkspaceController.class, "get", MEMBER);
        rule(WorkspaceController.class, "update", can(Permission.WORKSPACE_EDIT));
        rule(WorkspaceController.class, "delete", MANAGER);
        rule(WorkspaceController.class, "getMembers", MEMBER);
        rule(WorkspaceController.class, "updateMemberRole", can(Permission.MEMBER_ROLE));
        rule(WorkspaceController.class, "removeMember", MEMBER);
        rule(InvitationController.class, "invite", can(Permission.MEMBER_INVITE));
        rule(InvitationController.class, "getPending", can(Permission.MEMBER_INVITE));
        rule(InvitationController.class, "revoke", can(Permission.MEMBER_INVITE));
        // Roles: los ve cualquier miembro (para saber qué puede hacer cada uno); crearlos y editarlos, por permiso
        rule(RoleController.class, "getRoles", MEMBER);
        rule(RoleController.class, "create", can(Permission.ROLE_MANAGE));
        rule(RoleController.class, "update", can(Permission.ROLE_MANAGE));
        rule(RoleController.class, "updateMemberPermissions", can(Permission.ROLE_MANAGE));
        rule(RoleController.class, "delete", can(Permission.ROLE_MANAGE));
        // Proyectos
        rule(ProjectController.class, "getProjects", MEMBER);
        rule(ProjectController.class, "create", can(Permission.PROJECT_CREATE));
        rule(ProjectController.class, "get", ACCESS);
        rule(ProjectController.class, "update", inProject(Permission.PROJECT_EDIT));
        rule(ProjectController.class, "delete", inProject(Permission.PROJECT_DELETE));
        rule(ProjectController.class, "getMembers", ACCESS);
        rule(ProjectController.class, "addMember", inProject(Permission.PROJECT_MEMBERS));
        rule(ProjectController.class, "removeMember", inProject(Permission.PROJECT_MEMBERS));
        // Estados, etiquetas y plantillas: los ve todo el proyecto
        rule(StateController.class, "getStates", ACCESS);
        rule(StateController.class, "create", inProject(Permission.PROJECT_STATES));
        rule(StateController.class, "update", inProject(Permission.PROJECT_STATES));
        rule(StateController.class, "reorder", inProject(Permission.PROJECT_STATES));
        rule(StateController.class, "delete", inProject(Permission.PROJECT_STATES));
        rule(LabelController.class, "getLabels", ACCESS);
        rule(LabelController.class, "create", inProject(Permission.PROJECT_LABELS));
        rule(LabelController.class, "update", inProject(Permission.PROJECT_LABELS));
        rule(LabelController.class, "delete", inProject(Permission.PROJECT_LABELS));
        rule(IssueTemplateController.class, "getTemplates", ACCESS);
        rule(IssueTemplateController.class, "create", inProject(Permission.PROJECT_TEMPLATES));
        rule(IssueTemplateController.class, "update", inProject(Permission.PROJECT_TEMPLATES));
        rule(IssueTemplateController.class, "delete", inProject(Permission.PROJECT_TEMPLATES));
        // Ciclos y módulos: verlos, cualquiera del proyecto
        rule(CycleController.class, "getCycles", ACCESS);
        rule(CycleController.class, "get", ACCESS);
        rule(CycleController.class, "create", inProject(Permission.CYCLE_CREATE));
        rule(CycleController.class, "update", inProject(Permission.CYCLE_EDIT));
        rule(CycleController.class, "delete", inProject(Permission.CYCLE_DELETE));
        rule(CycleController.class, "addIssues", inProject(Permission.CYCLE_ASSIGN));
        rule(CycleController.class, "removeIssue", inProject(Permission.CYCLE_ASSIGN));
        rule(CycleController.class, "closePreview", inProject(Permission.CYCLE_CLOSE));
        rule(CycleController.class, "close", inProject(Permission.CYCLE_CLOSE));
        rule(ModuleController.class, "getModules", ACCESS);
        rule(ModuleController.class, "get", ACCESS);
        rule(ModuleController.class, "create", inProject(Permission.MODULE_CREATE));
        rule(ModuleController.class, "update", inProject(Permission.MODULE_EDIT));
        rule(ModuleController.class, "delete", inProject(Permission.MODULE_DELETE));
        rule(ModuleController.class, "addIssues", inProject(Permission.MODULE_ASSIGN));
        rule(ModuleController.class, "removeIssue", inProject(Permission.MODULE_ASSIGN));
        // Tareas: crear y eliminar los valida el servicio (una historia pide otro permiso que una tarea; borrar la propia,
        // otro que borrar la de alguien más)
        rule(IssueController.class, "getIssues", ACCESS);
        rule(IssueController.class, "get", ACCESS);
        rule(IssueController.class, "getSubIssues", ACCESS);
        rule(IssueController.class, "getActivity", ACCESS);
        rule(IssueController.class, "create", ACCESS);
        rule(IssueController.class, "update", inProject(Permission.ISSUE_EDIT));
        rule(IssueController.class, "move", inProject(Permission.ISSUE_EDIT));
        rule(IssueController.class, "delete", ACCESS);
        // Comentarios: editar el propio y borrar los valida el servicio (el autor, o quien puede borrar los de otros)
        rule(CommentController.class, "getComments", ACCESS);
        rule(CommentController.class, "create", inProject(Permission.COMMENT_CREATE));
        rule(CommentController.class, "update", ACCESS);
        rule(CommentController.class, "delete", ACCESS);
        // Dependencias y checklist
        rule(IssueRelationController.class, "getRelations", ACCESS);
        rule(IssueRelationController.class, "create", inProject(Permission.ISSUE_RELATIONS));
        rule(IssueRelationController.class, "delete", inProject(Permission.ISSUE_RELATIONS));
        rule(IssueChecklistController.class, "getItems", ACCESS);
        rule(IssueChecklistController.class, "create", inProject(Permission.ISSUE_CHECKLIST));
        rule(IssueChecklistController.class, "update", inProject(Permission.ISSUE_CHECKLIST));
        rule(IssueChecklistController.class, "delete", inProject(Permission.ISSUE_CHECKLIST));
        // Vistas guardadas: el servicio decide quién gestiona cada una (su dueño, o el permiso de vistas compartidas)
        rule(SavedViewController.class, "getViews", ACCESS);
        rule(SavedViewController.class, "create", ACCESS);
        rule(SavedViewController.class, "update", ACCESS);
        rule(SavedViewController.class, "reorder", ACCESS);
        rule(SavedViewController.class, "delete", ACCESS);
        // Retrospectiva: dirigirla y participar van por permiso; editar o borrar una nota lo valida el servicio (su autor,
        // o quien dirige la retro)
        rule(RetroController.class, "getRetro", ACCESS);
        rule(RetroController.class, "start", inProject(Permission.RETRO_MANAGE));
        rule(RetroController.class, "createNote", inProject(Permission.RETRO_PARTICIPATE));
        rule(RetroController.class, "updateNote", ACCESS);
        rule(RetroController.class, "deleteNote", ACCESS);
        rule(RetroController.class, "vote", inProject(Permission.RETRO_PARTICIPATE));
        rule(RetroController.class, "unvote", inProject(Permission.RETRO_PARTICIPATE));
        rule(RetroController.class, "convert", inProject(Permission.RETRO_MANAGE));
        rule(RetroController.class, "finish", inProject(Permission.RETRO_MANAGE));
        // Daily: la ve cualquiera del proyecto
        rule(DailyController.class, "getDaily", ACCESS);
        rule(DailyController.class, "saveNote", inProject(Permission.DAILY_NOTE));
        rule(DailyController.class, "getSettings", ACCESS);
        rule(DailyController.class, "saveSettings", inProject(Permission.DAILY_SETTINGS));
        // Estadísticas, reportes y actividad
        rule(ProjectStatsController.class, "getStats", inProject(Permission.STATS_VIEW));
        rule(ProjectStatsController.class, "getDevelopmentStats", inProject(Permission.STATS_VIEW));
        rule(ReportController.class, "previewIssues", inProject(Permission.REPORT_GENERATE));
        rule(ReportController.class, "issuesPdf", inProject(Permission.REPORT_GENERATE));
        rule(WorkspaceActivityController.class, "getFeed", can(Permission.ACTIVITY_VIEW));
        rule(WorkspaceActivityController.class, "getSummary", can(Permission.ACTIVITY_VIEW));
        // Mi trabajo y la búsqueda: cualquier miembro (solo traen lo de los proyectos que ve)
        rule(MyWorkController.class, "getMyWork", MEMBER);
        rule(SearchController.class, "search", MEMBER);
        rule(SearchController.class, "byCodes", MEMBER);
        // Historias: las ve cualquiera del proyecto (cerrar o reabrir por otros caminos, como el tablero, se valida en el
        // servicio)
        rule(StoryController.class, "getStories", ACCESS);
        rule(StoryController.class, "split", inProject(Permission.STORY_MANAGE));
        rule(StoryController.class, "close", inProject(Permission.STORY_CLOSE));
        rule(StoryController.class, "reopen", inProject(Permission.STORY_CLOSE));
        // Archivos: el servicio valida subir (adjuntos o imágenes) y borrar (quien lo subió, o quien puede borrar los de
        // otros). El detalle del almacenamiento va por permiso; el uso lo ve cualquiera
        rule(ProjectFileController.class, "createUpload", ACCESS);
        rule(ProjectFileController.class, "confirmUpload", ACCESS);
        rule(ProjectFileController.class, "deleteFile", ACCESS);
        rule(ProjectFileController.class, "getAttachments", ACCESS);
        rule(StorageController.class, "getReport", can(Permission.STORAGE_VIEW));
        rule(StorageController.class, "getUsage", MEMBER);

        for (String method : List.of("list", "get", "connect", "upload", "select", "saveMapping", "start", "stop", "undo", "discard",
                "report")) {
            rule(ImportController.class, method, can(Permission.DATA_IMPORT));
        }

        // GitHub: ver es de quien ve el proyecto (el estado de la conexión, de cualquier miembro); conectar y configurar,
        // y ejecutar workflows, van por permiso; crear la rama de una tarea o enlazarle un pull request es editarla
        rule(GithubController.class, "status", MEMBER);
        for (String method : List.of("install", "sync", "disconnect")) {
            rule(GithubController.class, method, can(Permission.GITHUB_MANAGE));
        }
        for (String method : List.of("status", "pullRequests", "runs", "workflows", "branches", "linkedBranches", "summary", "issue", "checks")) {
            rule(ProjectGithubController.class, method, ACCESS);
        }
        for (String method : List.of("setRepositories", "saveSettings", "addRule", "deleteRule")) {
            rule(ProjectGithubController.class, method, inProject(Permission.GITHUB_MANAGE));
        }
        for (String method : List.of("dispatch", "rerun", "cancel")) {
            rule(ProjectGithubController.class, method, inProject(Permission.GITHUB_RUN));
        }
        rule(ProjectGithubController.class, "linkPullRequest", inProject(Permission.ISSUE_EDIT));
        rule(ProjectGithubController.class, "createBranch", inProject(Permission.ISSUE_EDIT));
        rule(ProjectGithubController.class, "linkBranch", inProject(Permission.ISSUE_EDIT));
        rule(ProjectGithubController.class, "unlinkBranch", inProject(Permission.ISSUE_EDIT));
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

    /**
     * Cada {@code #nombre} de la regla es un parámetro del método: si no lo es, Spring Security lo evalúa como
     * {@code null} y la regla deja de proteger lo que debería (por ejemplo, un {@code #projectId} mal escrito).
     */
    @Test
    void losParametrosDeLaReglaExistenEnElMetodo() {
        Pattern variable = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");
        List<String> missing = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            PreAuthorize rule = endpoint.rule();
            if (rule == null) {
                continue;
            }
            Set<String> parameters = new HashSet<>();
            for (Parameter parameter : endpoint.method().getParameters()) {
                assertThat(parameter.isNamePresent())
                        .as("%s compilado sin -parameters: no se pueden comprobar los nombres", endpoint.key())
                        .isTrue();
                parameters.add(parameter.getName());
            }
            Matcher matcher = variable.matcher(rule.value());
            while (matcher.find()) {
                if (!parameters.contains(matcher.group(1))) {
                    missing.add(endpoint.key() + " usa #" + matcher.group(1));
                }
            }
        }

        assertThat(missing).as("parámetros de @PreAuthorize que el método no tiene").isEmpty();
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
