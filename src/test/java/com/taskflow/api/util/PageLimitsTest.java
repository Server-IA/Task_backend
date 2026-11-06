package com.taskflow.api.util;

import com.taskflow.api.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code ?page=99999999&size=200} respondía 500 (Spring Data: «Page offset exceeds Integer.MAX_VALUE»). */
class PageLimitsTest {

    @Test
    void unaPaginaDemasiadoLejanaEs400() {
        for (Pageable far : new Pageable[] {PageRequest.of(99_999_999, 200), PageRequest.of(Integer.MAX_VALUE, 2)}) {
            assertThatThrownBy(() -> PageLimits.reachable(far))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage(PageLimits.TOO_FAR);
        }
        assertThatThrownBy(() -> IssueListSort.withTieBreakers(PageRequest.of(99_999_999, 200, Sort.by("sortOrder"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void lasDemasPasanIgual() {
        Pageable last = PageRequest.of(Integer.MAX_VALUE / 200, 200);
        assertThat(PageLimits.reachable(last)).isSameAs(last);
        assertThat(PageLimits.reachable(Pageable.unpaged())).isEqualTo(Pageable.unpaged());
    }
}
