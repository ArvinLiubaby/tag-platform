package com.tagplatform.meta.composition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组合规则求值：纯函数，输入"标签编码 → 当前值"快照，输出结果与逐条判定轨迹。
 * 不做 IO、不读库，便于单测与查询期复用。
 */
public final class CompositionEvaluator {

    public static final Set<String> OPS = Set.of(
            "eq", "ne", "in", "not_in",
            "gt", "gte", "lt", "lte", "between",
            "contains", "not_contains",
            "before", "after",
            "is_true", "is_false",
            "is_empty", "is_not_empty");

    public static final String STATUS_EFFECTIVE = "effective";
    public static final String STATUS_NOT_MATCHED = "not_matched";
    public static final String STATUS_UNKNOWN = "none";

    private CompositionEvaluator() {
    }

    public record Trace(String tagCode, String tagName, String op, String expected,
                        String actual, boolean matched, boolean missing) {
    }

    public record Outcome(String value, boolean matched, String status, List<Trace> trace,
                          List<String> missingTags) {
    }

    /**
     * @param values   参与计算的标签当前值（含派生标签已算出的值）
     * @param tagNames 标签编码 → 名称，用于展示轨迹
     */
    public static Outcome evaluate(CompositionRule rule, Map<String, String> values, Map<String, String> tagNames) {
        List<Trace> trace = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        boolean matched = evalTop(rule, values, tagNames, trace, missing);

        boolean hasMissing = !missing.isEmpty();
        if (hasMissing && CompositionRule.MISSING_UNKNOWN.equalsIgnoreCase(rule.missingPolicy())) {
            return new Outcome(null, false, STATUS_UNKNOWN, trace, new ArrayList<>(missing));
        }
        if (!matched) {
            return new Outcome(null, false, STATUS_NOT_MATCHED, trace, new ArrayList<>(missing));
        }
        String value = resolveOutput(rule.output(), values);
        return new Outcome(value, true, STATUS_EFFECTIVE, trace, new ArrayList<>(missing));
    }

    private static boolean evalTop(CompositionRule rule, Map<String, String> values, Map<String, String> names,
                                   List<Trace> trace, Set<String> missing) {
        List<CompositionRule.ConditionGroup> groups = rule.groups() == null ? List.of() : rule.groups();
        if (groups.isEmpty()) {
            return false;
        }
        boolean and = !CompositionRule.LOGIC_OR.equalsIgnoreCase(rule.logic());
        boolean result = and;
        for (CompositionRule.ConditionGroup group : groups) {
            boolean g = evalGroup(group, values, names, trace, missing);
            result = and ? (result && g) : (result || g);
        }
        return result;
    }

    private static boolean evalGroup(CompositionRule.ConditionGroup group, Map<String, String> values,
                                     Map<String, String> names, List<Trace> trace, Set<String> missing) {
        List<CompositionRule.Condition> conditions = group.conditions() == null ? List.of() : group.conditions();
        if (conditions.isEmpty()) {
            return false;
        }
        boolean and = !CompositionRule.LOGIC_OR.equalsIgnoreCase(group.logic());
        boolean result = and;
        for (CompositionRule.Condition c : conditions) {
            String actual = values.get(c.tagCode());
            boolean missingValue = actual == null || actual.isEmpty();
            boolean matched = !missingValue && compare(c, actual);
            if (missingValue) {
                missing.add(c.tagCode());
            }
            trace.add(new Trace(c.tagCode(), names.get(c.tagCode()), c.op(), expectedText(c), actual,
                    matched, missingValue));
            result = and ? (result && matched) : (result || matched);
        }
        return result;
    }

    private static boolean compare(CompositionRule.Condition c, String actual) {
        String op = c.op() == null ? "" : c.op().toLowerCase();
        String expected = c.value() == null ? "" : c.value().trim();
        String a = actual.trim();
        switch (op) {
            case "is_empty":
                return a.isEmpty();
            case "is_not_empty":
                return !a.isEmpty();
            case "eq":
                return a.equalsIgnoreCase(expected);
            case "ne":
                return !a.equalsIgnoreCase(expected);
            case "in":
                return inList(a, expected);
            case "not_in":
                return !inList(a, expected);
            case "contains":
                return a.contains(expected);
            case "not_contains":
                return !a.contains(expected);
            case "is_true":
                return "true".equalsIgnoreCase(a) || "1".equals(a);
            case "is_false":
                return "false".equalsIgnoreCase(a) || "0".equals(a);
            case "gt":
                return num(a) != null && num(expected) != null && num(a) > num(expected);
            case "gte":
                return num(a) != null && num(expected) != null && num(a) >= num(expected);
            case "lt":
                return num(a) != null && num(expected) != null && num(a) < num(expected);
            case "lte":
                return num(a) != null && num(expected) != null && num(a) <= num(expected);
            case "between": {
                if (num(a) == null || num(expected) == null || num(c.valueTo()) == null) {
                    return false;
                }
                double lo = Math.min(num(expected), num(c.valueTo()));
                double hi = Math.max(num(expected), num(c.valueTo()));
                double v = num(a);
                return v >= lo && v <= hi;
            }
            case "before":
                return timeOf(a) != null && timeOf(expected) != null && timeOf(a).compareTo(timeOf(expected)) < 0;
            case "after":
                return timeOf(a) != null && timeOf(expected) != null && timeOf(a).compareTo(timeOf(expected)) > 0;
            default:
                return false;
        }
    }

    private static boolean inList(String actual, String expected) {
        for (String token : expected.split(",")) {
            if (token.trim().equalsIgnoreCase(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String expectedText(CompositionRule.Condition c) {
        if ("between".equalsIgnoreCase(c.op())) {
            return (c.value() == null ? "" : c.value()) + " ~ " + (c.valueTo() == null ? "" : c.valueTo());
        }
        return c.value();
    }

    private static String resolveOutput(CompositionRule.Output output, Map<String, String> values) {
        if (output == null) {
            return null;
        }
        if (CompositionRule.Output.MODE_MAPPING.equalsIgnoreCase(output.mode())) {
            String source = values.get(output.sourceTagCode());
            if (source != null && output.entries() != null) {
                for (CompositionRule.Output.Mapping m : output.entries()) {
                    if (m != null && m.from() != null && m.from().trim().equalsIgnoreCase(source.trim())) {
                        return m.to();
                    }
                }
            }
            return output.defaultValue();
        }
        return output.value();
    }

    private static Double num(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ISO 日期/时间统一成可比较字符串：yyyy-MM-dd HH:mm:ss（仅有日期时补零点） */
    private static String timeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().replace('T', ' ');
        if (v.length() == 10) {
            v = v + " 00:00:00";
        } else if (v.length() == 16) {
            v = v + ":00";
        }
        return v.length() == 19 ? v : null;
    }
}
