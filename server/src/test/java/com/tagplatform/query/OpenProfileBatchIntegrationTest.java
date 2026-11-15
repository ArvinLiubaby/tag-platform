package com.tagplatform.query;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.ProfileBatchView;
import com.tagplatform.query.web.dto.ProfileView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开放 API · 批量对象画像（GET /api/v1/open/objects/profiles）：
 * 逗号分隔多值、按位置配对、不做笛卡尔积、重复去重、参数校验。
 * 与「任务待打标清单」的 object_type / object_id 语义保持一致。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_profile_batch_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_profile_batch_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class OpenProfileBatchIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private ProfileQueryService profileQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private ProfileBatchView batch(String objectType, String objectId) {
        return profileQueryService.profiles(TENANT, objectType, objectId, false);
    }

    @Test
    void sameTypeMultipleObjects() {
        ProfileBatchView page = batch("driver", "DRV85001,DRV85002");

        assertThat(page.tenantId()).isEqualTo(TENANT);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).hasSize(2);
        assertThat(page.records()).extracting(ProfileView::objectType)
                .containsExactly("driver", "driver");
        assertThat(page.records()).extracting(ProfileView::objectId)
                .containsExactly("DRV85001", "DRV85002");
        assertThat(page.records()).allSatisfy(record -> assertThat(record.tags()).isNotNull());
    }

    @Test
    void mixedTypesPairByPositionWithoutCartesianProduct() {
        ProfileBatchView page = batch("driver,vehicle", "DRV85001,苏A88801");

        assertThat(page.total()).isEqualTo(2);
        assertThat(pairs(page)).containsExactly(
                List.of("driver", "DRV85001"),
                List.of("vehicle", "苏A88801"));

        // 不做笛卡尔积：不产生 (vehicle, DRV85001) 与 (driver, 苏A88801) 这两条交叉组合
        assertThat(pairs(page)).doesNotContain(
                List.of("vehicle", "DRV85001"),
                List.of("driver", "苏A88801"));
    }

    @Test
    void duplicatePairsAreDeduplicated() {
        ProfileBatchView page = batch("driver", "DRV85001,DRV85001,DRV85002");

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).extracting(ProfileView::objectId)
                .containsExactly("DRV85001", "DRV85002");
    }

    @Test
    void mixedTypesCountMismatchIsRejected() {
        assertThatThrownBy(() -> batch("driver,vehicle", "DRV85001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一一对应");
        assertThatThrownBy(() -> batch("driver,vehicle,fleet", "DRV85001,苏A88801"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一一对应");
    }

    @Test
    void requiredParamsAreValidated() {
        assertThatThrownBy(() -> batch(null, "DRV85001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("object_type");
        assertThatThrownBy(() -> batch("driver", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("object_id");
        assertThatThrownBy(() -> batch("driver", "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("object_id");
    }

    @Test
    void unknownObjectTypeIsRejected() {
        assertThatThrownBy(() -> batch("unknown_type", "DRV85001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对象类型未注册");
    }

    @Test
    void batchRecordsKeepSingleProfileShape() {
        ProfileBatchView batch = batch("driver", "DRV85001");
        ProfileView single = profileQueryService.profile(TENANT, "driver", "DRV85001", false);

        assertThat(batch.records()).hasSize(1);
        ProfileView first = batch.records().get(0);
        assertThat(first.tenantId()).isEqualTo(single.tenantId());
        assertThat(first.objectType()).isEqualTo(single.objectType());
        assertThat(first.objectId()).isEqualTo(single.objectId());
        assertThat(first.tags()).usingRecursiveComparison().isEqualTo(single.tags());
    }

    @Test
    void includeEmptyReturnsStatusNonePlaceholders() {
        ProfileBatchView compact = profileQueryService.profiles(TENANT, "driver", "DRV85001",
                false, false, Set.of());
        ProfileBatchView full = profileQueryService.profiles(TENANT, "driver", "DRV85001",
                false, true, Set.of());

        assertThat(full.total()).isEqualTo(1);
        assertThat(compact.records().get(0).tags())
                .noneMatch(tag -> ProfileView.STATUS_NONE.equals(tag.status()));
        assertThat(full.records().get(0).tags())
                .anyMatch(tag -> ProfileView.STATUS_NONE.equals(tag.status()));
        assertThat(full.records().get(0).tags().size())
                .isGreaterThan(compact.records().get(0).tags().size());
    }

    @Test
    void includeEmptyPlaceholdersCarryUnit() {
        // demo seed 未包含 9 个统计指标标签，临时给一个在线 number 标签配单位，验证占位项也能带出
        jdbcTemplate.update("UPDATE tag_meta SET unit = '次' WHERE tag_code = 'alarm_count_30d'");
        try {
            ProfileBatchView page = profileQueryService.profiles(TENANT, "driver", "DRV85001",
                    false, true, Set.of());
            ProfileView.TagItem target = page.records().get(0).tags().stream()
                    .filter(tag -> "alarm_count_30d".equals(tag.tagCode()))
                    .findFirst().orElseThrow();
            assertThat(target.unit()).isEqualTo("次");
            assertThat(target.status()).isEqualTo(ProfileView.STATUS_NONE);
            assertThat(target.tagValue()).isNull();
        } finally {
            jdbcTemplate.update("UPDATE tag_meta SET unit = NULL WHERE tag_code = 'alarm_count_30d'");
        }
    }

    @Test
    void tagCodesFiltersValuesAndPlaceholders() {
        ProfileBatchView page = profileQueryService.profiles(TENANT, "driver,vehicle",
                "DRV85001,苏A88801", false, true, Set.of("driver_relation"));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).allSatisfy(record ->
                assertThat(record.tags()).extracting(ProfileView.TagItem::tagCode)
                        .allMatch(code -> code.equals("driver_relation")));
        // 占位范围也受 tag_codes 约束：不会把该对象的全部在线标签带出来
        assertThat(page.records().get(0).tags()).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void includeEmptyAppliesToEveryObject() {
        ProfileBatchView page = profileQueryService.profiles(TENANT, "driver",
                "DRV85001,DRV85002", false, true, Set.of());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).allSatisfy(record ->
                assertThat(record.tags()).anyMatch(
                        tag -> ProfileView.STATUS_NONE.equals(tag.status())));
    }

    @Test
    void dimInfoBackfilledFromDictionary() {
        // include_empty=false：只含有值标签；维度字段与 tag_category 一致（无维度的标签为 null）
        ProfileBatchView page = profileQueryService.profiles(TENANT, "driver", "DRV85001", false);
        assertThat(page.records().get(0).tags()).allSatisfy(tag -> {
            if (tag.dim1Code() != null) {
                String name = jdbcTemplate.queryForObject(
                        "SELECT category_name FROM tag_category WHERE category_code = ?",
                        String.class, tag.dim1Code());
                assertThat(tag.dim1Name()).isEqualTo(name);
            } else {
                assertThat(tag.dim1Name()).isNull();
            }
        });

        // include_empty=true：占位项同样回填维度（driver_age：baseline 人员基础属性 / demographic 人口特征）
        ProfileBatchView empty = profileQueryService.profiles(TENANT, "driver", "DRV85001", false, true, Set.of());
        ProfileView.TagItem age = empty.records().get(0).tags().stream()
                .filter(t -> "driver_age".equals(t.tagCode()))
                .findFirst().orElseThrow();
        assertThat(age.status()).isEqualTo(ProfileView.STATUS_NONE);
        assertThat(age.dim1Code()).isEqualTo("baseline");
        assertThat(age.dim1Name()).isEqualTo("人员基础属性");
        assertThat(age.dim2Code()).isEqualTo("demographic");
        assertThat(age.dim2Name()).isEqualTo("人口特征");
        assertThat(age.priority()).isEqualTo(4);
        assertThat(age.dim1Priority()).isEqualTo(5); // V9012 种子：baseline 维度优先级 = 5

        // dim1_priority 与 tag_ext_attr 的 DIM1 值一致
        Integer dbPriority = jdbcTemplate.queryForObject("""
                SELECT CAST(a.attr_value AS INT) FROM tag_ext_attr a
                 WHERE a.tenant_id = ? AND a.subject_type = 'DIM1'
                   AND a.subject_code = 'baseline' AND a.attr_key = 'dim1_priority'
                """, Integer.class, TENANT);
        assertThat(age.dim1Priority()).isEqualTo(dbPriority);
    }

    @Test
    void 任意格式的objectId都不再被格式校验拦截() {
        // 2026-11-11 起取消 id_pattern 校验：非空即可，不再因为「不像 DRVxxxxx」返回 2002
        ProfileBatchView page = batch("driver", "123");
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).objectId()).isEqualTo("123");
        assertThat(page.records().get(0).tags()).isEmpty(); // 无画像数据，是「空」而不是「报错」

        // 车辆同理：既不是车辆 ID 也不是车牌的任意值也放行
        ProfileBatchView vehicle = batch("vehicle", "随便一个编号-01");
        assertThat(vehicle.records()).hasSize(1);
        assertThat(vehicle.records().get(0).objectId()).isEqualTo("随便一个编号-01");
    }

    private static List<List<String>> pairs(ProfileBatchView page) {
        return page.records().stream()
                .map(record -> List.of(record.objectType(), record.objectId()))
                .toList();
    }
}
