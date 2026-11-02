package com.tagplatform.meta.composition;

import java.util.List;

/**
 * 组合规则（派生标签）：顶层逻辑 + 条件组（最多两层 DNF）+ 输出规则 + 缺失值策略。
 * 保存为 JSON 时字段为 snake_case（全局 Jackson 命名策略）。
 */
public record CompositionRule(
        String logic,
        List<ConditionGroup> groups,
        Output output,
        String missingPolicy) {

    public static final String LOGIC_AND = "AND";
    public static final String LOGIC_OR = "OR";
    public static final String MISSING_HIT_FALSE = "hit_false";
    public static final String MISSING_UNKNOWN = "unknown";

    public record ConditionGroup(String logic, List<Condition> conditions) {
    }

    /** valueTo 仅 between / 区间类算子使用；value 为枚举多值时用英文逗号分隔 */
    public record Condition(String tagCode, String op, String value, String valueTo) {
    }

    public record Output(String mode, String value, String sourceTagCode, String defaultValue,
                         List<Mapping> entries) {

        public static final String MODE_FIXED = "fixed";
        public static final String MODE_MAPPING = "mapping";

        public record Mapping(String from, String to) {
        }
    }
}
