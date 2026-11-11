package com.taskflow.api.imports;

import com.taskflow.api.config.AsyncConfig;
import com.taskflow.api.entity.ImportJob;
import com.taskflow.api.entity.enums.ImportSourceType;
import com.taskflow.api.entity.enums.ImportStatus;
import com.taskflow.api.entity.enums.ImportVia;
import com.taskflow.api.i18n.Language;
import com.taskflow.api.imports.ImportPlan.PlannedIssue;
import com.taskflow.api.imports.ImportPlan.PlannedProject;
import com.taskflow.api.imports.ImportProgress.Stage;
import com.taskflow.api.imports.ImportProgress.StageKey;
import com.taskflow.api.imports.ImportStore.ItemKind;
import com.taskflow.api.imports.ImportWriter.Session;
import com.taskflow.api.imports.csv.CsvImport;
import com.taskflow.api.imports.model.ImportBundle;
import com.taskflow.api.imports.model.ImportBundle.ExtAttachment;
import com.taskflow.api.imports.source.ImportSource;
import com.taskflow.api.imports.source.SourceConnection;
import com.taskflow.api.imports.source.SourceException;
import com.taskflow.api.imports.source.TrelloFile;
import com.taskflow.api.repository.ImportJobRepository;
import com.taskflow.api.service.NotificationService;
import com.taskflow.api.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Corre en segundo plano lo que tarda de una importación: leer del origen lo elegido, escribirlo en TaskFlow y
 * deshacerlo. Cada tanda de escritura es una transacción aparte; entre tanda y tanda se guarda el avance y se mira si
 * alguien pidió detenerla. Lo que ya entró cuando una importación se detiene o falla se queda (y se puede deshacer).
 */
@Slf4j
@Component
public class ImportRunner {

    /** Tareas por transacción. */
    static final int CHUNK = 40;

    public static final String CONNECTION_LOST = "La conexión con el origen caducó: vuelve a conectar";
    private static final String INTERRUPTED = "El servidor se reinició mientras corría. Lo que alcanzó a entrar se puede deshacer.";
    private static final String UNEXPECTED = "Algo salió mal al importar. Lo que alcanzó a entrar se puede deshacer.";
    private static final String UNREADABLE = "No se pudo leer el origen. Inténtalo de nuevo.";

    private final ImportJobRepository jobRepository;
    private final ImportStore store;
    private final ImportWriter writer;
    private final ImportCredentials credentials;
    private final NotificationService notificationService;
    private final ObjectStorage storage;
    private final Map<ImportSourceType, ImportSource> sources = new EnumMap<>(ImportSourceType.class);
    private final TransactionTemplate transaction;
    private final Set<UUID> stopRequested = ConcurrentHashMap.newKeySet();

