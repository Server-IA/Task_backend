package com.taskflow.api.repository;

import com.taskflow.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
            select new com.taskflow.api.repository.UserCredentialsState(u.active, u.credentialsVersion)
            from User u where u.id = :id
            """)
    Optional<UserCredentialsState> findCredentialsState(@Param("id") UUID id);

    /**
     * Bloquea la fila del usuario hasta el final de la transacción ({@code SELECT … FOR UPDATE}) y devuelve, leídos de
     * la base de datos (no de la entidad en memoria), su hash de contraseña y la versión de sus credenciales. Lo usan
     * el login, la rotación del refresh token y el cambio de contraseña: así un cambio de contraseña no se cruza con
     * una sesión que se está abriendo o renovando (o espera a que termine y la cierra, o la otra ve el cambio).
     */
    @Query(value = """
            SELECT password_hash AS "passwordHash", credentials_version AS "credentialsVersion"
            FROM users WHERE id = :id FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedCredentials> lockCredentials(@Param("id") UUID id);

    /**
     * Bloquea la fila del usuario hasta el final de la transacción. Lo usa la creación de workspaces: dos creaciones
     * simultáneas de la misma persona cuentan sus workspaces en fila y no superan juntas el máximo.
     * {@code NO KEY UPDATE}: no choca con las filas que solo apuntan al usuario.
     */
    @Query(value = "SELECT 1 FROM users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    List<Integer> lockById(@Param("id") UUID id);

    /**
     * De los usuarios indicados, los que se pueden mencionar en una tarea del proyecto: sus miembros y los project
     * managers del workspace (que ven todos los proyectos).
     */
    @Query("""
            select u from User u
            where u.id in :ids
              and (exists (select 1 from ProjectMember pm where pm.project.id = :projectId and pm.user = u)
                   or exists (select 1 from WorkspaceMember wm
                              where wm.workspace.id = :workspaceId and wm.user = u
                                and wm.role = com.taskflow.api.entity.enums.WorkspaceRole.OWNER))
            """)
    List<User> findMentionable(@Param("ids") Collection<UUID> ids, @Param("projectId") UUID projectId,
                               @Param("workspaceId") UUID workspaceId);
}
