package com.tagplatform.meta;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.service.TagMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 已归档标签彻底删除（archived → deleted 终态）：
 * 控制台所有列表不再展示；tag_meta 行保留（底层数据库可查）、tag_code 不可复用；
 * 仅 archived 可删；deleted 后恢复/状态变更等写操作被拒。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_tagdelete_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_tagdelete_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TagDeleteIntegrationTest {

    @Autowired
    private TagMetaService tagMetaService;

    @Autowired
    private TagMetaRepository tagMetaRepository;

    @Autowired
    private TagAuditRepository tagAuditRepository;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void admin() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", null,
                "admin", "平台管理员", "平台", null);
    }

    private void ensureCategory() {
        if (tagMetaRepository.findCategory("test_category").isEmpty()) {
            tagMetaService.createCategory(
                    new TagMetaService.CategoryCreateRequest("test_category", "删除测试类目", null, 990));
        }
    }

    private void registerOffline(String code) {
        registerOffline(code, "test_category");
    }

    private void registerOffline(String code, String categoryCode) {
        tagMetaService.register(new TagMetaService.RegisterRequest(
                code, "删除测试标签", categoryCode, "string", null, "fact",
                false, 0, "risk_control", "L2", "driver", "manual", false, true), "集成测试");
        // 走真实审核流程：通过唯一待审核单（status=reviewing）→ online，再下线为 offline
        long auditId = tagAuditRepository.list("reviewing").stream()
                .filter(a -> code.equals(a.tagCode()))
                .findFirst().orElseThrow().id();
        tagMetaService.decideAudit(auditId, true, "集成测试", "测试通过");
        tagMetaService.changeStatus(code, "offline");
    }

    @Test
    void purgeArchivedTagHidesFromConsoleButKeepsRow() {
        admin();
        ensureCategory();

        registerOffline("purge_tag_it_a");
        tagMetaService.archive("purge_tag_it_a");
        assertThat(tagMetaRepository.findByCode("purge_tag_it_a")).isPresent();
        assertThat(tagMetaRepository.findByCode("purge_tag_it_a").get().status()).isEqualTo("archived");

        // 未归档（online）标签不可直接彻底删除
        registerOffline("purge_tag_it_online");
        tagMetaRepository.updateStatus("purge_tag_it_online", "online");
        assertThatThrownBy(() -> tagMetaService.deleteArchived("purge_tag_it_online"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 彻底删除：archived → deleted（终态）
        tagMetaService.deleteArchived("purge_tag_it_a");

        // 底层数据库仍可查到（tag_meta 行保留）
        assertThat(tagMetaRepository.findByCode("purge_tag_it_a")).isPresent();
        assertThat(tagMetaRepository.findByCode("purge_tag_it_a").get().status()).isEqualTo("deleted");

        // 控制台列表所有路径不再展示（默认 / 含归档 / 仅归档筛选）
        List<TagMeta> defaultList = tagMetaRepository.list(null, null, null, null, null, false);
        List<TagMeta> inclArchived = tagMetaRepository.list(null, null, null, null, null, true);
        List<TagMeta> archivedOnly = tagMetaRepository.list("archived", null, null, null, null, true);
        assertThat(defaultList).noneMatch(t -> "deleted".equals(t.status()));
        assertThat(inclArchived).noneMatch(t -> "deleted".equals(t.status()));
        assertThat(archivedOnly).noneMatch(t -> "purge_tag_it_a".equals(t.tagCode()));

        // tag_code 永久占用：重新注册同名编码被拒
        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "purge_tag_it_a", "重名标签", "test_category", "string", null, "fact",
                false, 0, "risk_control", "L2", "driver", "manual", false, true), "集成测试"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // deleted 为终态：恢复与状态变更均被拒
        assertThatThrownBy(() -> tagMetaService.restore("purge_tag_it_a"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> tagMetaService.changeStatus("purge_tag_it_a", "online"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void purgedTagFreesCategoryReference() {
        admin();
        if (tagMetaRepository.findCategory("dim_purge_ref").isEmpty()) {
            tagMetaService.createCategory(
                    new TagMetaService.CategoryCreateRequest("dim_purge_ref", "删除释放维度", null, 991));
        }
        registerOffline("purge_ref_tag_it", "dim_purge_ref");
        // 挂在该维度下的标签为 offline（存活）→ 维度不可删
        assertThatThrownBy(() -> tagMetaService.deleteCategory("dim_purge_ref"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 归档后标签不占用维度 → 维度可删
        tagMetaService.archive("purge_ref_tag_it");
        assertThat(tagMetaRepository.countTagRefs("dim_purge_ref")).isZero();

        // 恢复为 offline → 再次占用 → 维度又不可删
        tagMetaService.restore("purge_ref_tag_it");
        assertThat(tagMetaRepository.countTagRefs("dim_purge_ref")).isEqualTo(1);
        assertThatThrownBy(() -> tagMetaService.deleteCategory("dim_purge_ref"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));

        // 彻底删除后 → 维度可删除（deleted 不算引用）
        tagMetaService.archive("purge_ref_tag_it");
        tagMetaService.deleteArchived("purge_ref_tag_it");
        assertThat(tagMetaRepository.countTagRefs("dim_purge_ref")).isZero();
        tagMetaService.deleteCategory("dim_purge_ref");
        assertThat(tagMetaRepository.findCategory("dim_purge_ref")).isEmpty();
    }
}
