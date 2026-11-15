package com.tagplatform.write.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.write.service.LatestSyncService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台 · latest 手动同步。
 *
 * <p>只覆盖「画像读不到值」的场景：平台本身只写流水，latest 正常由外部同步程序维护。
 * 显式 POST，前端必须点按钮确认，平台内没有定时任务调用它。</p>
 */
@RestController
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleLatestSyncController {

    private final LatestSyncService latestSyncService;

    public ConsoleLatestSyncController(LatestSyncService latestSyncService) {
        this.latestSyncService = latestSyncService;
    }

    @RequirePermission("op.latest.sync")
    @PostMapping("/tag-values/latest/sync")
    public ApiResponse<SyncResult> sync(@RequestParam("tenantId") String tenantId,
                                        @RequestParam(value = "objectType", required = false) String objectType,
                                        @RequestParam(value = "objectId", required = false) String objectId) {
        String scopedTenant = ConsolePermission.scopeTenant(tenantId);
        int rows = latestSyncService.sync(scopedTenant, objectType, objectId);
        return ApiResponse.ok(new SyncResult(rows, scopedTenant, objectType, objectId));
    }

    /** synced = 写入 latest 的行数 */
    public record SyncResult(int synced, String tenantId, String objectType, String objectId) {
    }
}
