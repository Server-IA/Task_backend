package com.taskflow.api.exception;

import com.taskflow.api.dto.response.DescriptionConflictResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.UserSummaryResponse;
import com.taskflow.api.dto.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
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

    @Test
    void unCuerpoQueNoEsJsonEs415YNo500() {
        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void unAcceptSinJsonEs406() {
        assertThat(handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    }

    @Test
    void unaCabeceraQueFaltaEs400() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("conCabecera", String.class), 0);

        ResponseEntity<ErrorResponse> response =
                handler.handleBinding(new MissingRequestHeaderException("X-Algo", parameter));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("X-Algo");
    }

    @SuppressWarnings("unused")
    private void conCabecera(String header) {
    }

    @Test
    void otrasExcepcionesDeSpringConEstado4xxNoSon500() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void demasiadosIntentosLlevaRetryAfter() {
        ResponseEntity<ErrorResponse> response = handler.handleApi(new TooManyRequestsException(42));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
    }

    @Test
    void laViolacionDeIntegridadSoloRegistraElNombreDeLaRestriccion() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicado",
                new org.hibernate.exception.ConstraintViolationException(
                        "ERROR: duplicate key value violates unique constraint; Key (email)=(ana@test.com)",
                        new java.sql.SQLException("Key (email)=(ana@test.com) already exists"), "uk_users_email"));

        assertThat(GlobalExceptionHandler.constraintName(ex)).isEqualTo("uk_users_email").doesNotContain("@");
        assertThat(handler.handleDataIntegrity(ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
