package com.tagplatform.write.service;

import com.tagplatform.write.store.TagValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * latest 宽表的手动同步。
 *
 * <p>平台正常只追加 {@code tag_value_history}，latest 由外部同步程序维护。测试/演示环境没有那个程序时，
 * 控制台打完标签、画像页却读不到值——这里提供一个<b>手工触发</b>的兜底：把流水折叠成最新值写入 latest。</p>
 *
 * <p>刻意不做定时任务：latest 的写入边界仍归外部程序，这里只解决「临时要看结果」。</p>
 */
@Service
public class LatestSyncService {

    private static final Logger log = LoggerFactory.getLogger(LatestSyncService.class);

    private final TagValueStore tagValueStore;

    public LatestSyncService(TagValueStore tagValueStore) {
        this.tagValueStore = tagValueStore;
    }

    /**
     * @param objectType 为空 = 该租户全部对象类型
     * @param objectId   为空 = 该租户全部对象
     * @return 写入 latest 的行数
     */
    public int sync(String tenantId, String objectType, String objectId) {
        long start = System.currentTimeMillis();
        int rows = tagValueStore.syncLatestFromHistory(tenantId, objectType, objectId);
        log.info("手动同步 latest tenant={} objectType={} objectId={} rows={} cost={}ms",
                tenantId, objectType, objectId, rows, System.currentTimeMillis() - start);
        return rows;
    }
}
