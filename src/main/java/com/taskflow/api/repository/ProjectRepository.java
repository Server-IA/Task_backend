package com.taskflow.api.repository;

import com.taskflow.api.entity.Project;
import com.taskflow.api.entity.enums.ProjectColor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** Busca el proyecto solo si pertenece al workspace indicado. */
    @Query("select p from Project p join fetch p.workspace w where p.id = :id and w.slug = :slug")
    Optional<Project> findInWorkspace(@Param("id") UUID id, @Param("slug") String slug);

    List<Project> findAllByWorkspaceIdOrderByName(UUID workspaceId);

    @Query("""
            select p from Project p
            where p.workspace.id = :workspaceId
              and exists (select 1 from ProjectMember pm where pm.project = p and pm.user.id = :userId)
            order by p.name
            """)
    List<Project> findAllByWorkspaceIdAndMember(@Param("workspaceId") UUID workspaceId,
                                                @Param("userId") UUID userId);

    @Query("select p.id from Project p where p.workspace.id = :workspaceId")
    List<UUID> findIdsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("""
            select pm.project.id from ProjectMember pm
            where pm.user.id = :userId and pm.project.workspace.id = :workspaceId
            """)
    List<UUID> findIdsByWorkspaceIdAndMember(@Param("workspaceId") UUID workspaceId,
                                             @Param("userId") UUID userId);

    boolean existsByWorkspaceIdAndIdentifier(UUID workspaceId, String identifier);

    /**
     * El proyecto existe pero es de otro workspace. {@code WorkspaceSecurity} lo usa para negar el acceso; si no
     * existe, el servicio responde 404.
     */
    @Query("select count(p) > 0 from Project p where p.id = :id and p.workspace.slug <> :slug")
    boolean existsInOtherWorkspace(@Param("id") UUID id, @Param("slug") String slug);

    /**
     * Contadores de los proyectos indicados en una sola consulta (una fila por proyecto con tareas; los que no
     * tienen ninguna no aparecen). Columnas: project_id, issue_count, open_issue_count, overdue_issue_count,
     * open_story_count, unsplit_story_count. {@code today} es el día en la zona horaria de la aplicación.
     */
    @Query(value = """
            SELECT i.project_id AS project_id,
                   count(*) FILTER (WHERE i.issue_type <> 'STORY') AS issue_count,
                   count(*) FILTER (WHERE i.issue_type <> 'STORY'
                                      AND st.state_group NOT IN ('COMPLETED', 'CANCELLED')) AS open_issue_count,
                   count(*) FILTER (WHERE i.issue_type <> 'STORY'
                                      AND st.state_group NOT IN ('COMPLETED', 'CANCELLED')
                                      AND i.due_date < :today) AS overdue_issue_count,
                   count(*) FILTER (WHERE i.issue_type = 'STORY'
                                      AND st.state_group NOT IN ('COMPLETED', 'CANCELLED')) AS open_story_count,
                   count(*) FILTER (WHERE i.issue_type = 'STORY'
                                      AND st.state_group NOT IN ('COMPLETED', 'CANCELLED')
                                      AND NOT EXISTS (SELECT 1 FROM issues c
                                                      WHERE c.parent_id = i.id AND c.issue_type = 'TASK'))
                       AS unsplit_story_count
            FROM issues i
                     JOIN states st ON st.id = i.state_id
            WHERE i.project_id IN (:projectIds)
            GROUP BY i.project_id
            """, nativeQuery = true)
    List<Object[]> countIssues(@Param("projectIds") Collection<UUID> projectIds, @Param("today") LocalDate today);

    /** Colores de los proyectos del workspace (uno por proyecto, con repeticiones). */
    @Query("select p.color from Project p where p.workspace.id = :workspaceId")
    List<ProjectColor> findColorsByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    /**
     * Incrementa el contador de issues con un UPDATE atómico. La fila queda bloqueada hasta el final de la
     * transacción, así dos issues creados a la vez nunca reciben el mismo número.
     */
    /**
     * Bloquea la fila del proyecto hasta el final de la transacción. Lo usan los cambios que comprueban algo del
     * proyecto antes de escribir y que la base de datos no garantiza sola: que no haya dos estados o etiquetas con el
     * mismo nombre sin importar mayúsculas («Bug» y «bug» a la vez) y que la primera nota de la daily de un día no se
     * cree dos veces. {@code NO KEY UPDATE}: no choca con las filas que solo apuntan al proyecto.
     */
    @Query(value = "SELECT 1 FROM projects WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    List<Integer> lockById(@Param("id") UUID id);

    @Modifying
    @Query(value = "UPDATE projects SET issue_counter = issue_counter + 1 WHERE id = :id", nativeQuery = true)
    int incrementIssueCounter(@Param("id") UUID id);

    /** Lee el contador directamente de la base de datos (no de la entidad en memoria). */
    @Query("select p.issueCounter from Project p where p.id = :id")
    int findIssueCounter(@Param("id") UUID id);
}
