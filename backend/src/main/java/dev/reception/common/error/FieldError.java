package dev.reception.common.error;

/** One entry in the {@code errors} array of a validation problem+json body. */
public record FieldError(String field, String message) {}
