package com.tagplatform.meta.model;

/**
 * 对象类型注册表（object_type_meta 表映射）。
 */
public record ObjectTypeMeta(
        String objectType,
        String typeName,
        String idField,
        String idPattern,
        String objectClass,
        int defaultTtl,
        String status) {

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }

    /**
     * 对象 ID 是否可用：<b>只要求非空</b>。
     *
     * <p>2026-11-11 起取消格式校验——`id_pattern` 仅作为接入约定保留在元数据里（供文档与提示使用），
     * 运行期不再用它拦截请求。上游的 driver_id / vehicle_id 等编码规则不统一（历史车牌、
     * 外部系统编号、测试数据并存），强制正则只会把合法数据挡在门外。</p>
     */
    public boolean idPresent(String objectId) {
        return objectId != null && !objectId.isBlank();
    }
}
