package com.taskflow.api.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BCrypt admite hasta 72 bytes: una contraseña de 72 caracteres con «ñ» o emojis se rechaza con 400 (antes daba 500).
 * Los correos tienen el largo de la columna (255).
 */
class PasswordValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<String> invalidFields(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void setentaYDosCaracteresConEñesSuperanLos72Bytes() {
        String multibyte = "ñ".repeat(60) + "abc123456789";   // 72 caracteres, 132 bytes
        String ascii = "a".repeat(60) + "abc123456789";       // 72 caracteres, 72 bytes
        assertThat(multibyte).hasSize(72);

        assertThat(invalidFields(new RegisterRequest("ana@test.com", multibyte, "Ana"))).containsExactly("password");
        assertThat(invalidFields(new ResetPasswordRequest("tok", multibyte))).containsExactly("newPassword");
        assertThat(invalidFields(new ChangePasswordRequest("actual", multibyte))).containsExactly("newPassword");
        assertThat(invalidFields(new RegisterRequest("ana@test.com", ascii, "Ana"))).isEmpty();
    }

    /** QA: « ana@test.com » con espacios alrededor se acepta (antes, 400 «El correo no es válido»). */
    @Test
    void losCorreosSeRecortanAntesDeValidar() {
        RegisterRequest register = new RegisterRequest("  ana@test.com ", "Clave1234", "Ana");

        assertThat(register.email()).isEqualTo("ana@test.com");
        assertThat(invalidFields(register)).isEmpty();
        assertThat(invalidFields(new EmailRequest(" ana@test.com	"))).isEmpty();
        assertThat(invalidFields(new InviteMemberRequest(" luis@test.com "))).isEmpty();
        assertThat(invalidFields(new RegisterRequest("   ", "Clave1234", "Ana"))).contains("email");
        assertThat(new RegisterRequest(null, "Clave1234", "Ana").email()).isNull();
    }

    @Test
    void losCorreosNoSuperan255Caracteres() {
        String longEmail = "a".repeat(250) + "@test.com";

        assertThat(invalidFields(new InviteMemberRequest(longEmail))).contains("email");
        assertThat(invalidFields(new LoginRequest(longEmail, "Clave1234"))).contains("email");
        assertThat(invalidFields(new EmailRequest(longEmail))).contains("email");
    }
}
