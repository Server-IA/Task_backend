package com.taskflow.api.realtime;

import com.taskflow.api.config.AsyncConfig;
import com.taskflow.api.repository.WorkspaceRepository;
import com.taskflow.api.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * Avisos en tiempo real: cada pestaña abierta de TaskFlow tiene una conexión ({@code GET /api/v1/events}, Server-Sent
 * Events) y aquí se le cuenta qué cambió para que vuelva a pedir lo que está mostrando. Los avisos llevan solo
 * identificadores (el workspace, el proyecto, la tarea), nunca datos: lo que se ve se sigue pidiendo a la API con sus
 * permisos de siempre.
 * <ul>
 *   <li>Un cambio en un proyecto llega a quien puede ver ese proyecto.</li>
 *   <li>Un cambio del workspace entero (GitHub, una importación, los miembros) llega a quien es del workspace.</li>
 *   <li>Una notificación llega solo a su destinatario (en todas sus pestañas).</li>
 * </ul>
 * Se avisa después del commit (antes, la otra pestaña podría leer lo viejo) y desde un hilo propio: quien hizo el
 * cambio no espera a que se repartan los avisos. Las conexiones viven en memoria: con una sola instancia de la API
 * basta; si un día hay varias, esto pasa a un canal compartido (p. ej. LISTEN/NOTIFY de PostgreSQL).
 */
@Slf4j
@Component
public class RealtimeHub implements SmartLifecycle {

    /** Una conexión dura esto y el navegador vuelve a conectarse (con su token al día). */
    static final Duration CONNECTION = Duration.ofMinutes(30);
    /** Cuánto se recuerda si alguien puede ver un proyecto (o es de un workspace) antes de volver a mirarlo. */
    static final Duration ACCESS_TTL = Duration.ofSeconds(60);
    /** Conexiones abiertas por persona como mucho (pestañas); la más vieja se cierra al abrir otra. */
    static final int MAX_PER_USER = 10;

    private final WorkspaceSecurity security;
    private final WorkspaceRepository workspaceRepository;
    private final Executor executor;
    private final Clock clock = Clock.systemUTC();

    private final Map<UUID, Set<Connection>> connections = new ConcurrentHashMap<>();
    private final Map<String, Remembered> access = new ConcurrentHashMap<>();

    public RealtimeHub(WorkspaceSecurity security, WorkspaceRepository workspaceRepository,
                       @Qualifier(AsyncConfig.REALTIME_EXECUTOR) Executor executor) {
        this.security = security;
        this.workspaceRepository = workspaceRepository;
        this.executor = executor;
    }

    /** Lo que cambió. {@code projectId} nulo: el workspace entero; {@code slug} nulo: solo las notificaciones. */
    public record Change(String slug, UUID projectId, UUID issueId, String origin) {

