package com.example.wallet.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The annotated username may contain only ASCII letters, digits and {@code . _ -} (leading and trailing spaces are trimmed by the service). Rejects look-alike, invisible and control characters. */
@Documented
@Constraint(validatedBy = Username.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Username {
    String message() default "may contain only letters, digits, ., _ and -";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link Username}; {@code null} is valid (combine with {@code @NotBlank}). */
    class Validator implements ConstraintValidator<Username, String> {
        @Override
        public boolean isValid(String s, ConstraintValidatorContext ctx) {
            return s == null || TextRules.isUsername(s);
        }
    }
}
