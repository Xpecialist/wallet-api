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

/** The annotated text must be plain: no control characters (including newline and tab), invisible format characters (zero-width, soft hyphen, bidi overrides) or line separators. Plain spaces are allowed. */
@Documented
@Constraint(validatedBy = PlainText.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {
    String message() default "must not contain control or invisible formatting characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link PlainText}; {@code null} is valid (combine with {@code @NotBlank}). */
    class Validator implements ConstraintValidator<PlainText, String> {
        @Override
        public boolean isValid(String s, ConstraintValidatorContext ctx) {
            return s == null || TextRules.isPlain(s);
        }
    }
}
