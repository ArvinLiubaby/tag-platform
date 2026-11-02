package com.tagplatform.meta;

import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.model.TagVersion;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.service.TagMetaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签定义版本管理：注册快照、修改快照、写入开关快照、历史详情与回滚生成新版本。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_version_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_version_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class TagVersionIntegrationTest {

    private static final String TAG = "version_demo_tag";

    @Autowired
    private TagMetaService tagMetaService;

    @Autowired
    private TagAuditRepository tagAuditRepository;

    @Test
    void versionHistoryAndRollback() {
        TagMeta v1 = tagMetaService.register(new TagMetaService.RegisterRequest(
                TAG, "版本演示 V1", "risk_score", "string", null, "fact",
                false, 0, "risk_control", "L2", "driver", "manual", false, true), "集成测试");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(tagMetaService.listVersions(TAG, 100))
                .extracting(TagVersion.Summary::version)
                .containsExactly(1);
        assertThat(tagMetaService.listVersions(TAG, 100).get(0).changeType()).isEqualTo("create");

        TagAuditRepository.TagAuditInfo audit = tagAuditRepository.list("reviewing").stream()
                .filter(a -> TAG.equals(a.tagCode()))
                .findFirst().orElseThrow();
        tagMetaService.decideAudit(audit.id(), true, "集成审核", "允许上线");

        TagMeta current = tagMetaService.list(null, TAG, null, null, null, false).stream()
                .filter(t -> TAG.equals(t.tagCode())).findFirst().orElseThrow();
        TagMeta v2 = tagMetaService.update(TAG, new TagMetaService.UpdateRequest(
                "版本演示 V2", "risk_score", "risk_control", "string", "长度<=10", "fact",
                false, 30, "L2", "driver", "manual", false, true, current.version()));
        assertThat(v2.version()).isEqualTo(2);

        TagVersion.Detail detailV2 = tagMetaService.versionDetail(TAG, 2);
        assertThat(detailV2.previous()).isNotNull();
        assertThat(detailV2.previous().tagName()).isEqualTo("版本演示 V1");
        assertThat(detailV2.definition().tagName()).isEqualTo("版本演示 V2");
        assertThat(detailV2.version().changeSummary()).contains("标签名称", "TTL", "值域");

        tagMetaService.changeWritable(TAG, false);
        List<TagVersion.Summary> versions = tagMetaService.listVersions(TAG, 100);
        assertThat(versions).extracting(TagVersion.Summary::version).containsExactly(3, 2, 1);
        assertThat(versions.get(0).changeType()).isEqualTo("writable");

        TagMeta rolledBack = tagMetaService.rollback(TAG, 1);
        assertThat(rolledBack.version()).isEqualTo(4);
        assertThat(rolledBack.tagName()).isEqualTo("版本演示 V1");
        assertThat(rolledBack.writable()).isTrue();
        assertThat(rolledBack.status()).isEqualTo("online");

        TagVersion.Detail detailV4 = tagMetaService.versionDetail(TAG, 4);
        assertThat(detailV4.version().changeType()).isEqualTo("rollback");
        assertThat(detailV4.previous()).isNotNull();
        assertThat(detailV4.definition().tagName()).isEqualTo("版本演示 V1");
        assertThat(tagMetaService.listVersions(TAG, 100)).hasSize(4);
    }

    @Test
    void migrationBackfillsBaselineVersion() {
        List<TagVersion.Summary> baseline = tagMetaService.listVersions("risk_level", 100);
        assertThat(baseline).isNotEmpty();
        assertThat(baseline.get(0).changeType()).isEqualTo("baseline");
    }
}
