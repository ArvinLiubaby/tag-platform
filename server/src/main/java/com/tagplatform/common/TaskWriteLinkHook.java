package com.tagplatform.common;

import java.util.List;

/**
 * 开放写入携带 task_id 时的任务联动钩子：
 * 写前校验任务归属，写后按标签写齐情况自动推进任务项。
 * 实现在 workbench 模块，write 模块只依赖该接口，避免跨模块直接访表。
 */
public interface TaskWriteLinkHook {

    void validate(String tenantId, long taskId, List<Item> items);

    void afterWrite(String tenantId, long taskId, List<Item> items);

    record Item(String objectType, String objectId, String tagCode, String tagValue,
                String remark, String confidence, String evidenceUrl) {
    }
}
