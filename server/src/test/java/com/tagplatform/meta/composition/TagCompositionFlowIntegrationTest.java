package com.tagplatform.meta.composition;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.ProfileView;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组合标签闭环（demo + H2）：创建草稿 → 预检 → 发布 → 画像查询期计算 → 更新规则 → 回滚。
 * 派生标签不落 tag_value_latest，画像中的值由查询期按规则实时计算。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_compose_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_compose_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TagCompositionFlowIntegrationTest {

    private static final String TENANT = "risk_control";
    private static final String CODE = "it_risk_combo";
    private static final String OBJECT_ID = "DRV97001";

    @Autowired
    private TagCompositionService compositionService;

    @Autowired
    private ProfileQueryService profileQueryService;

    @Autowired
    private TagValueStore tagValueStore;

    @Autowired
    private TagMetaRepository tagMetaRepository;

    @Autowired
    private TagCompositionRepository compositionRepository;

    @BeforeEach
    void setUp() {
        adminContext();
        tagValueStore.upsertLatest(latest("risk_level", "medium"));
        if (compositionService.list(null, null).stream().noneMatch(c -> CODE.equals(c.tagCode()))) {
            CompositionModels.SaveRequest req = new CompositionModels.SaveRequest(CODE, "组合风险(IT)",
                    "risk_score", "driver", TENANT, "boolean", "集成测试组合标签",
                    rule("in", "high,medium", "true"));
            compositionService.create(req);
        }
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void createPreviewPublishThenProfileComputesDerivedValue() {
        CompositionModels.CompositionView created = compositionService.detail(CODE);
        assertThat(created.status()).isEqualTo(TagComposition.STATUS_DRAFT);
        TagMeta meta = tagMetaRepository.findByCode(CODE).orElseThrow();
        assertThat(meta.tagType()).isEqualTo("derived");
        assertThat(meta.writable()).isFalse();
        assertThat(meta.status()).isEqualTo("offline");
        assertThat(meta.sensitivity()).isEqualTo("L2");

        CompositionModels.PreviewResponse preview = compositionService.preview(
                new CompositionModels.PreviewRequest(TENANT, "driver", OBJECT_ID, "boolean",
                        rule("in", "high,medium", "true")));
        assertThat(preview.matched()).isTrue();
        assertThat(preview.value()).isEqualTo("true");
        assertThat(preview.inputs()).hasSize(1);
        assertThat(preview.inputs().get(0).tagValue()).isEqualTo("medium");

        compositionService.publish(CODE);
        assertThat(compositionService.detail(CODE).status()).isEqualTo(TagComposition.STATUS_PUBLISHED);
        assertThat(tagMetaRepository.findByCode(CODE).orElseThrow().status()).isEqualTo("online");

        ProfileView profile = profileQueryService.profile(TENANT, "driver", OBJECT_ID, false);
        ProfileView.TagItem derived = profile.tags().stream()
                .filter(t -> CODE.equals(t.tagCode())).findFirst().orElseThrow();
        assertThat(derived.derived()).isTrue();
        assertThat(derived.tagValue()).isEqualTo("true");
        assertThat(derived.sourceMode()).isEqualTo("derived");

        // 规则收窄为仅 high：medium 对象不再命中
        compositionService.update(CODE, new CompositionModels.SaveRequest(CODE, "组合风险(IT)-v2",
                "risk_score", "driver", TENANT, "boolean", "规则收窄",
                rule("in", "high", "true")));
        assertThat(tagMetaRepository.findByCode(CODE).orElseThrow().version()).isEqualTo(2);
        assertThat(compositionService.preview(new CompositionModels.PreviewRequest(TENANT, "driver",
                OBJECT_ID, "boolean", rule("in", "high", "true"))).matched()).isFalse();
        assertThat(profileQueryService.profile(TENANT, "driver", OBJECT_ID, false).tags().stream()
                .noneMatch(t -> CODE.equals(t.tagCode()))).isTrue();

        // 回滚到 v1：重新命中
        compositionService.rollback(CODE, 1);
        assertThat(compositionService.preview(new CompositionModels.PreviewRequest(TENANT, "driver",
                OBJECT_ID, "boolean", rule("in", "high,medium", "true"))).matched()).isTrue();
        assertThat(profileQueryService.profile(TENANT, "driver", OBJECT_ID, false).tags().stream()
                .filter(t -> CODE.equals(t.tagCode())).findFirst().map(ProfileView.TagItem::tagValue))
                .contains("true");
    }

    @Test
    void usageReturnsCurrentLatestValues() {
        CompositionModels.UsageView usage = compositionService.usage("risk_level", TENANT);
        assertThat(usage.derived()).isFalse();
        assertThat(usage.currentTotal()).isGreaterThanOrEqualTo(1);
        assertThat(usage.currentValues()).anyMatch(v -> OBJECT_ID.equals(v.objectId()) && "medium".equals(v.tagValue()));

        compositionService.publish(CODE);
        CompositionModels.UsageView derivedUsage = compositionService.usage(CODE, TENANT);
        assertThat(derivedUsage.derived()).isTrue();
        assertThat(derivedUsage.evaluated()).isGreaterThanOrEqualTo(1);
        assertThat(derivedUsage.currentTotal()).isGreaterThanOrEqualTo(1);
        assertThat(derivedUsage.currentValues())
                .anyMatch(v -> OBJECT_ID.equals(v.objectId()) && "true".equals(v.tagValue())
                        && "effective".equals(v.status()));
    }

    @Test
    void deleteCompositionRejectsReferencesThenArchivesTag() {
        String base = "it_del_base";
        String referrer = "it_del_ref";
        compositionService.create(new CompositionModels.SaveRequest(base, "待删除组合(IT)", "risk_score",
                "driver", TENANT, "boolean", null, rule("in", "high,medium", "true")));
        compositionService.publish(base);
        compositionService.create(new CompositionModels.SaveRequest(referrer, "引用组合(IT)", "risk_score",
                "driver", TENANT, "boolean", null,
                new CompositionRule("AND", List.of(new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition(base, "is_true", null, null)))),
                        new CompositionRule.Output("fixed", "true", null, null, null), "hit_false")));

        assertThatThrownBy(() -> compositionService.delete(base))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("引用");
        assertThat(compositionRepository.find(base)).isPresent();

        compositionService.delete(referrer);
        compositionService.delete(base);
        assertThat(compositionRepository.find(base)).isEmpty();
        assertThat(tagMetaRepository.findByCode(base).orElseThrow().status()).isEqualTo("archived");
        assertThat(profileQueryService.profile(TENANT, "driver", OBJECT_ID, false).tags().stream()
                .noneMatch(t -> base.equals(t.tagCode()))).isTrue();
    }

    @Test
    void selfReferenceIsRejected() {
        assertThatThrownBy(() -> compositionService.update(CODE, new CompositionModels.SaveRequest(CODE,
                "组合风险(IT)", "risk_score", "driver", TENANT, "boolean", null,
                new CompositionRule("AND", List.of(new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition(CODE, "eq", "true", null)))),
                        new CompositionRule.Output("fixed", "true", null, null, null), "hit_false"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("自身");
    }

    @Test
    void tenantAdminCannotManageOtherTenant() {
        RequestContext.setConsoleUser("ta_other", "TENANT_ADMIN", "其他租户管理员", "system_a",
                "TA_OTHER", "其他租户管理员", "业务系统A", "业务一组");
        assertThatThrownBy(() -> compositionService.update(CODE, new CompositionModels.SaveRequest(CODE,
                "越权修改", "risk_score", "driver", TENANT, "boolean", null, rule("in", "high", "true"))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> compositionService.create(new CompositionModels.SaveRequest("it_other_combo",
                "越权创建", "risk_score", "driver", TENANT, "boolean", null, rule("in", "high", "true"))))
                .isInstanceOf(BizException.class);
    }

    private CompositionRule rule(String op, String value, String output) {
        return new CompositionRule("AND",
                List.of(new CompositionRule.ConditionGroup("AND", List.of(
                        new CompositionRule.Condition("risk_level", op, value, null)))),
                new CompositionRule.Output("fixed", output, null, null, null), "hit_false");
    }

    private TagValue latest(String tagCode, String value) {
        LocalDateTime now = LocalDateTime.now();
        return new TagValue(TENANT, "driver", OBJECT_ID, tagCode, value, "enum", TagValue.STATUS_EFFECTIVE,
                now, now, null, "OP_IT", "集成测试操作员", "human", "风控中心", "test", "高",
                null, null, null, null, null, "req-compose-it", now, 1L, null);
    }

    private void adminContext() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }
}
