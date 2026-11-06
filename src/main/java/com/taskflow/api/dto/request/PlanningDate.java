package com.taskflow.api.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

/**
 * Una fecha de planificación (inicio o fecha límite de una tarea, fechas de un ciclo o de un módulo) entre
 * {@link #MIN} y {@link #MAX}. Antes se aceptaba cualquiera: un año mal escrito (0026 en vez de 2026) dejaba un ciclo
 * «en curso» de dos mil años cuyo burndown (un punto por día) llenaba las estadísticas con decenas de MB, y una fecha que
 * PostgreSQL no admite (+999999999-12-31) respondía «el texto tiene caracteres no permitidos». {@code null} es válido.
 */
@Documented
@Constraint(validatedBy = PlanningDate.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlanningDate {

    LocalDate MIN = LocalDate.of(1900, 1, 1);
    LocalDate MAX = LocalDate.of(2999, 12, 31);
    String MESSAGE = "La fecha debe estar entre el año 1900 y el 2999";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PlanningDate, LocalDate> {

        /** {@code date} es {@code null} o está entre {@link #MIN} y {@link #MAX}. */
        public static boolean inRange(LocalDate date) {
            return date == null || !date.isBefore(MIN) && !date.isAfter(MAX);
        }

        @Override
        public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
            return inRange(value);
        }
    }
}
