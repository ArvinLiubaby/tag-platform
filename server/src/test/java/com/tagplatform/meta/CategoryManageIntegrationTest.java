package com.tagplatform.meta;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.repo.TagMetaRepository.CategoryInfo;
import com.tagplatform.meta.service.TagMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 维度管理（tag_category）：新增 / 编辑 / 删除 + 引用安全校验
 * （编码格式与唯一性、父级必须为一级、有下级不可改父级、一级不可降级、被标签引用不可删除）。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_category_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_category_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class CategoryManageIntegrationTest {

    @Autowired
    private TagMetaService tagMetaService;

    @Autowired
    private TagMetaRepository tagMetaRepository;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void admin() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", null,
                "admin", "平台管理员", "平台", null);
    }

    private CategoryInfo create(String code, String name, String parent, Integer sort) {
        return tagMetaService.createCategory(
                new TagMetaService.CategoryCreateRequest(code, name, parent, sort));
    }

    @Test
    void createTopAndSubCategories() {
        admin();
        CategoryInfo top = create("dim_test_top", "测试一级维度", null, 900);
        assertThat(top.parentCode()).isNull();
        assertThat(top.sortNo()).isEqualTo(900);

        // 二级维度默认排序 = 同级最大排序号 +10
        CategoryInfo sub = create("dim_test_sub", "测试二级维度", "dim_test_top", null);
        assertThat(sub.parentCode()).isEqualTo("dim_test_top");
        assertThat(sub.sortNo()).isEqualTo(10); // 同级无兄弟，默认 0+10

        // 编码重复 → 拒绝
        assertThatThrownBy(() -> create("dim_test_top", "重复编码", null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        // 父级是二级维度 → 拒绝（只允许挂在一维维度下）
        assertThatThrownBy(() -> create("dim_test_x", "挂在二级下", "dim_test_sub", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        // 父级不存在 → 拒绝
        assertThatThrownBy(() -> create("dim_test_y", "挂在不存在维度", "dim_not_exist", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        // 非法编码 → 拒绝
        assertThatThrownBy(() -> create("Bad-Code", "非法编码", null, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void updateCategoryRules() {
        admin();
        create("dim_upd_top_a", "更新测试甲", null, 920);
        create("dim_upd_top_b", "更新测试乙", null, 930);
        create("dim_upd_sub", "更新测试子", "dim_upd_top_a", null);

        // 改名 + 排序
        CategoryInfo renamed = tagMetaService.updateCategory("dim_upd_sub",
                new TagMetaService.CategoryUpdateRequest("更新测试子·改名", 5));
        assertThat(renamed.categoryName()).isEqualTo("更新测试子·改名");
        assertThat(renamed.sortNo()).isEqualTo(5);

        // 二级迁移到另一个一级
        CategoryInfo moved = tagMetaService.updateCategory("dim_upd_sub",
                new TagMetaService.CategoryUpdateRequest(null, "dim_upd_top_b", true, null));
        assertThat(moved.parentCode()).isEqualTo("dim_upd_top_b");

        // 二级升级为一级（parentCode 显式传 null）
        CategoryInfo promoted = tagMetaService.updateCategory("dim_upd_sub",
                new TagMetaService.CategoryUpdateRequest(null, null, true, null));
        assertThat(promoted.parentCode()).isNull();

        // 一级降级为二级 → 拒绝
        assertThatThrownBy(() -> tagMetaService.updateCategory("dim_upd_sub",
                new TagMetaService.CategoryUpdateRequest(null, "dim_upd_top_a", true, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 有下级的一级改父级 → 拒绝（避免出现三级）
        create("dim_upd_sub2", "更新测试子二", "dim_upd_top_a", null);
        assertThatThrownBy(() -> tagMetaService.updateCategory("dim_upd_top_a",
                new TagMetaService.CategoryUpdateRequest(null, "dim_upd_top_b", true, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 不存在的维度 → 拒绝
        assertThatThrownBy(() -> tagMetaService.updateCategory("dim_not_exist",
                new TagMetaService.CategoryUpdateRequest("名称", null, false, null)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void batchSaveCreatesUpdatesAndDeletesInOneShot() {
        admin();
        // 同批新增：一级 + 其下二级（一级先建，二级才能挂上）
        tagMetaService.applyCategoryChanges(new TagMetaService.CategoryBatchRequest(
                List.of(
                        new TagMetaService.CategoryCreateRequest("dim_batch_top", "批量一级", null, 950),
                        new TagMetaService.CategoryCreateRequest("dim_batch_sub", "批量二级", "dim_batch_top", null)),
                List.of(),
                List.of()));
        assertThat(tagMetaRepository.findCategory("dim_batch_top")).isPresent();
        assertThat(tagMetaRepository.findCategory("dim_batch_sub").orElseThrow().parentCode())
                .isEqualTo("dim_batch_top");

        // 同批：二级改名 + 改排序，一级调排序，同时再新增一个一级
        tagMetaService.applyCategoryChanges(new TagMetaService.CategoryBatchRequest(
                List.of(new TagMetaService.CategoryCreateRequest("dim_batch_top2", "批量一级二", null, 960)),
                List.of(new TagMetaService.CategoryBatchUpdate("dim_batch_sub", "批量二级·改名", 7, null, false),
                        new TagMetaService.CategoryBatchUpdate("dim_batch_top", null, 951, null, false)),
                List.of()));
        assertThat(tagMetaRepository.findCategory("dim_batch_sub").orElseThrow().categoryName())
                .isEqualTo("批量二级·改名");
        assertThat(tagMetaRepository.findCategory("dim_batch_sub").orElseThrow().sortNo()).isEqualTo(7);
        assertThat(tagMetaRepository.findCategory("dim_batch_top").orElseThrow().sortNo()).isEqualTo(951);

        // 二级迁移到另一个一级（parent_changed=true）
        tagMetaService.applyCategoryChanges(new TagMetaService.CategoryBatchRequest(List.of(),
                List.of(new TagMetaService.CategoryBatchUpdate("dim_batch_sub", null, null, "dim_batch_top2", true)),
                List.of()));
        assertThat(tagMetaRepository.findCategory("dim_batch_sub").orElseThrow().parentCode())
                .isEqualTo("dim_batch_top2");

        // 同批删除父子：内部先删子再删父，一次成功
        tagMetaService.applyCategoryChanges(new TagMetaService.CategoryBatchRequest(List.of(), List.of(),
                List.of("dim_batch_top2", "dim_batch_sub")));
        assertThat(tagMetaRepository.findCategory("dim_batch_sub")).isEmpty();
        assertThat(tagMetaRepository.findCategory("dim_batch_top2")).isEmpty();
    }

    @Test
    void batchSaveRollsBackOnInvalidItem() {
        admin();
        // 新增先执行成功，随后更新不存在的维度 → 异常，整个批次回滚（新增不落库）
        assertThatThrownBy(() -> tagMetaService.applyCategoryChanges(
                new TagMetaService.CategoryBatchRequest(
                        List.of(new TagMetaService.CategoryCreateRequest("dim_rb_new", "回滚新增", null, 971)),
                        List.of(new TagMetaService.CategoryBatchUpdate("dim_rb_not_exist", "x", null, null, false)),
                        List.of())))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThat(tagMetaRepository.findCategory("dim_rb_new")).isEmpty();
    }

    @Test
    void deleteCategoryGuards() {
        admin();
        create("dim_del_top", "删除测试一级", null, 940);
        create("dim_del_sub", "删除测试二级", "dim_del_top", null);

        // 有下级维度 → 拒绝
        assertThatThrownBy(() -> tagMetaService.deleteCategory("dim_del_top"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 无引用、无下级 → 删除成功
        tagMetaService.deleteCategory("dim_del_sub");
        assertThat(tagMetaRepository.findCategory("dim_del_sub")).isEmpty();

        // 注册一个挂在该维度（现为一级）下的标签（reviewing 状态也算引用）→ 拒绝删除
        tagMetaService.register(new TagMetaService.RegisterRequest(
                "dim_ref_tag_it", "维度引用标签", "dim_del_top", "string", null, "fact",
                false, 0, "risk_control", "L2", "driver", "manual", false, true), "集成测试");
        assertThatThrownBy(() -> tagMetaService.deleteCategory("dim_del_top"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }
}
