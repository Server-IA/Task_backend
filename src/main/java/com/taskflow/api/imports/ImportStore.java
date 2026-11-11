package com.taskflow.api.imports;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Las tablas propias de la importación y los ajustes que el importador hace por SQL porque las entidades no los
 * permiten: conservar la fecha de creación original (la entidad siempre pone «ahora») y fijar el contador de tareas
 * del proyecto. Todo corre dentro de la transacción de quien llama.
 */
@Component
public class ImportStore {

    public static final String UPLOAD = "UPLOAD";
    public static final String BUNDLE = "BUNDLE";

    /** Qué creó una importación (ver {@code import_items}). */
    public enum ItemKind {
        PROJECT, STATE, LABEL, MODULE, CYCLE, ISSUE
    }

    @PersistenceContext
    private EntityManager entityManager;

    // ----- Lo subido y lo leído del origen

    public void savePayload(UUID jobId, String kind, byte[] data) {
        entityManager.createNativeQuery("""
                        INSERT INTO import_payloads (job_id, kind, data) VALUES (:job, :kind, :data)
                        ON CONFLICT (job_id, kind) DO UPDATE SET data = EXCLUDED.data
                        """)
                .setParameter("job", jobId).setParameter("kind", kind).setParameter("data", data).executeUpdate();
    }

    /** @return los datos, o {@code null} si no hay */
    public byte[] payload(UUID jobId, String kind) {
        List<?> rows = entityManager.createNativeQuery("SELECT data FROM import_payloads WHERE job_id = :job AND kind = :kind")
                .setParameter("job", jobId).setParameter("kind", kind).getResultList();
        return rows.isEmpty() ? null : (byte[]) rows.get(0);
    }

    public void deletePayloads(UUID jobId) {
        entityManager.createNativeQuery("DELETE FROM import_payloads WHERE job_id = :job").setParameter("job", jobId).executeUpdate();
    }

    // ----- Lo que creó la importación

    public void addItem(UUID jobId, ItemKind kind, UUID entityId) {
        entityManager.createNativeQuery("INSERT INTO import_items (job_id, kind, entity_id) VALUES (:job, :kind, :entity)")
                .setParameter("job", jobId).setParameter("kind", kind.name()).setParameter("entity", entityId).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    public List<UUID> items(UUID jobId, ItemKind kind) {
        return entityManager.createNativeQuery("SELECT entity_id FROM import_items WHERE job_id = :job AND kind = :kind ORDER BY id", UUID.class)
                .setParameter("job", jobId).setParameter("kind", kind.name()).getResultList();
    }

    public void deleteItems(UUID jobId) {
        entityManager.createNativeQuery("DELETE FROM import_items WHERE job_id = :job").setParameter("job", jobId).executeUpdate();
    }

    // ----- Fechas originales

    /** La tarea queda creada (y actualizada por última vez) cuando lo fue en el origen, igual que su actividad «creada». */
    public void backdateIssue(UUID issueId, Instant createdAt, Instant updatedAt) {
        entityManager.createNativeQuery("UPDATE issues SET created_at = :created, updated_at = :updated WHERE id = :id")
                .setParameter("created", createdAt).setParameter("updated", updatedAt).setParameter("id", issueId).executeUpdate();
        entityManager.createNativeQuery("UPDATE issue_activities SET created_at = :created, updated_at = :created WHERE issue_id = :id")
                .setParameter("created", createdAt).setParameter("id", issueId).executeUpdate();
    }

    public void backdateComment(UUID commentId, Instant createdAt) {
        entityManager.createNativeQuery("UPDATE comments SET created_at = :created, updated_at = :created WHERE id = :id")
                .setParameter("created", createdAt).setParameter("id", commentId).executeUpdate();
    }

    // ----- Proyecto

    /** El contador de tareas del proyecto queda, como mínimo, en ese número: el siguiente código no choca con uno importado. */
    public void raiseIssueCounter(UUID projectId, int atLeast) {
        entityManager.createNativeQuery("UPDATE projects SET issue_counter = GREATEST(issue_counter, :value) WHERE id = :id")
                .setParameter("value", atLeast).setParameter("id", projectId).executeUpdate();
    }

    public void addToCycle(UUID cycleId, UUID issueId) {
        entityManager.createNativeQuery("INSERT INTO cycle_issues (cycle_id, issue_id) VALUES (:cycle, :issue)")
                .setParameter("cycle", cycleId).setParameter("issue", issueId).executeUpdate();
    }

    public void addToModule(UUID moduleId, UUID issueId) {
        entityManager.createNativeQuery("INSERT INTO module_issues (module_id, issue_id) VALUES (:module, :issue)")
                .setParameter("module", moduleId).setParameter("issue", issueId).executeUpdate();
    }

    // ----- Tareas de personas invitadas

    public void addPendingAssignment(UUID workspaceId, String email, UUID issueId) {
        entityManager.createNativeQuery("""
                        INSERT INTO import_pending_assignments (workspace_id, email, issue_id) VALUES (:workspace, :email, :issue)
                        ON CONFLICT (issue_id, email) DO NOTHING
                        """)
                .setParameter("workspace", workspaceId).setParameter("email", email).setParameter("issue", issueId).executeUpdate();
    }

    @SuppressWarnings("unchecked")
    public List<UUID> pendingIssues(UUID workspaceId, String email) {
        return entityManager.createNativeQuery(
                        "SELECT issue_id FROM import_pending_assignments WHERE workspace_id = :workspace AND email = :email", UUID.class)
                .setParameter("workspace", workspaceId).setParameter("email", email).getResultList();
    }

    public void deletePending(UUID workspaceId, String email) {
        entityManager.createNativeQuery("DELETE FROM import_pending_assignments WHERE workspace_id = :workspace AND email = :email")
                .setParameter("workspace", workspaceId).setParameter("email", email).executeUpdate();
    }

    /** Asigna la tarea a la persona, si no lo estaba. */
    public void assign(UUID issueId, UUID userId) {
        entityManager.createNativeQuery("""
                        INSERT INTO issue_assignees (issue_id, user_id)
                        SELECT :issue, :user WHERE NOT EXISTS (SELECT 1 FROM issue_assignees WHERE issue_id = :issue AND user_id = :user)
                        """)
                .setParameter("issue", issueId).setParameter("user", userId).executeUpdate();
    }

    // ----- Deshacer

    /** Borra por identificador en una de las tablas del importador (el nombre de la tabla lo pone el código, nunca la persona). */
    public void deleteByIds(String table, Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            entityManager.createNativeQuery("DELETE FROM " + table + " WHERE id IN (:ids)").setParameter("ids", ids).executeUpdate();
        }
    }

    /** Las etiquetas que creó la importación y ninguna tarea lleva ya (una que la persona siguió usando se queda). */
    public void deleteUnusedLabels(Collection<UUID> ids) {
        deleteUnused("labels", "SELECT 1 FROM issue_labels il WHERE il.label_id = t.id", ids);
    }

    /** Los estados que creó la importación y en los que no queda ninguna tarea. */
    public void deleteUnusedStates(Collection<UUID> ids) {
        deleteUnused("states", "SELECT 1 FROM issues i WHERE i.state_id = t.id", ids);
    }

    private void deleteUnused(String table, String usage, Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            entityManager.createNativeQuery("DELETE FROM " + table + " t WHERE t.id IN (:ids) AND NOT EXISTS (" + usage + ")")
                    .setParameter("ids", ids).executeUpdate();
        }
    }
}