    public ImportRunner(ImportJobRepository jobRepository, ImportStore store, ImportWriter writer, ImportCredentials credentials,
                        NotificationService notificationService, ObjectStorage storage, List<ImportSource> sources,
                        PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.store = store;
        this.writer = writer;
        this.credentials = credentials;
        this.notificationService = notificationService;
        this.storage = storage;
        sources.forEach(source -> this.sources.put(source.type(), source));
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** El conector de una herramienta, o {@code null} si de esa solo se importa por archivo. */
    public ImportSource source(ImportSourceType type) {
        return sources.get(type);
    }

    public void requestStop(UUID jobId) {
        stopRequested.add(jobId);
    }

    /** Una importación que estaba corriendo cuando el servidor se apagó no va a seguir sola: se deja en un estado del que se sale. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        transaction.executeWithoutResult(status -> {
            for (ImportJob job : jobRepository.findAllByStatusIn(List.of(ImportStatus.FETCHING, ImportStatus.RUNNING, ImportStatus.UNDOING))) {
                job.setError(INTERRUPTED);
                job.setProgress(null);
                job.setStatus(job.getStatus() == ImportStatus.FETCHING ? ImportStatus.DRAFT : ImportStatus.FAILED);
                if (job.getFinishedAt() == null && job.getStatus() == ImportStatus.FAILED) {
                    job.setFinishedAt(Instant.now());
                }
            }
        });
    }

    // ----- Leer del origen

    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void fetch(UUID jobId) {
        try {
            ImportBundle bundle = read(jobId);
            byte[] compressed = ImportJson.compress(bundle);
            transaction.executeWithoutResult(status -> {
                ImportJob job = jobRepository.findById(jobId).orElseThrow();
                store.savePayload(jobId, ImportStore.BUNDLE, compressed);
                if (job.getVia() == ImportVia.FILE) {
                    job.setSelection(ImportJson.write(fitToFile(ImportJson.read(job.getSelection(), ImportSelection.class), bundle)));
                }
                job.setStatus(ImportStatus.READY);
                job.setProgress(null);
                job.setError(null);
            });
        } catch (SourceException ex) {
            log.info("Importación {}: el origen no dejó leer: {}", jobId, ex.getMessage());
            backToDraft(jobId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Importación {}: no se pudo leer el origen", jobId, ex);
            backToDraft(jobId, UNREADABLE);
        }
    }

    private ImportBundle read(UUID jobId) {
        record Input(ImportSourceType source, ImportVia via, ImportSelection selection, String label, byte[] upload) {
        }
        Input input = transaction.execute(status -> {
            ImportJob job = jobRepository.findById(jobId).orElseThrow();
            return new Input(job.getSource(), job.getVia(), ImportJson.read(job.getSelection(), ImportSelection.class),
                    job.getSourceLabel(), job.getVia() == ImportVia.FILE ? store.payload(jobId, ImportStore.UPLOAD) : null);
        });
        if (input.via() == ImportVia.FILE) {
            if (input.source() == ImportSourceType.TRELLO) {
                return TrelloFile.toBundle(input.upload());
            }
            String projectName = input.label().replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
            return CsvImport.toBundle(input.upload(), input.selection().csv().columns(), projectName);
        }
        SourceConnection connection = credentials.get(jobId).orElseThrow(() -> new SourceException(CONNECTION_LOST));
        List<String> projectIds = input.selection().projects().stream().map(ImportSelection.ProjectChoice::externalId).toList();
        long[] lastSaved = {0};
        return sources.get(input.source()).fetch(connection, projectIds, input.selection().optionsOrDefault().comments(), (done, total) -> {
            // Sin escribir en la base por cada tarea leída
            long now = System.currentTimeMillis();
            if (now - lastSaved[0] > 1500 || done >= total) {
                lastSaved[0] = now;
                saveProgress(jobId, new ImportProgress(List.of(new Stage(StageKey.FETCH, done, total)), null));
            }
        });
    }

    /**
     * Con un archivo, qué proyectos hay depende de sus columnas (y de cuáles eligió la persona): lo elegido se ajusta
     * a los proyectos que de verdad salieron. Uno solo toma el destino que se eligió; los demás entran como nuevos.
     */
    static ImportSelection fitToFile(ImportSelection selection, ImportBundle bundle) {
        List<ImportSelection.ProjectChoice> chosen = selection.projects() == null ? List.of() : selection.projects();
        List<ImportSelection.ProjectChoice> fitted = new ArrayList<>();
        for (ImportBundle.ExtProject project : bundle.projects()) {
            ImportSelection.ProjectChoice match = chosen.stream().filter(choice -> project.id().equals(choice.externalId())).findFirst()
                    .orElse(chosen.size() == 1 && bundle.projects().size() == 1 ? chosen.get(0) : null);
            fitted.add(match == null ? new ImportSelection.ProjectChoice(project.id(), null, null, null)
                    : new ImportSelection.ProjectChoice(project.id(), match.targetProjectId(), match.name(), match.identifier()));
        }
        return new ImportSelection(fitted, selection.options(), selection.csv());
    }

    private void backToDraft(UUID jobId, String error) {
        transaction.executeWithoutResult(status -> {
            ImportJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(ImportStatus.DRAFT);
            job.setProgress(null);
            job.setError(error);
        });
    }

    // ----- Escribir en TaskFlow

    /**
     * @param language el idioma de quien la lanzó: en ese quedan las notas que se agregan a las tareas
     */
    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void run(UUID jobId, String slug, Language language) {
        Session session = null;
        ImportStatus result = ImportStatus.COMPLETED;
        String error = null;
        try {
            session = transaction.execute(status -> {
                ImportJob job = jobRepository.findById(jobId).orElseThrow();
                ImportBundle bundle = ImportJson.decompress(store.payload(jobId, ImportStore.BUNDLE), ImportBundle.class);
                ImportPlan plan = ImportPlan.of(bundle, ImportJson.read(job.getSelection(), ImportSelection.class),
                        ImportJson.read(job.getMapping(), ImportMapping.class));
                // El autor y el workspace se leen aquí, dentro de la transacción
                job.getCreatedBy().getId();
                job.getWorkspace().getId();
                return writer.open(job, slug, plan, bundle, language);
            });
            if (!write(jobId, session)) {
                result = ImportStatus.STOPPED;
            }
        } catch (RuntimeException ex) {
            log.error("Importación {}: falló al escribir", jobId, ex);
            result = ImportStatus.FAILED;
            error = UNEXPECTED;
        }
        finish(jobId, session, result, error);
    }

    /** @return {@code false} si la detuvieron a la mitad */
    private boolean write(UUID jobId, Session session) {
        ImportPlan plan = session.plan();
        ImportPlan.Totals totals = plan.totals();
        boolean withAttachments = plan.options().attachments();
        int attachmentTotal = withAttachments ? totals.attachments() + totals.tooBig() + totals.notAllowed() : 0;
        int linkTotal = plan.projects().stream().flatMap(project -> project.issues().stream())
                .mapToInt(issue -> issue.source().links() == null ? 0 : issue.source().links().size()).sum();
        Tracker tracker = new Tracker(jobId, plan.projects().size(), plan.issueCount(), totals.comments(), attachmentTotal, linkTotal);

        for (PlannedProject project : plan.projects()) {
            transaction.executeWithoutResult(status -> writer.prepareProject(session, project));
            tracker.advance(StageKey.PROJECTS, 1, project.source().name());
        }
        for (PlannedProject project : plan.projects()) {
            for (List<PlannedIssue> chunk : chunks(project.issues())) {
                if (stopRequested.contains(jobId)) {
                    return false;
                }
                transaction.executeWithoutResult(status -> writer.writeIssues(session, project, chunk));
                PlannedIssue last = chunk.get(chunk.size() - 1);
                tracker.advance(StageKey.ISSUES, chunk.size(), label(last));
            }
        }
        if (plan.options().comments()) {
            for (PlannedProject project : plan.projects()) {
                for (List<PlannedIssue> chunk : chunks(project.issues())) {
                    if (stopRequested.contains(jobId)) {
                        return false;
                    }
                    int before = session.report().comments;
                    transaction.executeWithoutResult(status -> writer.writeComments(session, chunk));
                    tracker.advance(StageKey.COMMENTS, session.report().comments - before, label(chunk.get(chunk.size() - 1)));
                }
            }
        }
        if (withAttachments && !attachments(jobId, session, tracker)) {
            return false;
        }
        transaction.executeWithoutResult(status -> writer.writeRelations(session));
        tracker.advance(StageKey.RELATIONS, linkTotal, null);
        return true;
    }

    /** @return {@code false} si la detuvieron a la mitad */
    private boolean attachments(UUID jobId, Session session, Tracker tracker) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        ImportSource source = sources.get(job.getSource());
        SourceConnection connection = credentials.get(jobId).orElse(null);
        boolean possible = source != null && connection != null && storage.enabled();
        for (PlannedProject project : session.plan().projects()) {
            for (PlannedIssue issue : project.issues()) {
                List<ExtAttachment> attachments = issue.source().attachments();
                if (attachments == null || attachments.isEmpty()) {
                    continue;
                }
                if (stopRequested.contains(jobId)) {
                    return false;
                }
                for (ExtAttachment attachment : attachments) {
                    if (!possible) {
                        writer.attachmentFailed(session, issue, attachment);
                    } else if (Boolean.TRUE.equals(transaction.execute(status -> writer.accepts(session, issue, attachment)))) {
                        try {
                            // La descarga va fuera de la transacción: puede tardar y no debe retener una conexión a la base
                            byte[] content = source.download(connection, attachment);
                            transaction.executeWithoutResult(status -> writer.writeAttachment(session, issue, attachment, content));
                        } catch (RuntimeException ex) {
                            log.warn("Importación {}: no se pudo traer el adjunto {} de {}: {}", jobId, attachment.name(),
                                    issue.source().key(), ex.toString());
                            writer.attachmentFailed(session, issue, attachment);
                        }
                    }
                    tracker.advance(StageKey.ATTACHMENTS, 1, label(issue));
                }
            }
        }
        return true;
    }

