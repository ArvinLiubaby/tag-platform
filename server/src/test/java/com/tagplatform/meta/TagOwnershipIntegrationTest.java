package com.tagplatform.meta;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.service.TagMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签归属规则：tenant_id 第一个租户为归属方；平台管理员管理全部，归属租户管理员可编辑，其他租户只读。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_tag_owner_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_tag_owner_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TagOwnershipIntegrationTest {

    @Autowired
    private TagMetaService tagMetaService;

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

    private void tenantAdmin(String tenant) {
        RequestContext.setConsoleUser(tenant + "_admin", "TENANT_ADMIN", tenant + "管理员",
                tenant, "TA-" + tenant, tenant + "管理员", tenant, "默认班组");
    }

    private TagMeta registerAndApprove(String tagCode, String tenants) {
        admin();
        TagMeta created = tagMetaService.register(new TagMetaService.RegisterRequest(
                tagCode, tagCode + " 名称", "risk_score", "string", null, "fact",
                false, 0, tenants, "L2", "driver", "manual", false, true), "集成测试");
        TagAuditRepository.TagAuditInfo audit = tagAuditRepository.list("reviewing").stream()
                .filter(a -> tagCode.equals(a.tagCode())).findFirst().orElseThrow();
        tagMetaService.decideAudit(audit.id(), true, "集成审核", "通过");
        return tagMetaService.list(null, tagCode, null, null, null, false).stream()
                .filter(t -> tagCode.equals(t.tagCode())).findFirst().orElseThrow();
    }

    private TagMetaService.UpdateRequest updateRequest(String name, int version) {
        return new TagMetaService.UpdateRequest(name, "risk_score", null, "string", null, "fact",
                false, 0, "L2", "driver", "manual", false, true, version);
    }

    @Test
    void onlyFirstTenantCanEditSharedTag() {
        TagMeta tag = registerAndApprove("ownership_demo", "risk_control,system_a");
        assertThat(tag.ownedByTenant("risk_control")).isTrue();
        assertThat(tag.ownedByTenant("system_a")).isFalse();

        tenantAdmin("risk_control");
        TagMeta edited = tagMetaService.update("ownership_demo", updateRequest("归属租户修改", tag.version()));
        assertThat(edited.tagName()).isEqualTo("归属租户修改");

        tenantAdmin("system_a");
        assertThatThrownBy(() -> tagMetaService.update("ownership_demo",
                updateRequest("非归属租户修改", edited.version())))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
        assertThatThrownBy(() -> tagMetaService.changeWritable("ownership_demo", false))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tagMetaService.changeStatus("ownership_demo", "offline"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tagMetaService.rollback("ownership_demo", 1))
                .isInstanceOf(BizException.class);

        tenantAdmin("risk_control");
        tagMetaService.changeStatus("ownership_demo", "offline");
        assertThat(tagMetaService.update("ownership_demo", updateRequest("归属租户再修改", edited.version()))
                .tagName()).isEqualTo("归属租户再修改");
    }

    @Test
    void tenantAdminCanOnlyRegisterForOwnTenant() {
        tenantAdmin("system_a");
        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "cross_register_demo", "越权注册", "risk_score", "string", null, "fact",
                false, 0, "risk_control", "L2", "driver", "manual", false, true), "租户管理员"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));

        TagMeta own = tagMetaService.register(new TagMetaService.RegisterRequest(
                "own_register_demo", "本租户注册", "risk_score", "string", null, "fact",
                false, 0, null, "L2", "driver", "manual", false, true), "租户管理员");
        assertThat(own.tenantId()).isEqualTo("system_a");
        assertThat(own.status()).isEqualTo("reviewing");
    }
}
