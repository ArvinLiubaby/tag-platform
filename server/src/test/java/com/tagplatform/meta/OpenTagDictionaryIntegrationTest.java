package com.tagplatform.meta;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.web.OpenTagController;
import com.tagplatform.meta.web.OpenTagController.TagView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开放 API · 标签字典（GET /api/v1/open/tags）：
 * 一级/二级维度中文名称（dim1_name / dim2_name，按 tag_category 实时解析）
 * 与维度展示优先级（dim1_priority，DIM1 扩展属性，与 ext.dim1_priority 同源）。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_open_tags_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_open_tags_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class OpenTagDictionaryIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private OpenTagController openTagController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // 控制器内部取 RequestContext.getTenantId()（ctx.tenantId），与 setConsoleUser 的 consoleTenantId 是两个字段
        RequestContext.setTenantId(TENANT);
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @SuppressWarnings("unchecked")
    private List<TagView> tags() {
        ApiResponse<Map<String, Object>> resp = openTagController.tags(null, null, null);
        assertThat(resp.code()).isZero();
        return (List<TagView>) resp.data().get("tags");
    }

    @Test
    void dimNamesResolvedFromCategory() {
        List<TagView> tags = tags();
        assertThat(tags).isNotEmpty();

        // 有 dim1 的标签：dim1_name 与 tag_category 的名称一致且非空
        TagView sample = tags.stream()
                .filter(t -> t.dim1Code() != null)
                .findFirst().orElseThrow();
        String dim1Name = jdbcTemplate.queryForObject(
                "SELECT category_name FROM tag_category WHERE category_code = ?",
                String.class, sample.dim1Code());
        assertThat(dim1Name).isNotBlank();
        assertThat(sample.dim1Name()).isEqualTo(dim1Name);

        // dim2 有值时同样回填
        if (sample.dim2Code() != null) {
            String dim2Name = jdbcTemplate.queryForObject(
                    "SELECT category_name FROM tag_category WHERE category_code = ?",
                    String.class, sample.dim2Code());
            assertThat(sample.dim2Name()).isEqualTo(dim2Name);
        }

        // 同一维度的所有标签 dim1_name 一致
        tags.stream()
                .filter(t -> sample.dim1Code().equals(t.dim1Code()))
                .forEach(t -> assertThat(t.dim1Name()).isEqualTo(dim1Name));
    }

    @Test
    void dim1PriorityMatchesExtAndCategory() {
        List<TagView> tags = tags();

        // V9012 种子给 5 个一级维度都配了优先级：至少一个标签 dim1_priority 非 null
        List<TagView> withPriority = tags.stream()
                .filter(t -> t.dim1Priority() != null)
                .toList();
        assertThat(withPriority).isNotEmpty();

        // dim1_priority 与 ext.dim1_priority 同源（均为该一级维度的 DIM1 优先级）
        TagView sample = withPriority.get(0);
        assertThat(String.valueOf(sample.dim1Priority()))
                .isEqualTo(sample.ext().get("dim1_priority"));
        // 数值与库中 DIM1 扩展属性一致
        Integer dbPriority = jdbcTemplate.queryForObject("""
                SELECT CAST(a.attr_value AS INT) FROM tag_ext_attr a
                 WHERE a.tenant_id = ? AND a.subject_type = 'DIM1'
                   AND a.subject_code = ? AND a.attr_key = 'dim1_priority'
                """, Integer.class, TENANT, sample.dim1Code());
        assertThat(sample.dim1Priority()).isEqualTo(dbPriority);

        // ext 中未回落出 dim1_priority 的标签，顶级字段同样为 null
        tags.stream()
                .filter(t -> !t.ext().containsKey("dim1_priority"))
                .forEach(t -> assertThat(t.dim1Priority()).isNull());
    }
}
