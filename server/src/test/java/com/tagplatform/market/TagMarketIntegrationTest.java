package com.tagplatform.market;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.market.TagMarketModels.ListingView;
import com.tagplatform.market.TagMarketModels.RequestView;
import com.tagplatform.meta.model.TagMeta;
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
 * 标签市场闭环：租户上架 → 其他租户申请 → 平台管理员审批 → 扩展适用租户。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_market_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_market_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TagMarketIntegrationTest {

    private static final String TAG = "risk_level";

    @Autowired
    private TagMarketService marketService;

    @Autowired
    private TagMetaService tagMetaService;

    @Autowired
    private TagMetaRepository tagMetaRepository;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void tenantAdmin(String tenant) {
        RequestContext.setConsoleUser(tenant + "_admin", "TENANT_ADMIN", tenant + "租户管理员",
                tenant, "TA-" + tenant, tenant + "租户管理员", tenant, "默认班组");
    }

    private void admin() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", null,
                "admin", "平台管理员", "平台", null);
    }

    @Test
    void listApplyApproveGrantAndVersion() {
        tenantAdmin("risk_control");
        ListingView listed = marketService.list(TAG, "风控等级，可用于跨租户风险分级");
        assertThat(listed.ownedByCurrentTenant()).isTrue();
        assertThat(listed.availableToCurrentTenant()).isTrue();
        assertThat(marketService.listings(null, null)).extracting(ListingView::tagCode).contains(TAG);

        tenantAdmin("system_a");
        ListingView forApplicant = marketService.listings(null, null).get(0);
        assertThat(forApplicant.canApply()).isTrue();
        assertThat(forApplicant.availableToCurrentTenant()).isFalse();
        RequestView request = marketService.apply(forApplicant.id(), "业务系统 A 申请复用");
        assertThat(request.status()).isEqualTo("pending");
        assertThatThrownBy(() -> marketService.apply(forApplicant.id(), "重复申请"))
                .isInstanceOf(BizException.class);

        admin();
        List<RequestView> pending = marketService.requests("pending");
        assertThat(pending).extracting(RequestView::tagCode).contains(TAG);
        RequestView approved = marketService.approve(pending.get(0).id(), "同意共享");
        assertThat(approved.status()).isEqualTo("approved");

        TagMeta tag = tagMetaRepository.findByCode(TAG).orElseThrow();
        assertThat(tag.visibleTo("system_a")).isTrue();
        assertThat(tagMetaService.listVersions(TAG, 10))
                .anyMatch(version -> "market".equals(version.changeType()));

        tenantAdmin("system_a");
        ListingView available = marketService.listings(null, null).get(0);
        assertThat(available.availableToCurrentTenant()).isTrue();
        assertThat(available.canApply()).isFalse();
    }
}
