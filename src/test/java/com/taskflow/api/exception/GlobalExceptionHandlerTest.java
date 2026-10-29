package com.taskflow.api.exception;

import com.taskflow.api.dto.response.DescriptionConflictResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void elConflictoDeDescripcionTraeLaVersionActualQuienYCuando() {
        UserSummaryResponse luis = new UserSummaryResponse(UUID.randomUUID(), "luis@test.com", "Luis Muñoz", null);
        Instant at = Instant.parse("2026-10-29T10:15:00Z");
        IssueResponse current = new IssueResponse(UUID.randomUUID(), "WEB-4", 4, UUID.randomUUID(), null, null, "Título",
                "Lo que guardó Luis", 7, at, luis, null, null, null, null, null, 0, List.of(), List.of(), null,
                List.of(), 0, 0, 0, List.of(), List.of(), 0, 0, 0, 0, null, null, null, null, null, null, 0, 0, 0, 0, 0,
                false, null, null);

        ResponseEntity<DescriptionConflictResponse> response =
                handler.handleDescriptionConflict(new DescriptionConflictException(current));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        DescriptionConflictResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.code()).isEqualTo("DESCRIPTION_CONFLICT");
        assertThat(body.message()).isEqualTo(DescriptionConflictException.MESSAGE);
        assertThat(body.description()).isEqualTo("Lo que guardó Luis");
        assertThat(body.descriptionVersion()).isEqualTo(7);
        assertThat(body.descriptionUpdatedBy()).isEqualTo(luis);
        assertThat(body.descriptionUpdatedAt()).isEqualTo(at);
        assertThat(body.issue()).isSameAs(current);
        assertThat(body.timestamp()).isNotNull();
    }
}
