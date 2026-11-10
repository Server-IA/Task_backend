package com.taskflow.api.integration;

import com.taskflow.api.dto.request.AddProjectMemberRequest;
import com.taskflow.api.dto.request.CreateIssueRequest;
import com.taskflow.api.dto.request.CreateProjectRequest;
import com.taskflow.api.dto.request.CreateWorkspaceRequest;
import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.dto.request.RolePermissionsRequest;
import com.taskflow.api.dto.request.RoleRequest;
import com.taskflow.api.dto.request.UpdateMemberRoleRequest;
import com.taskflow.api.dto.request.UpdateRoleRequest;
import com.taskflow.api.dto.response.InvitationResponse;
import com.taskflow.api.dto.response.RoleResponse;
import com.taskflow.api.dto.response.WorkspaceMemberResponse;
import com.taskflow.api.dto.response.WorkspaceResponse;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.enums.Permission;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.exception.ConflictException;
import com.taskflow.api.exception.ForbiddenException;
import com.taskflow.api.repository.UserRepository;
import com.taskflow.api.security.UserPrincipal;
import com.taskflow.api.security.WorkspaceSecurity;
import com.taskflow.api.service.InvitationService;
import com.taskflow.api.service.IssueService;
import com.taskflow.api.service.ProjectService;
import com.taskflow.api.service.RoleService;
import com.taskflow.api.service.WorkspaceService;
import com.taskflow.api.util.TokenUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Roles con permisos contra un PostgreSQL de verdad ({@link TestDatabase}): la migración (los workspaces que ya
 * existían quedan con el rol «Miembro» de siempre), crear y asignar roles, lo que cada rol deja hacer, que nadie se
 * suba los permisos y qué pasa al eliminar un rol.
 */
