package com.cep.audience.internal.rules;

import com.cep.audience.internal.rules.Rule.Condition;
import com.cep.audience.internal.rules.Rule.Group;
import com.cep.audience.internal.rules.Rule.Kind;
import com.cep.audience.internal.rules.Rule.Not;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates rule JSON and compiles it to a {@link Rule} tree plus a normalized JSON form.
 *
 * <p>Accepted input:
 * <ul>
 *   <li>Full form: {@code {"all":[...]}}, {@code {"any":[...]}}, {@code {"not":{...}}} and conditions
 *       {@code {"field":"customer.attributes.city","op":"in","value":["Singapore"]}}.</li>
 *   <li>Shorthand: {@code {"customerStatus":"ACTIVE","tier":["GOLD","SILVER"],"age":{"gte":18}}}. Each key is an
 *       attribute ({@code customer.attributes.<key>}) or a full dotted path. A scalar means {@code eq}, an array
 *       means {@code in}, and an object maps operator names to values. Keys are combined with {@code all}.</li>
 * </ul>
 * Group and condition elements may themselves use shorthand. The normalized form always uses the full form
 * with a group at the root, so it can be compiled again without change.
 */
public final class RuleCompiler {
    public static final int MAX_DEPTH = 10;
    public static final int MAX_CONDITIONS = 200;
    public static final int MAX_LIST_SIZE = 1000;
    public static final int MAX_REGEX_LENGTH = 200;
    public static final int MAX_PATH_SEGMENTS = 10;

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> GROUP_KEYS = Set.of("all", "any", "not");
    private static final Set<String> CONDITION_KEYS = Set.of("field", "op", "value");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Set<String> roots;
    private final String shorthandPrefix;

    public RuleCompiler(Set<String> roots, String shorthandPrefix) {
        this.roots = Set.copyOf(roots);
        this.shorthandPrefix = shorthandPrefix;
    }

    /** Audience criteria may reference the customer profile and the triggering event. */
    public static RuleCompiler forAudiences() {
        return new RuleCompiler(Set.of("customer", "event"), "customer.attributes.");
    }

    public record Issue(String path, String message) {}

    public record Compiled(Rule rule, JsonNode normalized) {}

    public static final class InvalidRuleException extends RuntimeException {
        private final transient List<Issue> issues;

        InvalidRuleException(List<Issue> issues) {
            super("Invalid rule: " + issues);
            this.issues = List.copyOf(issues);
        }

        public List<Issue> issues() { return issues; }
    }

    private static final class Ctx {
        final List<Issue> issues = new ArrayList<>();
        int conditions;
        boolean tooMany;

        void issue(String path, String message) {
            issues.add(new Issue(path, message));
        }
    }

    public Compiled compile(JsonNode content) {
        Ctx ctx = new Ctx();
        Rule rule = null;
        if (content == null || !content.isObject() || content.isEmpty()) {
            ctx.issue("$", "must be a non-empty JSON object");
        } else {
            rule = node(content, "$", 1, ctx);
        }
        if (!ctx.issues.isEmpty() || rule == null) {
            throw new InvalidRuleException(ctx.issues.isEmpty() ? List.of(new Issue("$", "invalid rule")) : ctx.issues);
        }
        Rule root = rule instanceof Group ? rule : new Group(Kind.ALL, List.of(rule));
        return new Compiled(root, toJson(root));
    }

    private Rule node(JsonNode n, String path, int depth, Ctx ctx) {
        if (depth > MAX_DEPTH) {
            ctx.issue(path, "nesting is deeper than " + MAX_DEPTH + " levels");
            return null;
        }
        if (n == null || !n.isObject() || n.isEmpty()) {
            ctx.issue(path, "must be a non-empty object");
            return null;
        }
        List<String> keys = new ArrayList<>(n.propertyNames());
        boolean hasGroupKey = keys.stream().anyMatch(GROUP_KEYS::contains);
        if (hasGroupKey) {
            if (keys.size() != 1) {
                ctx.issue(path, "'all', 'any' or 'not' must be the only key in its object");
                return null;
            }
            String key = keys.get(0);
            JsonNode value = n.get(key);
            if (key.equals("not")) {
                Rule inner = node(value, path + ".not", depth + 1, ctx);
                return inner == null ? null : new Not(inner);
            }
            return group(key.equals("all") ? Kind.ALL : Kind.ANY, value, path + "." + key, depth, ctx);
        }
        if (n.has("field") || n.has("op")) {
            return condition(n, path, ctx);
        }
        return shorthand(n, path, depth, ctx);
    }