    private void finish(UUID jobId, Session session, ImportStatus result, String error) {
        stopRequested.remove(jobId);
        credentials.remove(jobId);
        try {
            if (session != null) {
                // Lo que quedó escrito, completo o no: el resumen de cada proyecto y las invitaciones
                transaction.executeWithoutResult(status -> session.plan().projects().stream()
                        .filter(project -> session.projects.containsKey(project.source().id()))
                        .forEach(project -> writer.finishProject(session, project)));
                if (result == ImportStatus.COMPLETED) {
                    writer.invitations(session).forEach((email, roleId) -> writer.invite(session, email, roleId));
                }
            }
        } catch (RuntimeException ex) {
            log.error("Importación {}: falló al cerrar", jobId, ex);
        }
        transaction.executeWithoutResult(status -> {
            ImportJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(result);
            job.setError(error);
            job.setFinishedAt(Instant.now());
            job.setProgress(null);
            if (session != null) {
                job.setReport(ImportJson.write(session.report()));
            }
            // Lo leído del origen ya no hace falta (y puede pesar): el informe es lo que queda
            store.deletePayloads(jobId);
            if (result != ImportStatus.STOPPED && job.getCreatedBy() != null) {
                notificationService.notifyImportFinished(job.getWorkspace(), job.getCreatedBy(), sourceName(job.getSource()),
                        result == ImportStatus.COMPLETED, session == null ? 0 : session.report().issues(),
                        session == null ? 0 : session.report().projects.size());
            }
        });
    }

