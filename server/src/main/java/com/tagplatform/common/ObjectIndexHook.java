package com.tagplatform.common;

/**
 * 写入受理后登记对象索引（对象下拉数据源）并更新对象名称。
 * 实现在 workbench 模块（ObjectIndexRepository），write 模块只依赖该接口，避免跨模块直接访表。
 */
public interface ObjectIndexHook {

    /**
     * 登记/刷新对象索引。objectName 为空时保留已有名称（不覆盖）。
     */
    void touch(String tenantId, String objectType, String objectId, String objectName);
}