    private Rule group(Kind kind, JsonNode items, String path, int depth, Ctx ctx) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            ctx.issue(path, "must be a non-empty array");
            return null;
        }
        if (items.size() > MAX_CONDITIONS) {
            ctx.issue(path, "has more than " + MAX_CONDITIONS + " elements");
            return null;
        }
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Rule r = node(items.get(i), path + "[" + i + "]", depth + 1, ctx);
            if (r != null) {
                rules.add(r);
            }
        }
        return rules.size() == items.size() ? new Group(kind, rules) : null;
    }

    private Rule condition(JsonNode n, String path, Ctx ctx) {
        int before = ctx.issues.size();
        for (String key : n.propertyNames()) {
            if (!CONDITION_KEYS.contains(key)) {
                ctx.issue(path + "." + key, "unknown key; a condition has only 'field', 'op' and 'value'");
            }
        }
        JsonNode field = n.get("field");
        JsonNode op = n.get("op");
        String fieldPath = null;
        if (field == null || !field.isString()) {
            ctx.issue(path + ".field", "is required and must be a string");
        } else {
            fieldPath = checkPath(field.stringValue(), path + ".field", ctx);
        }
        Op parsed = null;
        if (op == null || !op.isString()) {
            ctx.issue(path + ".op", "is required and must be a string");
        } else {
            parsed = Op.parse(op.stringValue()).orElse(null);
            if (parsed == null) {
                ctx.issue(path + ".op", "unknown operator; allowed: " + allowedOps());
            }
        }
        JsonNode value = n.get("value");
        if (value == null && parsed == Op.EXISTS) {
            value = NODES.booleanNode(true);
        }
        if (value == null && parsed != null) {
            ctx.issue(path + ".value", "is required for '" + parsed.json() + "'");
        }
        if (ctx.issues.size() > before || fieldPath == null || parsed == null || value == null) {
            return null;
        }
        return make(fieldPath, parsed, value, path + ".value", ctx);
    }

    private Rule shorthand(JsonNode n, String path, int depth, Ctx ctx) {
        List<Rule> rules = new ArrayList<>();
        boolean ok = true;
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            String keyPath = path + "." + e.getKey();
            String field = e.getKey().contains(".") ? checkPath(e.getKey(), keyPath, ctx)
                    : ATTRIBUTE.matcher(e.getKey()).matches() ? shorthandPrefix + e.getKey() : null;
            if (field == null) {
                if (!e.getKey().contains(".")) {
                    ctx.issue(keyPath, "is not a valid attribute name");
                }
                ok = false;
                continue;
            }
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                ctx.issue(keyPath, "null is not allowed; use {\"exists\": false}");
                ok = false;
            } else if (v.isArray()) {
                ok &= add(rules, make(field, Op.IN, v, keyPath, ctx));
            } else if (v.isObject()) {
                if (v.isEmpty()) {
                    ctx.issue(keyPath, "must contain at least one operator");
                    ok = false;
                }
                for (Map.Entry<String, JsonNode> o : v.properties()) {
                    Optional<Op> op = Op.parse(o.getKey());
                    if (op.isEmpty()) {
                        ctx.issue(keyPath + "." + o.getKey(), "unknown operator; allowed: " + allowedOps());
                        ok = false;
                    } else {
                        ok &= add(rules, make(field, op.get(), o.getValue(), keyPath + "." + o.getKey(), ctx));
                    }
                }
            } else {
                ok &= add(rules, make(field, Op.EQ, v, keyPath, ctx));
            }
        }
        if (!ok || rules.isEmpty()) {
            return null;
        }
        return rules.size() == 1 ? rules.get(0) : new Group(Kind.ALL, rules);
    }

    private static boolean add(List<Rule> rules, Rule r) {
        if (r == null) {
            return false;
        }
        rules.add(r);
        return true;
    }

    private String checkPath(String field, String path, Ctx ctx) {
        if (!PATH.matcher(field).matches()) {
            ctx.issue(path, "must be a dotted path such as customer.attributes.city");
            return null;
        }
        String[] segments = field.split("\\.");
        if (segments.length < 2 || segments.length > MAX_PATH_SEGMENTS) {
            ctx.issue(path, "must have between 2 and " + MAX_PATH_SEGMENTS + " segments");
            return null;
        }
        if (!roots.contains(segments[0])) {
            ctx.issue(path, "must start with one of " + roots.stream().sorted().toList());
            return null;
        }
        return field;
    }

    private Rule make(String field, Op op, JsonNode value, String path, Ctx ctx) {
        if (++ctx.conditions > MAX_CONDITIONS) {
            if (!ctx.tooMany) {
                ctx.tooMany = true;
                ctx.issue("$", "has more than " + MAX_CONDITIONS + " conditions");
            }
            return null;
        }
        String problem = switch (op) {
            case EQ, NEQ, CONTAINS -> Values.isScalar(value) ? null : "must be a string, number or boolean";
            case GT, GTE, LT, LTE -> Values.isOrderable(value) ? null : "must be a number or an ISO-8601 date";
            case IN, NIN -> list(value);
            case EXISTS -> value.isBoolean() ? null : "must be true or false";
            case REGEX -> regex(value);
            case BETWEEN -> between(value);
        };
        if (problem != null) {
            ctx.issue(path, problem);
            return null;
        }
        return new Condition(field, Arrays.asList(field.split("\\.")), op, value.deepCopy(),
                op == Op.REGEX ? Pattern.compile(value.stringValue()) : null);
    }

    private static String list(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            return "must be a non-empty array";
        }
        if (value.size() > MAX_LIST_SIZE) {
            return "has more than " + MAX_LIST_SIZE + " values";
        }
        for (JsonNode item : value) {
            if (!Values.isScalar(item)) {
                return "may only contain strings, numbers or booleans";
            }
        }
        return null;
    }

    private static String regex(JsonNode value) {
        if (!value.isString() || value.stringValue().isEmpty()) {
            return "must be a non-empty string";
        }
        if (value.stringValue().length() > MAX_REGEX_LENGTH) {
            return "is longer than " + MAX_REGEX_LENGTH + " characters";
        }
        try {
            Pattern.compile(value.stringValue());
            return null;
        } catch (PatternSyntaxException e) {
            return "is not a valid regular expression: " + e.getDescription();
        }
    }

    private static String between(JsonNode value) {
        if (!value.isArray() || value.size() != 2) {
            return "must be an array of two values [low, high]";
        }
        JsonNode lo = value.get(0);
        JsonNode hi = value.get(1);
        boolean numbers = lo.isNumber() && hi.isNumber();
        boolean dates = Values.temporal(lo) != null && Values.temporal(hi) != null;
        if (!numbers && !dates) {
            return "bounds must both be numbers or both be ISO-8601 dates";
        }
        return Values.compare(lo, hi) > 0 ? "low bound is greater than high bound" : null;
    }

    private static String allowedOps() {
        return Arrays.stream(Op.values()).map(Op::json).toList().toString();
    }

    static JsonNode toJson(Rule rule) {
        return switch (rule) {
            case Group g -> {
                ObjectNode o = NODES.objectNode();
                ArrayNode items = o.putArray(g.kind().json());
                g.rules().forEach(r -> items.add(toJson(r)));
                yield o;
            }
            case Not n -> {
                ObjectNode o = NODES.objectNode();
                o.set("not", toJson(n.rule()));
                yield o;
            }
            case Condition c -> {
                ObjectNode o = NODES.objectNode();
                o.put("field", c.field());
                o.put("op", c.op().json());
                o.set("value", c.value());
                yield o;
            }
        };
    }
}
