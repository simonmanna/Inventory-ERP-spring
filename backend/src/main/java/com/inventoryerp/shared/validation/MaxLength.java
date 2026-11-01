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
 * class-validator {@code @MaxLength(n)} on a required property: unlike {@code @Size}, a missing value fails too, so a
 * body without it answers "must be shorter than or equal to n characters" next to the {@code @IsString} message.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxLength.Validator.class)
public @interface MaxLength {

    int value();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link MaxLength}. */
    class Validator implements ConstraintValidator<MaxLength, CharSequence> {
        private int max;
        private String message;

        @Override
        public void initialize(MaxLength annotation) {
            this.max = annotation.value();
            this.message = annotation.message().isEmpty()
                    ? "must be shorter than or equal to " + max + " characters"
                    : annotation.message();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext ctx) {
            boolean ok = value != null && value.length() <= max;
            if (!ok) {
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            }
            return ok;
        }
    }
}
