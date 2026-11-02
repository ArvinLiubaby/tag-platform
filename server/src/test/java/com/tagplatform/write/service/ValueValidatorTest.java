package com.tagplatform.write.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.meta.model.TagMeta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueValidatorTest {

    private final ValueValidator validator = new ValueValidator(new ObjectMapper());

    private TagMeta tag(String valueType, String valueRange) {
        return new TagMeta(1L, "t_code", "测试标签", "cat", valueType, valueRange, false,
                "rule", false, 0, "risk_control", "L1", "online", 1, null, null, true, null, null);
    }

    private TagMeta multiTag(String valueRange) {
        return new TagMeta(1L, "t_code", "测试标签", "cat", "enum", valueRange, true,
                "rule", false, 0, "risk_control", "L1", "online", 1, null, null, true, null, null);
    }

    @Test
    void booleanAcceptsOnlyTrueFalse() {
        TagMeta t = tag("boolean", null);
        assertThat(validator.validate(t, "true")).isNull();
        assertThat(validator.validate(t, "false")).isNull();
        assertThat(validator.validate(t, "1")).isNotNull();
        assertThat(validator.validate(t, "TRUE")).isNotNull();
    }

    @Test
    void numberRangeEnforced() {
        TagMeta t = tag("number", "{\"min\":0,\"max\":100}");
        assertThat(validator.validate(t, "0")).isNull();
        assertThat(validator.validate(t, "100")).isNull();
        assertThat(validator.validate(t, "50.5")).isNull();
        assertThat(validator.validate(t, "-1")).isNotNull();
        assertThat(validator.validate(t, "101")).isNotNull();
        assertThat(validator.validate(t, "abc")).isNotNull();
    }

    @Test
    void numberWithoutRangeAcceptsAnyNumber() {
        TagMeta t = tag("number", null);
        assertThat(validator.validate(t, "-999.9")).isNull();
    }

    @Test
    void enumRestrictedToAllowedSet() {
        TagMeta t = tag("enum", "{\"enum\":[\"low\",\"medium\",\"high\"]}");
        assertThat(validator.validate(t, "high")).isNull();
        assertThat(validator.validate(t, "HIGH")).isNotNull();
        assertThat(validator.validate(t, "critical")).isNotNull();
    }

    @Test
    void enumWithoutRangeRejected() {
        assertThat(validator.validate(tag("enum", null), "anything")).isNotNull();
    }

    @Test
    void multiEnumAcceptsSubsetAndNormalizes() {
        TagMeta t = multiTag("{\"enum\":[\"公共道路\",\"港口\",\"矿区\",\"园区\",\"工地\",\"其他\"]}");
        assertThat(validator.validate(t, "公共道路,港口")).isNull();
        assertThat(validator.validate(t, "港口")).isNull();
        assertThat(validator.normalize(t, " 公共道路 , 港口 ")).isEqualTo("公共道路,港口");
        assertThat(validator.validate(t, "公共道路,沙漠")).isNotNull();
        assertThat(validator.validate(t, "港口,港口")).isNotNull();
        assertThat(validator.validate(t, "港口,")).isNotNull();
    }

    @Test
    void multiEnumRejectsMultipleAsSingle() {
        TagMeta single = tag("enum", "{\"enum\":[\"公共道路\",\"港口\"]}");
        assertThat(validator.validate(single, "公共道路,港口")).isNotNull();
    }

    @Test
    void multiOnlyForEnum() {
        TagMeta t = new TagMeta(1L, "t_code", "测试标签", "cat", "string", null, true,
                "rule", false, 0, "risk_control", "L1", "online", 1, null, null, true, null, null);
        assertThat(validator.validate(t, "a,b")).contains("仅 enum");
    }

    @Test
    void dateAcceptsTwoFormats() {
        TagMeta t = tag("date", null);
        assertThat(validator.validate(t, "2026-10-14")).isNull();
        assertThat(validator.validate(t, "2026-10-14 10:30:00")).isNull();
        assertThat(validator.validate(t, "14/10/2026")).isNotNull();
    }

    @Test
    void stringAcceptsAnything() {
        assertThat(validator.validate(tag("string", null), "任意文本 ok")).isNull();
    }

    @Test
    void blankAndOversizeRejected() {
        TagMeta t = tag("string", null);
        assertThat(validator.validate(t, null)).isNotNull();
        assertThat(validator.validate(t, "  ")).isNotNull();
        assertThat(validator.validate(t, "x".repeat(2049))).isNotNull();
        assertThat(validator.validate(t, "x".repeat(2048))).isNull();
    }

    @Test
    void unknownValueTypeRejected() {
        assertThat(validator.validate(tag("json", null), "{}")).isNotNull();
    }
}
