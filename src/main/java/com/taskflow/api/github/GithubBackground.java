package com.taskflow.api.github;

import com.taskflow.api.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Lo que la integración hace fuera de la petición que lo provocó. Las llamadas a GitHub pueden tardar; hacerlas con una
 * transacción abierta retiene una conexión a la base de datos (y, en un aviso, la fila de la instalación) todo ese
 * tiempo, y una ráfaga de avisos dejaría a la aplicación sin conexiones. Por eso se hacen después de confirmar la
 * transacción, en un grupo de hilos propio, y lo que resulte se escribe en una transacción corta aparte.
 */
@Slf4j
@Component
public class GithubBackground {

    private final Executor executor;
    private final TransactionTemplate ownTransaction;

    public GithubBackground(@Qualifier(AsyncConfig.GITHUB_EXECUTOR) Executor executor, PlatformTransactionManager transactionManager) {
        this.executor = executor;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Ejecuta la tarea cuando la transacción en curso se confirme (si se deshace, no se ejecuta). Sin transacción, ya. */
    public void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(task);
            }
        });
    }

    /** Ejecuta la tarea en los hilos de la integración. Si falla queda en el registro: nadie la está esperando. */
    public void run(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.warn("GitHub: una tarea en segundo plano falló: {}", ex.getMessage(), ex);
            }
        });
    }

    /** Escribe en una transacción propia y corta. */
    public void write(Runnable work) {
        ownTransaction.executeWithoutResult(status -> work.run());
    }

    public <T> T write(Supplier<T> work) {
        return ownTransaction.execute(status -> work.get());
    }
}
