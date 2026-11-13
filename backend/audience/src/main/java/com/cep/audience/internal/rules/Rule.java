package com.cep.audience.internal.rules;

import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** A compiled, validated rule tree. Rules are interpreted by {@link RuleEvaluator}; they never become code. */
public sealed interface Rule permits Rule.Group, Rule.Not, Rule.Condition {

    enum Kind {
        ALL, ANY;

        String json() { return this == ALL ? "all" : "any"; }
    }

    record Group(Kind kind, List<Rule> rules) implements Rule {
        public Group {
            rules = List.copyOf(rules);
        }
    }

    record Not(Rule rule) implements Rule {}

    /**
     * One comparison. {@code path} is {@code field} split on dots. {@code pattern} is pre-compiled for
     * {@link Op#REGEX} and null otherwise.
     */
    record Condition(String field, List<String> path, Op op, JsonNode value, Pattern pattern) implements Rule {
        public Condition {
            path = List.copyOf(path);
        }
    }
}
