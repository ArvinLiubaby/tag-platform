package com.tagplatform.meta.composition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 组合规则求值：纯函数口径（AND/OR、缺失策略、映射输出、数值与时间算子）。 */
class CompositionEvaluatorTest {

    private static CompositionRule rule(String logic, String missingPolicy, CompositionRule.Condition... conditions) {
        return new CompositionRule(logic,
                List.of(new CompositionRule.ConditionGroup("AND", List.of(conditions))),
                new CompositionRule.Output("fixed", "true", null, null, null), missingPolicy);
    }

    private static Map<String, String> values(String... kv) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void enumInMatchesAndOr() {
        CompositionEvaluator.Outcome hit = CompositionEvaluator.evaluate(
                rule("AND", "hit_false", new CompositionRule.Condition("driving_risk_attitude", "in", "激进,中性", null)),
                values("driving_risk_attitude", "中性"), Map.of("driving_risk_attitude", "驾驶风险倾向"));
        assertThat(hit.matched()).isTrue();
        assertThat(hit.value()).isEqualTo("true");
        assertThat(hit.trace()).hasSize(1);
        assertThat(hit.trace().get(0).actual()).isEqualTo("中性");
    }

    @Test
    void orAcrossGroups() {
        CompositionRule rule = new CompositionRule("OR", List.of(
                new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition("a", "eq", "x", null))),
                new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition("b", "eq", "y", null)))),
                new CompositionRule.Output("fixed", "ok", null, null, null), "hit_false");
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule, values("a", "no", "b", "y"), Map.of());
        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.value()).isEqualTo("ok");
    }

    @Test
    void missingValueFollowsPolicy() {
        CompositionRule unknown = rule("AND", "unknown",
                new CompositionRule.Condition("a", "eq", "x", null));
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(unknown, values(), Map.of());
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.status()).isEqualTo(CompositionEvaluator.STATUS_UNKNOWN);
        assertThat(outcome.missingTags()).containsExactly("a");

        CompositionRule hitFalse = rule("AND", "hit_false",
                new CompositionRule.Condition("a", "eq", "x", null));
        CompositionEvaluator.Outcome outcome2 = CompositionEvaluator.evaluate(hitFalse, values(), Map.of());
        assertThat(outcome2.matched()).isFalse();
        assertThat(outcome2.status()).isEqualTo(CompositionEvaluator.STATUS_NOT_MATCHED);
    }

    @Test
    void numericBetweenAndDateCompare() {
        CompositionRule rule = new CompositionRule("AND", List.of(
                new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition("fatigue", "between", "10", "20"),
                        new CompositionRule.Condition("last_active", "before", "2026-10-01", null)))),
                new CompositionRule.Output("fixed", "true", null, null, null), "hit_false");
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule,
                values("fatigue", "15.5", "last_active", "2026-09-20 08:00:00"), Map.of());
        assertThat(outcome.matched()).isTrue();
    }

    @Test
    void mappingOutputUsesSourceValue() {
        CompositionRule rule = new CompositionRule("AND", List.of(
                new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition("comm_attitude", "is_not_empty", null, null)))),
                new CompositionRule.Output("mapping", null, "comm_attitude", "未知",
                        List.of(new CompositionRule.Output.Mapping("友好", "A"),
                                new CompositionRule.Output.Mapping("恶劣", "C"))),
                "hit_false");
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule, values("comm_attitude", "友好"), Map.of());
        assertThat(outcome.value()).isEqualTo("A");

        CompositionEvaluator.Outcome fallback = CompositionEvaluator.evaluate(rule, values("comm_attitude", "一般"), Map.of());
        assertThat(fallback.value()).isEqualTo("未知");
    }

    @Test
    void isEmptyTreatsBlankAsMissing() {
        CompositionRule rule = rule("AND", "hit_false",
                new CompositionRule.Condition("phone_reachability", "is_empty", null, null));
        CompositionEvaluator.Outcome outcome = CompositionEvaluator.evaluate(rule, values(), Map.of());
        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.status()).isEqualTo(CompositionEvaluator.STATUS_NOT_MATCHED);
    }
}
