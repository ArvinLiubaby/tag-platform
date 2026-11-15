package com.tagplatform.write;

import com.tagplatform.write.service.LatestSyncService;
import com.tagplatform.write.service.TagValueWriteService;
import com.tagplatform.write.store.TagValueStore;
import com.tagplatform.write.web.dto.DeleteRequest;
import com.tagplatform.write.web.dto.OperatorDto;
import com.tagplatform.write.web.dto.WriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * latest 手动同步：把打标流水折叠成最新值写入 latest。
 * 覆盖「同键取最后一条」「作废覆盖生效」「按对象过滤」三种口径。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_latest_sync_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_latest_sync_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class LatestSyncIntegrationTest {

    private static final String TENANT = "risk_control";
    private static final OperatorDto OPERATOR = new OperatorDto("OP1001", "张三", "human", "某交警支队");

    @Autowired
    private TagValueWriteService writeService;

    @Autowired
    private LatestSyncService latestSyncService;

    @Autowired
    private TagValueStore tagValueStore;

    private WriteRequest.WriteItem item(String objectType, String objectId, String tagCode, String value) {
        return new WriteRequest.WriteItem(objectType, objectId, tagCode, value,
                LocalDateTime.parse("2026-11-11T10:00:00"), LocalDateTime.parse("2026-11-11T10:01:00"),
                OPERATOR, "it-test", "高", null, null, null);
    }

    private void write(String objectType, String objectId, String tagCode, String value) {
        // rateLimit=false：同对象同标签 1 QPS 的频控会把「连续两条流水」的构造挡掉
        writeService.write(new WriteRequest(UUID.randomUUID().toString(),
                List.of(item(objectType, objectId, tagCode, value))), TENANT, "req-" + UUID.randomUUID(), false);
    }

    @Test
    void 同步前画像为空同步后按流水折叠出最新值() {
        String objectId = "DRV91001";
        write("driver", objectId, "risk_level", "high");
        write("driver", objectId, "risk_level", "low");

        // 平台只写流水，latest 仍是空
        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", objectId)).isEmpty();

        int synced = latestSyncService.sync(TENANT, "driver", objectId);

        // 两条流水折叠成一条，取最后写入的 low
        assertThat(synced).isEqualTo(1);
        assertThat(tagValueStore.findLatest(TENANT, "driver", objectId, "risk_level"))
                .get()
                .extracting(v -> v.tagValue() + "/" + v.status())
                .isEqualTo("low/effective");

        // 重复执行幂等：同键覆盖，不产生重复行
        assertThat(latestSyncService.sync(TENANT, "driver", objectId)).isEqualTo(1);
        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", objectId)).hasSize(1);
    }

    @Test
    void 同键取最后一条作废流水覆盖生效值() {
        String objectId = "DRV91002";
        write("driver", objectId, "risk_level", "low");

        DeleteRequest.DeleteItem deleteItem = new DeleteRequest.DeleteItem("driver", objectId, "risk_level",
                OPERATOR, "集成测试作废");
        writeService.delete(new DeleteRequest(UUID.randomUUID().toString(), List.of(deleteItem)), TENANT, "req-del");

        assertThat(latestSyncService.sync(TENANT, "driver", objectId)).isEqualTo(1);
        assertThat(tagValueStore.findLatest(TENANT, "driver", objectId, "risk_level"))
                .get()
                .extracting(v -> v.status())
                .isEqualTo("revoked");
    }

    @Test
    void 指定对象时不影响同租户其他对象() {
        write("driver", "DRV91003", "risk_level", "high");
        write("driver", "DRV91005", "risk_level", "high");

        latestSyncService.sync(TENANT, "driver", "DRV91003");

        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", "DRV91003")).hasSize(1);
        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", "DRV91005")).isEmpty();
    }

    @Test
    void 同步整个租户时覆盖该租户全部对象() {
        write("driver", "DRV91004", "risk_level", "high");
        write("driver", "DRV91006", "risk_level", "high");

        int synced = latestSyncService.sync(TENANT, null, null);

        assertThat(synced).isGreaterThanOrEqualTo(2);
        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", "DRV91004")).hasSize(1);
        assertThat(tagValueStore.listLatestByObject(TENANT, "driver", "DRV91006")).hasSize(1);
    }
}