@SpringBootTest
class RolesIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("app.jwt.secret", () -> "secreto-de-integracion-con-mas-de-32-caracteres");
        registry.add("app.mail.provider", () -> "resend");
        registry.add("app.mail.resend-api-key", () -> "");
        registry.add("app.frontend-url", () -> "http://localhost:5173");
        registry.add("app.maintenance.cron", () -> "-");
    }

    @BeforeAll
    static void requirePostgres() {
        assumeTrue(TestDatabase.available(), "PostgreSQL no disponible (DB_URL)");
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private InvitationService invitationService;
    @Autowired
    private IssueService issueService;
    @Autowired
    private WorkspaceSecurity workspaceSecurity;

    private User pm;
    private User dev;
    private String slug;
    private UUID workspaceId;
    private UUID projectId;

    @BeforeEach
    void workspace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        pm = user("pm-" + suffix + "@test.com", "Paula PM");
        dev = user("dev-" + suffix + "@test.com", "Diego Dev");
        WorkspaceResponse workspace = workspaceService.create(pm.getId(),
                new CreateWorkspaceRequest("Equipo " + suffix, "rl-" + suffix));
        slug = workspace.slug();
        workspaceId = workspace.id();
        // Como entraban los miembros antes de los roles: sin rol propio
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) VALUES (gen_random_uuid(), '" + workspaceId
                + "', '" + dev.getId() + "', 'MEMBER')");
        projectId = projectService.create(slug, pm.getId(), new CreateProjectRequest("Web",
                "R" + suffix.substring(0, 4).toUpperCase().replaceAll("[0-9]", "X"), null, null)).id();
        projectService.addMember(slug, projectId, new AddProjectMemberRequest(dev.getId()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ----- Migración y roles de fábrica -----

    @Test
    void quienCreaElWorkspaceEsProjectManagerConTodosLosPermisos() {
        WorkspaceResponse mine = workspaceService.get(slug, pm.getId());

        assertThat(mine.myRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(mine.myRoleName()).isEqualTo("Project manager");
        assertThat(mine.myRoleId()).isNull();
        assertThat(mine.myPermissions()).containsExactlyElementsOf(Permission.names(Permission.ALL));
    }

    @Test
    void unWorkspaceAnteriorALosRolesQuedaConElMiembroDeSiempre() {
        // La columna nueva toma su valor por defecto en las filas que ya existían: debe ser el mismo de Permission
        String old = "viejo-" + UUID.randomUUID().toString().substring(0, 8);
        sql("INSERT INTO workspaces (id, name, slug, owner_id, created_at, updated_at) VALUES (gen_random_uuid(), 'Viejo', '"
                + old + "', '" + pm.getId() + "', now(), now())");
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role) SELECT gen_random_uuid(), id, '" + dev.getId()
                + "', 'MEMBER' FROM workspaces WHERE slug = '" + old + "'");

        WorkspaceResponse mine = workspaceService.get(old, dev.getId());

        assertThat(mine.myRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(mine.myRoleName()).isEqualTo("Miembro");
        assertThat(mine.myPermissions()).containsExactlyElementsOf(Permission.names(Permission.MEMBER_DEFAULTS));
    }

    @Test
    void laListaTraeLosDosRolesDeFabricaYCuentaSusMiembros() {
        List<RoleResponse> roles = roleService.list(slug);

        assertThat(roles).extracting(RoleResponse::key).containsExactly("MANAGER", "MEMBER");
        assertThat(roles.get(0).permissions()).hasSize(Permission.values().length);
        assertThat(roles.get(0).memberCount()).isEqualTo(1);
        assertThat(roles.get(1).permissions()).containsExactlyElementsOf(Permission.names(Permission.MEMBER_DEFAULTS));
        assertThat(roles.get(1).memberCount()).isEqualTo(1);
    }

    // ----- Crear, editar y asignar -----

    @Test
    void unRolCreadoDaJustoSusPermisos() {
        RoleResponse leader = roleService.create(slug, pm.getId(), new RoleRequest("  Líder  ", "Coordina el sprint",
                List.of("CYCLE_CREATE", "ISSUE_EDIT", "MEMBER_INVITE")));
        assertThat(leader.name()).isEqualTo("Líder");
        assertThat(leader.kind()).isEqualTo(RoleResponse.Kind.CUSTOM);

        WorkspaceMemberResponse member = workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, leader.id()));
        assertThat(member.roleId()).isEqualTo(leader.id());
        assertThat(member.roleName()).isEqualTo("Líder");

        WorkspaceResponse mine = workspaceService.get(slug, dev.getId());
        assertThat(mine.myRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(mine.myRoleId()).isEqualTo(leader.id());
        assertThat(mine.myPermissions()).containsExactly("ISSUE_EDIT", "CYCLE_CREATE", "MEMBER_INVITE");

        login(dev);
        assertThat(workspaceSecurity.can(slug, "MEMBER_INVITE")).isTrue();
        assertThat(workspaceSecurity.canInProject(slug, projectId, "CYCLE_CREATE")).isTrue();
        // El rol creado reemplaza a «Miembro»: lo que no se marcó, no se puede
        assertThat(workspaceSecurity.canInProject(slug, projectId, "CYCLE_DELETE")).isFalse();
        assertThat(workspaceSecurity.can(slug, "ROLE_MANAGE")).isFalse();
        assertThatThrownBy(() -> issueService.create(slug, projectId, dev.getId(), newIssue()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Tu rol no permite crear tareas");
        assertThat(roleService.list(slug)).filteredOn(role -> role.key().equals(leader.key()))
                .extracting(RoleResponse::memberCount).containsExactly(1L);
    }

    @Test
    void cambiarLosPermisosDeMiembroValeParaQuienNoTieneRolPropio() {
        login(dev);
        assertThat(workspaceSecurity.canInProject(slug, projectId, "ISSUE_CREATE")).isTrue();
        assertThat(workspaceSecurity.can(slug, "MEMBER_INVITE")).isFalse();

        RoleResponse member = roleService.updateMemberPermissions(slug, pm.getId(),
                new RolePermissionsRequest(List.of("MEMBER_INVITE", "COMMENT_CREATE")));

        assertThat(member.permissions()).containsExactly("COMMENT_CREATE", "MEMBER_INVITE");
        assertThat(workspaceSecurity.canInProject(slug, projectId, "ISSUE_CREATE")).isFalse();
        assertThat(workspaceSecurity.can(slug, "MEMBER_INVITE")).isTrue();
        // El project manager no depende de eso
        login(pm);
        assertThat(workspaceSecurity.canInProject(slug, projectId, "ISSUE_CREATE")).isTrue();
    }

    @Test
    void unRolSeRenombraYCambiaSusPermisosSinRepetirNombres() {
        RoleResponse leader = roleService.create(slug, pm.getId(), new RoleRequest("Líder", null, List.of("ISSUE_EDIT")));
        roleService.create(slug, pm.getId(), new RoleRequest("Invitado", null, List.of()));

        RoleResponse updated = roleService.update(slug, pm.getId(), leader.id(),
                new UpdateRoleRequest("Tech lead", "", List.of("ISSUE_EDIT", "STORY_MANAGE")));
        assertThat(updated.name()).isEqualTo("Tech lead");
        assertThat(updated.description()).isNull();
        assertThat(updated.permissions()).containsExactly("ISSUE_EDIT", "STORY_MANAGE");

        // Sin importar mayúsculas, ni el de otro rol ni el de uno de fábrica
        assertThatThrownBy(() -> roleService.update(slug, pm.getId(), leader.id(), new UpdateRoleRequest("INVITADO", null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> roleService.create(slug, pm.getId(), new RoleRequest("miembro", null, List.of())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> roleService.create(slug, pm.getId(), new RoleRequest("project manager", null, List.of())))
                .isInstanceOf(ConflictException.class);
        // El mismo nombre que ya tiene no choca consigo mismo
        assertThat(roleService.update(slug, pm.getId(), leader.id(), new UpdateRoleRequest("Tech Lead", null, null)).name())
                .isEqualTo("Tech Lead");
        assertThatThrownBy(() -> roleService.create(slug, pm.getId(), new RoleRequest("Raro", null, List.of("VOLAR"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VOLAR");
    }

    @Test
    void alEliminarUnRolSusMiembrosYSusInvitacionesVuelvenAMiembro() {
        RoleResponse leader = roleService.create(slug, pm.getId(), new RoleRequest("Líder", null, List.of("CYCLE_CREATE")));
        workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, leader.id()));
        InvitationResponse invitation = invitationService.invite(slug, pm.getId(),
                new InviteMemberRequest("nueva-" + UUID.randomUUID() + "@test.com", leader.id()));
        assertThat(invitation.roleName()).isEqualTo("Líder");

        roleService.delete(slug, pm.getId(), leader.id());

        assertThat(roleService.list(slug)).extracting(RoleResponse::key).containsExactly("MANAGER", "MEMBER");
        WorkspaceResponse mine = workspaceService.get(slug, dev.getId());
        assertThat(mine.myRoleId()).isNull();
        assertThat(mine.myRoleName()).isEqualTo("Miembro");
        assertThat(mine.myPermissions()).containsExactlyElementsOf(Permission.names(Permission.MEMBER_DEFAULTS));
        assertThat(invitationService.getPending(slug)).singleElement()
                .satisfies(pending -> {
                    assertThat(pending.roleId()).isNull();
                    assertThat(pending.roleName()).isEqualTo("Miembro");
                });
    }

    @Test
    void laPersonaInvitadaEntraConElRolDeLaInvitacion() {
        RoleResponse guestRole = roleService.create(slug, pm.getId(), new RoleRequest("Invitado", null, List.of("COMMENT_CREATE")));
        User guest = user("inv-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "Irene Invitada");
        String token = "tok-" + UUID.randomUUID();
        sql("INSERT INTO workspace_invitations (id, workspace_id, email, role, custom_role_id, token_hash, invited_by_id, "
                + "expires_at, accepted) VALUES (gen_random_uuid(), '" + workspaceId + "', '" + guest.getEmail()
                + "', 'MEMBER', '" + guestRole.id() + "', '" + TokenUtils.sha256(token) + "', '" + pm.getId()
                + "', now() + interval '7 days', false)");

        WorkspaceResponse joined = invitationService.accept(token, guest.getId());

        assertThat(joined.myRoleName()).isEqualTo("Invitado");
        assertThat(joined.myPermissions()).containsExactly("COMMENT_CREATE");
        assertThat(value("SELECT custom_role_id FROM workspace_members WHERE user_id = '" + guest.getId() + "'"))
                .isEqualTo(guestRole.id().toString());
    }

    // ----- Nadie se sube los permisos -----

    @Test
    void quienGestionaRolesSinSerProjectManagerNoDaPermisosQueNoTiene() {
        RoleResponse admin = roleService.create(slug, pm.getId(), new RoleRequest("Admin de roles", null,
                List.of("ROLE_MANAGE", "MEMBER_ROLE", "ISSUE_EDIT")));
        RoleResponse powerful = roleService.create(slug, pm.getId(), new RoleRequest("Jefe", null,
                List.of("PROJECT_DELETE", "ISSUE_EDIT")));
        workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, admin.id()));

        // Puede crear un rol con lo que tiene…
        RoleResponse editor = roleService.create(slug, dev.getId(), new RoleRequest("Editor", null, List.of("ISSUE_EDIT")));
        assertThat(editor.permissions()).containsExactly("ISSUE_EDIT");
        // …pero no con lo que le falta, ni agregándoselo a su propio rol, ni tocando un rol que tiene más que él
        assertThatThrownBy(() -> roleService.create(slug, dev.getId(), new RoleRequest("Trampa", null, List.of("PROJECT_DELETE"))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Eliminar proyectos");
        assertThatThrownBy(() -> roleService.update(slug, dev.getId(), admin.id(),
                new UpdateRoleRequest(null, null, List.of("ROLE_MANAGE", "MEMBER_ROLE", "ISSUE_EDIT", "WORKSPACE_EDIT"))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> roleService.update(slug, dev.getId(), powerful.id(), new UpdateRoleRequest("Otro", null, null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> roleService.delete(slug, dev.getId(), powerful.id()))
                .isInstanceOf(ForbiddenException.class);
        // «Miembro» tiene permisos que a él le faltan (crear tareas): tampoco lo cambia
        assertThatThrownBy(() -> roleService.updateMemberPermissions(slug, dev.getId(), new RolePermissionsRequest(List.of())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void quienCambiaRolesSinSerProjectManagerNoAsciendeANadie() {
        RoleResponse admin = roleService.create(slug, pm.getId(), new RoleRequest("Admin de roles", null,
                List.of("MEMBER_ROLE", "MEMBER_REMOVE", "ISSUE_EDIT")));
        RoleResponse powerful = roleService.create(slug, pm.getId(), new RoleRequest("Jefe", null, List.of("PROJECT_DELETE")));
        RoleResponse editor = roleService.create(slug, pm.getId(), new RoleRequest("Editor", null, List.of("ISSUE_EDIT")));
        workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, admin.id()));
        User other = user("otra-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "Olga Otra");
        sql("INSERT INTO workspace_members (id, workspace_id, user_id, role, custom_role_id) VALUES (gen_random_uuid(), '"
                + workspaceId + "', '" + other.getId() + "', 'MEMBER', '" + editor.id() + "')");

        // Ni a project manager (a sí mismo o a otra persona), ni a un rol con más permisos que el suyo
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, dev.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.OWNER)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, dev.getId(), other.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, powerful.id())))
                .isInstanceOf(ForbiddenException.class);
        // Tampoco degrada ni saca al project manager
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, dev.getId(), pm.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> workspaceService.removeMember(slug, dev.getId(), pm.getId()))
                .isInstanceOf(ForbiddenException.class);
        // «Miembro» (de fábrica) tiene permisos que a él le faltan: tampoco lo asigna
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, dev.getId(), other.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER)))
                .isInstanceOf(ForbiddenException.class);
        // Sí puede lo que cabe en sus permisos: quitar a alguien con un rol menor
        workspaceService.removeMember(slug, dev.getId(), other.getId());
        assertThat(workspaceService.getMembers(slug)).extracting(member -> member.user().id())
                .doesNotContain(other.getId());
    }

    @Test
    void unRolDelWorkspaceNoSeMezclaConProjectManagerNiConOtroWorkspace() {
        RoleResponse leader = roleService.create(slug, pm.getId(), new RoleRequest("Líder", null, List.of()));
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.OWNER, leader.id())))
                .isInstanceOf(BadRequestException.class);

        String otherSlug = workspaceService.create(pm.getId(),
                new CreateWorkspaceRequest("Otro", "ot-" + UUID.randomUUID().toString().substring(0, 8))).slug();
        RoleResponse foreign = roleService.create(otherSlug, pm.getId(), new RoleRequest("Ajeno", null, List.of()));
        assertThatThrownBy(() -> workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, foreign.id())))
                .hasMessage("Rol no encontrado");

        // Al ascender a project manager se le quita el rol propio (la base de datos no deja tener los dos)
        workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.MEMBER, leader.id()));
        WorkspaceMemberResponse promoted = workspaceService.updateMemberRole(slug, pm.getId(), dev.getId(),
                new UpdateMemberRoleRequest(WorkspaceRole.OWNER));
        assertThat(promoted.role()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(promoted.roleId()).isNull();
        assertThat(promoted.roleName()).isEqualTo("Project manager");
    }

    // ----- Utilidades -----

    private static CreateIssueRequest newIssue() {
        return new CreateIssueRequest("Login", null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private void login(User user) {
        UserPrincipal principal = UserPrincipal.fromToken(user.getId(), user.getEmail());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private User user(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setFullName(name);
        user.setPasswordHash(passwordEncoder.encode("Clave1234"));
        user.setEmailVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private void sql(String statement) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement query = connection.prepareStatement(statement)) {
            query.execute();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String value(String query) {
        try (Connection connection = TestDatabase.connect();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
