package com.cep.audience.internal.rules;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Comparison operators of the rule language (JOURNEY_ENGINE_DESIGN §4). */
public enum Op {
    EQ, NEQ, GT, GTE, LT, LTE, IN, NIN, CONTAINS, EXISTS, REGEX, BETWEEN;

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Op> parse(String value) {
        return Arrays.stream(values()).filter(o -> o.json().equals(value)).findFirst();
    }
}
