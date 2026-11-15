package com.tagplatform.query.web.dto;

import java.util.List;

/**
 * 批量对象画像视图（GET /api/v1/open/objects/profiles）。
 * records 中每个元素与单对象画像 ProfileView 结构完全一致，接入方可复用同一套解析逻辑。
 * 不做分页：total = 本次请求去重后的对象数 = records.size()。
 */
public record ProfileBatchView(
        String tenantId,
        int total,
        List<ProfileView> records) {
}
