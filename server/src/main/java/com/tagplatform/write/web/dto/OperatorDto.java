package com.tagplatform.write.web.dto;

/**
 * 操作员四元组（契约冻结：human=人工打标 / system=系统写入 / algo=算法写入）。
 */
public record OperatorDto(
        String operatorId,
        String operatorName,
        String operatorType,
        String operatorOrg) {
}
