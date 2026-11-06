package com.taskflow.api.dto.request;

import com.taskflow.api.util.VisibleText;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * El texto, si viene, tiene algo visible: no está hecho solo de espacios, tampoco de los que {@code @NotBlank} no
 * conoce (el duro U+00A0, los de ancho cero…; ver {@link VisibleText}). {@code null} es válido: en las ediciones
 * significa «no cambia»; donde es obligatorio, lo exige {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = Visible.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Visible {

    String message() default "No puede estar vacío";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Visible, CharSequence> {

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || !VisibleText.isBlank(value.toString());
        }
    }
}