    /** Cómo se nombra el origen en un aviso (en español; el aviso se traduce al leerlo). */
    private static String sourceName(ImportSourceType source) {
        return switch (source) {
            case JIRA -> "Jira";
            case TRELLO -> "Trello";
            case LINEAR -> "Linear";
            case ASANA -> "Asana";
            case CSV -> "un archivo CSV";
        };
    }

    private static String label(PlannedIssue issue) {
        String key = issue.source().key();
        return (key == null || key.isBlank() ? "" : key + " · ") + issue.source().title();
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += CHUNK) {
            chunks.add(items.subList(from, Math.min(items.size(), from + CHUNK)));
        }
        return chunks;
    }

    /** Lleva la cuenta de cada etapa y la guarda (como mucho una vez por segundo, y siempre al cambiar de etapa). */
    private final class Tracker {
        private final UUID jobId;
        private final Map<StageKey, int[]> stages = new EnumMap<>(StageKey.class);
        private long lastSaved;
        private StageKey lastStage;

        private Tracker(UUID jobId, int projects, int issues, int comments, int attachments, int links) {
            this.jobId = jobId;
            stages.put(StageKey.PROJECTS, new int[]{0, projects});
            stages.put(StageKey.ISSUES, new int[]{0, issues});
            stages.put(StageKey.COMMENTS, new int[]{0, comments});
            stages.put(StageKey.ATTACHMENTS, new int[]{0, attachments});
            stages.put(StageKey.RELATIONS, new int[]{0, links});
        }

        private void advance(StageKey stage, int count, String current) {
            stages.get(stage)[0] += count;
            long now = System.currentTimeMillis();
            if (stage != lastStage || now - lastSaved > 1000) {
                lastStage = stage;
                lastSaved = now;
                List<Stage> list = new ArrayList<>();
                stages.forEach((key, value) -> list.add(new Stage(key, value[0], value[1])));
                saveProgress(jobId, new ImportProgress(list, current));
            }
        }
    }

    private void saveProgress(UUID jobId, ImportProgress progress) {
        transaction.executeWithoutResult(status -> jobRepository.findById(jobId).ifPresent(job -> job.setProgress(ImportJson.write(progress))));
    }

    // ----- Deshacer

    /**
     * Borra lo que creó la importación y nada más: los proyectos que creó (con todo lo suyo) y, en los proyectos que
     * ya existían, las tareas, módulos y ciclos que agregó. Un estado o una etiqueta que agregó y que después se usó
     * en otra tarea se queda.
     */
    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void undo(UUID jobId, ImportStatus previous) {
        try {
            transaction.executeWithoutResult(status -> {
                store.deleteByIds("projects", store.items(jobId, ItemKind.PROJECT));
                store.deleteByIds("issues", store.items(jobId, ItemKind.ISSUE));
                store.deleteByIds("modules", store.items(jobId, ItemKind.MODULE));
                store.deleteByIds("cycles", store.items(jobId, ItemKind.CYCLE));
                store.deleteUnusedLabels(store.items(jobId, ItemKind.LABEL));
            });
            // Aparte: si un estado no se puede quitar (otra cosa del proyecto lo usa), lo demás ya quedó deshecho
            tolerate(() -> {
                transaction.executeWithoutResult(status -> store.deleteUnusedStates(store.items(jobId, ItemKind.STATE)));
                return null;
            });
            transaction.executeWithoutResult(status -> {
                ImportJob job = jobRepository.findById(jobId).orElseThrow();
                store.deleteItems(jobId);
                job.setStatus(ImportStatus.UNDONE);
                job.setError(null);
            });
        } catch (RuntimeException ex) {
            log.error("Importación {}: no se pudo deshacer", jobId, ex);
            transaction.executeWithoutResult(status -> {
                ImportJob job = jobRepository.findById(jobId).orElseThrow();
                job.setStatus(previous);
                job.setError("No se pudo deshacer la importación. Inténtalo de nuevo.");
            });
        }
    }

    private static void tolerate(Supplier<Void> step) {
        try {
            step.get();
        } catch (RuntimeException ex) {
            log.warn("Deshacer importación: un paso no se pudo completar: {}", ex.toString());
        }
    }
}
