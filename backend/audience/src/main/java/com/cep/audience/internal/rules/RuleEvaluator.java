package com.cep.audience.internal.rules;

import com.cep.audience.internal.rules.Rule.Condition;
import com.cep.audience.internal.rules.Rule.Group;
import com.cep.audience.internal.rules.Rule.Kind;
import com.cep.audience.internal.rules.Rule.Not;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Evaluates a compiled {@link Rule} against a JSON context such as
 * {@code {"customer":{"attributes":{...}},"event":{...}}}.
 *
 * <p>Every condition is evaluated (no short-circuit) so the caller can see which ones matched and which
 * failed. A missing or null field fails every operator except {@code exists}; {@code neq} and {@code nin}
 * also fail on a missing field, so "not equal to X" never matches customers who lack the attribute.
 */
public final class RuleEvaluator {
    /** Upper bound on characters a regex may inspect, against catastrophic backtracking. */
    static final int REGEX_STEP_BUDGET = 100_000;
    static final int MAX_REGEX_INPUT = 10_000;

    private RuleEvaluator() {}

    public record Leaf(String path, String field, String op, JsonNode value) {}

    public record Outcome(boolean member, List<Leaf> matched, List<Leaf> failed) {}

    public static Outcome evaluate(Rule root, JsonNode context) {
        List<Leaf> matched = new ArrayList<>();
        List<Leaf> failed = new ArrayList<>();
        boolean member = eval(root, "$", context, matched, failed);
        return new Outcome(member, List.copyOf(matched), List.copyOf(failed));
    }

    private static boolean eval(Rule rule, String path, JsonNode ctx, List<Leaf> matched, List<Leaf> failed) {
        return switch (rule) {
            case Group g -> {
                String key = g.kind() == Kind.ALL ? "all" : "any";
                boolean all = true;
                boolean any = false;
                for (int i = 0; i < g.rules().size(); i++) {
                    boolean r = eval(g.rules().get(i), path + "." + key + "[" + i + "]", ctx, matched, failed);
                    all &= r;
                    any |= r;
                }
                yield g.kind() == Kind.ALL ? all : any;
            }
            case Not n -> !eval(n.rule(), path + ".not", ctx, matched, failed);
            case Condition c -> {
                boolean r = test(c, resolve(ctx, c.path()));
                (r ? matched : failed).add(new Leaf(path, c.field(), c.op().json(), c.value()));
                yield r;
            }
        };
    }

    static JsonNode resolve(JsonNode ctx, List<String> path) {
        JsonNode node = ctx;
        for (String segment : path) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
        }
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    static boolean test(Condition c, JsonNode actual) {
        JsonNode v = c.value();
        if (c.op() == Op.EXISTS) {
            return (actual != null) == v.booleanValue();
        }
        if (actual == null) {
            return false;
        }
        return switch (c.op()) {
            case EQ -> Values.equal(actual, v);
            case NEQ -> !Values.equal(actual, v);
            case GT -> cmp(actual, v) > 0;
            case GTE -> cmp(actual, v) >= 0;
            case LT -> cmp(actual, v) < 0 && cmp(actual, v) != Integer.MIN_VALUE;
            case LTE -> cmp(actual, v) <= 0 && cmp(actual, v) != Integer.MIN_VALUE;
            case IN -> contains(v, actual);
            case NIN -> !contains(v, actual);
            case CONTAINS -> actual.isArray() ? contains(actual, v)
                    : actual.isString() && v.isString() && actual.stringValue().contains(v.stringValue());
            case REGEX -> actual.isString() && regex(c, actual.stringValue());
            case BETWEEN -> {
                int lo = cmp(actual, v.get(0));
                int hi = cmp(actual, v.get(1));
                yield lo != Integer.MIN_VALUE && hi != Integer.MIN_VALUE && lo >= 0 && hi <= 0;
            }
            case EXISTS -> throw new IllegalStateException("handled above");
        };
    }

    /** {@link Integer#MIN_VALUE} when the values are not comparable, so every ordering test fails. */
    private static int cmp(JsonNode a, JsonNode b) {
        Integer r = Values.compare(a, b);
        return r == null ? Integer.MIN_VALUE : Integer.signum(r);
    }

    private static boolean contains(JsonNode array, JsonNode item) {
        for (JsonNode element : array) {
            if (Values.equal(element, item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regex(Condition c, String input) {
        if (input.length() > MAX_REGEX_INPUT) {
            return false;
        }
        try {
            return c.pattern().matcher(new BudgetedChars(input, new int[] {REGEX_STEP_BUDGET})).find();
        } catch (BudgetExceeded e) {
            return false;
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** CharSequence that fails after a fixed number of reads, which bounds regex backtracking. */
    private record BudgetedChars(CharSequence text, int[] budget) implements CharSequence {
        @Override
        public char charAt(int index) {
            if (--budget[0] < 0) {
                throw new BudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() { return text.length(); }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedChars(text.subSequence(start, end), budget);
        }

        @Override
        public String toString() { return text.toString(); }
    }
}
