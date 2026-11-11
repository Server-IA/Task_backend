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
}
