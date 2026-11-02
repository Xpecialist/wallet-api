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
import java.nio.charset.StandardCharsets;

/** The annotated string must be at most {@code value} bytes long in UTF-8 (BCrypt ignores everything past 72). */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {
    String message() default "must be at most {value} bytes in UTF-8";
    int value();
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /** Validator for {@link MaxUtf8Bytes}; {@code null} is valid (combine with {@code @NotBlank}). */
    class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {
        private int max;

        @Override
        public void initialize(MaxUtf8Bytes a) { this.max = a.value(); }

        @Override
        public boolean isValid(String s, ConstraintValidatorContext ctx) {
            return s == null || s.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
