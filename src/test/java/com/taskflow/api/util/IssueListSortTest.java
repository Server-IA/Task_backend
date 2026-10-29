package com.taskflow.api.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class IssueListSortTest {

    private static String describe(Pageable pageable) {
        return pageable.getSort().stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    @Test
    void porPrioridadAgregaFechaLimiteYNumero() {
        Pageable result = IssueListSort.withTieBreakers(PageRequest.of(2, 50, Sort.by("priorityRank")));

        assertThat(describe(result)).isEqualTo("priorityRank,asc dueDate,asc sequenceId,asc");
        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(50);
    }

    @Test
    void respetaLaDireccionYLosDesempatesQueYaVienen() {
        Pageable result = IssueListSort.withTieBreakers(PageRequest.of(0, 20,
                Sort.by(Sort.Order.desc("priorityRank"), Sort.Order.desc("dueDate"))));

        assertThat(describe(result)).isEqualTo("priorityRank,desc dueDate,desc sequenceId,asc");
    }

    @Test
    void cualquierOtroOrdenTerminaPorNumeroParaQueLasPaginasSeanEstables() {
        // sortOrder se repite entre columnas (65535 es el primero de cada una): sin desempate, una tarea podía salir
        // en dos páginas y otra en ninguna
        assertThat(describe(IssueListSort.withTieBreakers(PageRequest.of(1, 20, Sort.by("sortOrder")))))
                .isEqualTo("sortOrder,asc sequenceId,asc");
        assertThat(describe(IssueListSort.withTieBreakers(PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"))))))
                .isEqualTo("createdAt,desc sequenceId,asc");
        assertThat(describe(IssueListSort.withTieBreakers(PageRequest.of(0, 20)))).isEqualTo("sequenceId,asc");
    }

    @Test
    void siYaOrdenaPorNumeroNoLoRepite() {
        Pageable result = IssueListSort.withTieBreakers(PageRequest.of(0, 20, Sort.by(Sort.Order.desc("sequenceId"))));

        assertThat(describe(result)).isEqualTo("sequenceId,desc");
        assertThat(IssueListSort.withTieBreakers(Pageable.unpaged())).isEqualTo(Pageable.unpaged());
    }
}