        Map<String, Object> payload() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (slug != null) {
                data.put("workspace", slug);
            }
            if (projectId != null) {
                data.put("projectId", projectId);
            }
            if (issueId != null) {
                data.put("issueId", issueId);
            }
            if (origin != null) {
                data.put("origin", origin);
            }
            return data;
        }
    }

    private record Connection(UUID userId, SseEmitter emitter) {
    }

    private record Remembered(boolean allowed, long until) {
    }

    // ---------------------------------------------------------------- conexiones

    /** Abre la conexión de una pestaña. El primer aviso ({@code ready}) confirma que quedó abierta. */
    public SseEmitter connect(UUID userId) {
        SseEmitter emitter = new SseEmitter(CONNECTION.toMillis());
        Connection connection = new Connection(userId, emitter);
        // compute: abrir y cerrar a la vez la misma persona no pierde ninguna conexión
        Set<Connection> own = connections.compute(userId, (id, current) -> {
            Set<Connection> set = current == null ? new CopyOnWriteArraySet<>() : current;
            set.add(connection);
            return set;
        });
        if (own.size() > MAX_PER_USER) {
            own.stream().findFirst().filter(oldest -> oldest != connection).ifPresent(this::close);
        }
        emitter.onCompletion(() -> forget(connection));
        emitter.onTimeout(() -> close(connection));
        emitter.onError(error -> forget(connection));
        send(connection, SseEmitter.event().name("ready").data(Map.of()));
        return emitter;
    }

    /** Pestañas abiertas en total (para las pruebas y el registro). */
    public int open() {
        return connections.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Un comentario cada 25 s: mantiene viva la conexión a través de nginx y Traefik (que cortan lo que lleva un
     * minuto callado) y descubre las pestañas que ya se cerraron.
     */
    @Scheduled(fixedDelayString = "${app.realtime.heartbeat:PT25S}", initialDelayString = "${app.realtime.heartbeat:PT25S}")
    public void heartbeat() {
        for (Set<Connection> own : connections.values()) {
            for (Connection connection : own) {
                send(connection, SseEmitter.event().comment("latido"));
            }
        }
    }

    // Al apagar (un despliegue), las conexiones se cierran ANTES de que Tomcat espere a las peticiones en curso: si no,
    // el apagado esperaría a que cada pestaña colgara. El navegador vuelve a conectarse al servidor nuevo.

    private volatile boolean running;

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        connections.values().forEach(own -> own.forEach(this::close));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** La fase más alta: se detiene primero, antes del apagado ordenado del servidor web. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    // ---------------------------------------------------------------- avisos

    /** Cambió algo de un proyecto (y, si se sabe, de qué tarea). {@code origin}: la pestaña que lo hizo. */
    public void project(String slug, UUID projectId, UUID issueId, String origin) {
        afterCommit(new Change(slug, projectId, issueId, origin));
    }

    /** Cambió algo del workspace entero (miembros, GitHub, una importación…). */
    public void workspace(String slug, String origin) {
        afterCommit(new Change(slug, null, null, origin));
    }

    /** Igual, cuando solo se conoce el identificador del workspace (los avisos de GitHub, las importaciones). */
    public void workspace(UUID workspaceId) {
        afterCommit(() -> workspaceRepository.findById(workspaceId)
                .ifPresent(workspace -> deliver(new Change(workspace.getSlug(), null, null, null))));
    }

    /** Hay algo nuevo en la bandeja de esa persona (o cambió desde otra de sus pestañas). */
    public void notifications(UUID userId, String origin) {
        Change change = new Change(null, null, null, origin);
        afterCommit(() -> deliverTo(userId, change));
    }

    private void afterCommit(Change change) {
        afterCommit(() -> deliver(change));
    }

    private void afterCommit(Runnable task) {
        Runnable later = () -> {
            try {
                executor.execute(task);
            } catch (RuntimeException ex) {
                // La cola llena: se pierde un aviso; la próxima vez que esa pestaña vuelva al frente lo pide igual
                log.debug("Tiempo real: se descarta un aviso ({})", ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    later.run();
                }
            });
        } else {
            later.run();
        }
    }

    /** Reparte un cambio entre quienes pueden verlo. */
    void deliver(Change change) {
        for (Map.Entry<UUID, Set<Connection>> entry : connections.entrySet()) {
            if (!entry.getValue().isEmpty() && allowed(entry.getKey(), change)) {
                for (Connection connection : entry.getValue()) {
                    send(connection, event(change));
                }
            }
        }
    }

    private void deliverTo(UUID userId, Change change) {
        for (Connection connection : connections.getOrDefault(userId, Set.of())) {
            send(connection, SseEmitter.event().name("notifications").data(change.payload(), MediaType.APPLICATION_JSON));
        }
    }

    private static SseEmitter.SseEventBuilder event(Change change) {
        return SseEmitter.event().name("change").data(change.payload(), MediaType.APPLICATION_JSON);
    }

    private boolean allowed(UUID userId, Change change) {
        String key = userId + "|" + change.slug() + "|" + change.projectId();
        long now = clock.millis();
        Remembered remembered = access.get(key);
        if (remembered != null && remembered.until() > now) {
            return remembered.allowed();
        }
        boolean allowed;
        try {
            allowed = change.projectId() == null
                    ? security.isMember(userId, change.slug())
                    : security.canAccessProject(userId, change.slug(), change.projectId());
        } catch (RuntimeException ex) {
            log.debug("Tiempo real: no se pudo comprobar el acceso de {}: {}", userId, ex.getMessage());
            return false;
        }
        access.put(key, new Remembered(allowed, now + ACCESS_TTL.toMillis()));
        if (access.size() > 10_000) {
            access.values().removeIf(old -> old.until() <= now);
        }
        return allowed;
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        try {
            connection.emitter().send(event);
        } catch (IOException | IllegalStateException ex) {
            // La pestaña se cerró (o la conexión ya terminó): se olvida
            forget(connection);
        }
    }

    private void close(Connection connection) {
        forget(connection);
        try {
            connection.emitter().complete();
        } catch (RuntimeException ignored) {
            // Ya estaba cerrada
        }
    }

    private void forget(Connection connection) {
        connections.computeIfPresent(connection.userId(), (id, own) -> {
            own.remove(connection);
            return own.isEmpty() ? null : own;
        });
    }

    /** Para las pruebas: las personas con alguna pestaña conectada. */
    List<UUID> connectedUsers() {
        return List.copyOf(connections.keySet());
    }
}
