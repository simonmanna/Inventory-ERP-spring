package com.inventoryerp.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * class-validator {@code @Length(min, max)} on a required property: unlike {@code @Size}, a missing value fails too,
 * so a body without it answers the length message next to the {@code @IsString} message.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Length.Validator.class)
public @interface Length {

    int min();

    int max();

    /** {@code @IsOptional()}: a missing value passes. */
    boolean nullable() default false;

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link Length}. */
    class Validator implements ConstraintValidator<Length, CharSequence> {
        private int min;
        private int max;
        private boolean nullable;
        private String message;

        @Override
        public void initialize(Length annotation) {
            this.min = annotation.min();
            this.max = annotation.max();
            this.nullable = annotation.nullable();
            this.message = annotation.message();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext ctx) {
            boolean ok = value == null ? nullable : value.length() >= min && value.length() <= max;
            if (!ok) {
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(message.isEmpty() ? defaultMessage(value) : message)
                        .addConstraintViolation();
            }
            return ok;
        }

        // class-validator's Length message: the bound that failed, or both when there is no value.
        private String defaultMessage(CharSequence value) {
            if (value != null && value.length() < min) {
                return "must be longer than or equal to " + min + " characters";
            }
            if (value != null && value.length() > max) {
                return "must be shorter than or equal to " + max + " characters";
            }
            return "must be longer than or equal to " + min + " and shorter than or equal to " + max + " characters";
        }
    }
}
