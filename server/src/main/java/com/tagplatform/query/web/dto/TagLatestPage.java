package com.tagplatform.query.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标签当前值视图（控制台标签查看页）：
 *   - 原始标签：tag_value_latest 最近 N 条；
 *   - 组合标签：按输入标签 recent 对象抽样实时计算，derived=true，total 为命中数。
 */
public record TagLatestPage(
        String tagCode,
        boolean derived,
        long total,
        int evaluated,
        List<Row> rows) {

    public record Row(
            String objectType,
            String objectId,
            String tagValue,
            String status,
            String detail,
            LocalDateTime bizTime,
            LocalDateTime operateTime,
            LocalDateTime expireTime,
            String operatorName,
            String source) {
    }
}
