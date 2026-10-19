package com.inventoryerp.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * class-validator {@code @IsIn([...])}: the value (when present) must be one of {@link #value()}.
 * The default message reproduces class-validator's "must be one of the following values: a, b".
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OneOf.Validator.class)
public @interface OneOf {

    String[] value();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link OneOf}. */
    class Validator implements ConstraintValidator<OneOf, Object> {
        private String[] allowed;
        private String message;

        @Override
        public void initialize(OneOf annotation) {
            this.allowed = annotation.value();
            this.message = annotation.message().isEmpty()
                    ? "must be one of the following values: " + String.join(", ", allowed)
                    : annotation.message();
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext ctx) {
            if (value == null) {
                return true;
            }
            boolean ok = Arrays.asList(allowed).contains(String.valueOf(value));
            if (!ok) {
                ctx.disableDefaultConstraintViolation();
                ctx.buildConstraintViolationWithTemplate(message.replace("{", "\\{")).addConstraintViolation();
            }
            return ok;
        }
    }
}
