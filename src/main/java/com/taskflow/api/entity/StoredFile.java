package com.taskflow.api.entity;

import com.taskflow.api.entity.enums.FileKind;
import com.taskflow.api.entity.enums.FileStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Un archivo guardado en Cloudflare R2 (bucket privado): un adjunto, una imagen de un texto o una foto de perfil. Se ve
 * con URLs firmadas de poca duración. Al borrar la fila (a mano o en cascada con su tarea, su proyecto o su workspace)
 * un trigger deja la clave en {@code storage_deletions} y {@code StorageCleanup} borra el objeto de R2.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stored_files")
public class StoredFile extends BaseEntity {

    /** {@code null} en las fotos de perfil. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    /** {@code null} en las fotos de perfil. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    /** Tarea del adjunto o de la imagen; {@code null} en una imagen de «Nueva tarea» (hasta crearla) o un avatar. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private Issue issue;

    /** Quien lo subió (en un avatar, su dueño). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileStatus status = FileStatus.PENDING;

    @Column(name = "object_key", nullable = false, length = 200, updatable = false)
    private String objectKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public boolean isReady() {
        return status == FileStatus.READY;
    }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
