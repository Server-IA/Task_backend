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
    void otrosOrdenesNoCambian() {
        Pageable requested = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("dueDate")));

        assertThat(IssueListSort.withTieBreakers(requested)).isSameAs(requested);
        assertThat(IssueListSort.withTieBreakers(Pageable.unpaged())).isEqualTo(Pageable.unpaged());
    }
}
