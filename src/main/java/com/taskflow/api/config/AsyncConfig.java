package com.taskflow.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Habilita {@code @Async} con un pool propio para el envío de correos, y las tareas programadas
 * ({@code MaintenanceJobs}).
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String MAIL_EXECUTOR = "mailExecutor";
    public static final String IMPORT_EXECUTOR = "importExecutor";
    public static final String GITHUB_EXECUTOR = "githubExecutor";
    public static final String REALTIME_EXECUTOR = "realtimeExecutor";

    @Bean(name = MAIL_EXECUTOR)
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        // Con la cola llena (el proveedor de correo caído o lento) el correo se descarta y se registra: quien lo pidió
        // (el login, el registro, una invitación) no falla por eso.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Cola de correos llena ({} en espera): se descarta un correo", pool.getQueue().size()));
        executor.initialize();
        return executor;
    }

    /**
     * Las importaciones desde otras herramientas: leer el origen, escribir en TaskFlow y deshacer. Pocas a la vez (son
     * largas y escriben mucho); las demás esperan en la cola.
     */
    @Bean(name = IMPORT_EXECUTOR)
    public Executor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("import-");
        executor.initialize();
        return executor;
    }

    /**
     * Lo que la integración con GitHub hace después de atender un aviso o de completarse una tarea (contar los checks
     * de un commit, ejecutar el workflow de una regla): llama a GitHub, así que va fuera de la petición y de su
     * transacción. Con {@code app.github.inline=true} se ejecuta en el mismo hilo (para las pruebas).
     */
    @Bean(name = GITHUB_EXECUTOR)
    public Executor githubExecutor(@org.springframework.beans.factory.annotation.Value("${app.github.inline:false}") boolean inline) {
        if (inline) {
            return new org.springframework.core.task.SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("github-");
        // Con la cola llena (GitHub caído o una ráfaga enorme de avisos) la tarea se descarta: lo siguiente que pase en
        // ese pull request la vuelve a pedir
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Cola de GitHub llena ({} en espera): se descarta una tarea", pool.getQueue().size()));
        executor.initialize();
        return executor;
    }

    /**
     * Reparte los avisos en tiempo real ({@code RealtimeHub}) fuera de la petición que hizo el cambio. Un solo hilo:
     * los avisos salen en el orden en que pasaron. Con {@code app.realtime.inline=true} van en el mismo hilo (pruebas).
     */
    @Bean(name = REALTIME_EXECUTOR)
    public Executor realtimeExecutor(@org.springframework.beans.factory.annotation.Value("${app.realtime.inline:false}") boolean inline) {
        if (inline) {
            return new org.springframework.core.task.SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("realtime-");
        // Con la cola llena se pierde un aviso: la pestaña lo recupera al volver al frente (refresca igual)
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Cola de avisos en tiempo real llena ({} en espera): se descarta uno", pool.getQueue().size()));
        executor.initialize();
        return executor;
    }
}
