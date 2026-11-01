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
 * class-validator {@code @MinLength(n)} on a required property: unlike {@code @Size}, a missing value fails too, so a
 * body without it answers "must be longer than or equal to n characters" next to the {@code @IsString} message.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MinLength.Validator.class)
public @interface MinLength {

    int value();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link MinLength}. */
    class Validator implements ConstraintValidator<MinLength, CharSequence> {
        private int min;
        private String message;

        @Override
        public void initialize(MinLength annotation) {
            this.min = annotation.value();
            this.message = annotation.message().isEmpty()
                    ? "must be longer than or equal to " + min + " characters"
                    : annotation.message();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext ctx) {
            boolean ok = value != null && value.length() >= min;
            if (!ok) {
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            }
            return ok;
        }
    }
}
