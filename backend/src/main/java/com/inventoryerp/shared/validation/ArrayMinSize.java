package com.inventoryerp.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;

/**
 * class-validator {@code @ArrayMinSize(n)}: unlike {@code @Size}, a missing value fails too, so a body without the
 * array answers both "must contain at least n elements" and the {@code @IsArray} message, as Nest does.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ArrayMinSize.Validator.class)
public @interface ArrayMinSize {

    int value();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link ArrayMinSize}. */
    class Validator implements ConstraintValidator<ArrayMinSize, Collection<?>> {
        private int min;
        private String message;

        @Override
        public void initialize(ArrayMinSize annotation) {
            this.min = annotation.value();
            this.message = annotation.message().isEmpty()
                    ? "must contain at least " + min + " elements"
                    : annotation.message();
        }

        @Override
        public boolean isValid(Collection<?> value, ConstraintValidatorContext ctx) {
            boolean ok = value != null && value.size() >= min;
            if (!ok) {
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            }
            return ok;
        }
    }
}
