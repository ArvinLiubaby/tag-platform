package com.tagplatform.console;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户管理员：每个租户最多一个；只能管理本租户的操作类账号，不能创建/操作平台管理员和租户管理员。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_tenant_admin_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_tenant_admin_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TenantAdminIntegrationTest {

    @Autowired
    private ConsoleUserService userService;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void adminContext() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", null,
                "admin", "平台管理员", "平台", null);
    }

    private void tenantAdminContext() {
        RequestContext.setConsoleUser("tenant_admin_it", "TENANT_ADMIN", "租户管理员",
                "risk_control", "TA001", "租户管理员", "风控平台", "风控一组");
    }

    @Test
    void tenantAdminScopeAndRoleLimits() {
        adminContext();
        userService.create("tenant_admin_it", "租户管理员", "Tenant123", "TENANT_ADMIN",
                "risk_control", "TA001", "租户管理员", "风控平台", "风控一组");
        userService.create("other_tenant_user", "其他租户打标员", "Other123", "TAGGER",
                "system_a", "OP900", "其他租户打标员", "业务系统 A", "A 组");

        assertThatThrownBy(() -> userService.create("tenant_admin_it2", "第二管理员", "Tenant123",
                "TENANT_ADMIN", "risk_control", null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在租户管理员");

        tenantAdminContext();
        assertThat(userService.list()).allMatch(u -> "risk_control".equals(u.tenantId()));
        assertThat(userService.list()).noneMatch(u -> "other_tenant_user".equals(u.username()));

        var tagger = userService.create("tenant_tagger_it", "租户打标员", "Tagger123", "TAGGER",
                "risk_control", "CS1001", "租户打标员", "客服中心", "客服一组");
        assertThat(tagger.tenantId()).isEqualTo("risk_control");

        assertThatThrownBy(() -> userService.create("cross_tenant_user", "跨租户", "Cross123", "TAGGER",
                "system_a", "OP901", null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
        assertThatThrownBy(() -> userService.create("tenant_admin_bad", "越权管理员", "Admin123", "ADMIN",
                "risk_control", null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
        assertThatThrownBy(() -> userService.create("tenant_admin_bad2", "越权租户管理员", "Admin123",
                "TENANT_ADMIN", "risk_control", null, null, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));

        userService.changeRole("tenant_admin_it", "tenant_tagger_it", "TASK_MANAGER");
        assertThatThrownBy(() -> userService.changeRole("tenant_admin_it", "tenant_tagger_it", "TENANT_ADMIN"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));

        assertThatThrownBy(() -> userService.resetPassword("tenant_admin_it", "other_tenant_user", "Reset123"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
        assertThatThrownBy(() -> userService.changeStatus("tenant_admin_it", "other_tenant_user", "disabled"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
    }
}
