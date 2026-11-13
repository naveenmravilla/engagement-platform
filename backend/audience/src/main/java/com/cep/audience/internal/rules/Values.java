package com.cep.audience.internal.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/**
 * Typed comparison of JSON values. Numbers compare numerically, ISO-8601 dates and timestamps compare
 * chronologically, strings and booleans compare exactly. Values of different types are never equal and
 * never ordered; there is no implicit conversion (the string "10" is not the number 10).
 */
final class Values {
    private Values() {}

    static boolean isScalar(JsonNode n) {
        return n != null && (n.isString() || n.isNumber() || n.isBoolean());
    }

    static boolean equal(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isString() && b.isString()) {
            return a.stringValue().equals(b.stringValue());
        }
        if (a.isBoolean() && b.isBoolean()) {
            return a.booleanValue() == b.booleanValue();
        }
        return false;
    }

    /** @return the ordering of {@code a} relative to {@code b}, or null when they are not comparable. */
    static Integer compare(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return null;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        Instant ta = temporal(a);
        Instant tb = temporal(b);
        return ta != null && tb != null ? Integer.valueOf(ta.compareTo(tb)) : null;
    }

    static boolean isOrderable(JsonNode n) {
        return n != null && (n.isNumber() || temporal(n) != null);
    }

    /** Parses an ISO-8601 instant, offset date-time or date (start of day UTC); null if it is none of those. */
    static Instant temporal(JsonNode n) {
        if (n == null || !n.isString()) {
            return null;
        }
        String s = n.stringValue();
        if (s.length() < 10 || s.length() > 40 || !Character.isDigit(s.charAt(0))) {
            return null;
        }
        try {
            return s.length() == 10 ? LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
