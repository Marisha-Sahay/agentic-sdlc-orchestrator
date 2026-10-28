package io.github.marishasahay.shortener.web;

/**
 * One invalid request field, listed in the {@code errors} property of a 400 problem.
 *
 * @param field the request field name
 * @param message why the value is invalid
 */
public record FieldViolation(String field, String message) {}
