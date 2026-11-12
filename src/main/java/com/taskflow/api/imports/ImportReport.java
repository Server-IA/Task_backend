package com.taskflow.api.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lo que resultó de una importación: cuánto entró, qué proyectos se crearon y qué no entró y por qué. Se va llenando
 * mientras corre y se guarda al terminar (o al detenerse o fallar, con lo que alcanzó a entrar).
 */
public final class ImportReport {

    /** Cuántas cosas que no entraron se guardan con detalle; de ahí en adelante solo se cuentan. */
    private static final int MAX_SKIPPED = 2000;

    public enum SkipReason {
        /** El archivo pasa del límite por archivo. */
        ATTACHMENT_TOO_BIG,
        /** TaskFlow no acepta ese tipo de archivo. */
        ATTACHMENT_TYPE,
        /** No cabe en el espacio que le queda al workspace. */
        ATTACHMENT_QUOTA,
        /** No se pudo descargar del origen. */
        ATTACHMENT_FAILED,
        /** La relación apunta a una tarea que no se importó. */
        LINK_OUTSIDE,
        /** No se pudo enviar la invitación. */
        INVITATION_FAILED,
        /** La tarea no se pudo crear. */
        ISSUE_FAILED
    }

    /**
     * @param key    el código de la tarea en el origen, si lo tiene
     * @param title  el título de la tarea (o el correo, en una invitación)
     * @param detail el nombre del archivo, la otra tarea de la relación o el motivo
     * @param url    el enlace para abrir la tarea en el origen
     */
    public record Skipped(SkipReason reason, String key, String title, String detail, String url) {
    }

    public record CreatedProject(UUID id, String identifier, String name, int issues, boolean created) {
    }

    public int modules;
    public int cycles;
    public int stories;
    public int tasks;
    public int bugs;
    public int comments;
    public int attachments;
    public long attachmentBytes;
    public int labels;
    public int states;
    public int relations;
    public int invitations;
    public int skippedTotal;
    public List<CreatedProject> projects = new ArrayList<>();
    public List<Skipped> skipped = new ArrayList<>();

    public void skip(SkipReason reason, String key, String title, String detail, String url) {
        skippedTotal++;
        if (skipped.size() < MAX_SKIPPED) {
            skipped.add(new Skipped(reason, key, title, detail, url));
        }
    }

    public int issues() {
        return stories + tasks + bugs;
    }
}
